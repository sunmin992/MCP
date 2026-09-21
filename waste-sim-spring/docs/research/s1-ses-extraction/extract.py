# -*- coding: utf-8 -*-
"""SES 추출·검증·검토 실행기.

  python extract.py policy   --rule P-pilot --run-id r1
  python extract.py snapshot --run-id r1
  python extract.py run      --run-id r1 --plan T2 --model gpt-4.1-mini
  python extract.py validate --run-id r1
  python extract.py report   --run-id r1
  python extract.py review   --run-id r1 --decisions decisions.json
  python extract.py pes      --run-id r1 --request pes-request.json
  python extract.py pipeline --rule P-pilot --run-id r1 --plan T2 --model gpt-4.1-mini

기존 run_s1.py · run_t.py · runs/ 는 건드리지 않는다. 산출물은 exp/<run-id>/ 아래다.
같은 run-id 로 다시 만들지 않는다 — 덮어쓰기는 실패로 끝난다.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import subprocess
import sys
import time
import re

KIT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(KIT, "..", "..", ".."))
sys.path.insert(0, KIT)

from sesx import (assemble, contract, input_policy, llm as llm_mod, pes as pes_mod,  # noqa: E402
                  report as report_mod, review as review_mod, run_store,
                  snapshot as snap_mod, stages as stages_mod, validate as validate_mod)

EXP = os.path.join(KIT, "exp")


def run_dir(run_id):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}", run_id):
        raise ValueError("run-id must be a portable directory name")
    return snap_mod.safe_path(EXP, run_id)


def _write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "x", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")
    return path


def _read(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def _latest(d):
    pointer = os.path.join(d, "current.json")
    if not os.path.exists(pointer):
        return _read(os.path.join(d, "ses.json"))  # read-only legacy fallback
    ref = _read(pointer)
    doc = _read(snap_mod.safe_path(d, ref["path"]))
    if snap_mod.digest(doc) != ref["sha256"]:
        raise ValueError("artifact hash mismatch")
    return doc


def _publish(d, kind, doc):
    n = 1
    while os.path.exists(os.path.join(d, f"{kind}-{n:04d}.json")):
        n += 1
    name = f"{kind}-{n:04d}.json"
    _write(os.path.join(d, name), doc)
    # Only the pointer is mutable; every scientific artifact remains immutable.
    run_store._write_atomic(os.path.join(d, "current.json"), json.dumps(
        {"path": name, "sha256": snap_mod.digest(doc)}, ensure_ascii=False) + "\n")
    return name


def _raw_ids(d, doc):
    return _read(snap_mod.safe_path(d, doc.get("extraction_run", {}).get(
        "raw_ids_path", "raw-ids.json")))


def _git_rev():
    try:
        rev = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO).decode().strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO).strip())
        return {"rev": rev, "dirty": dirty}
    except Exception as e:
        return {"rev": None, "dirty": None, "error": str(e)}


# ---------------------------------------------------------------- 명령

def cmd_policy(a):
    d = run_dir(a.run_id)
    policy = input_policy.load_policy(os.path.join(KIT, "sesx", "rules", a.rule + ".json"))
    if os.path.exists(os.path.join(d, "input-decisions.json")):
        if not a.allow_existing:
            print("입력 기록이 이미 있다. 재개하려면 --allow-existing 을 쓴다.")
            return 3
        old = _read(os.path.join(d, "input-decisions.json"))
        if old.get("policy_sha256") != snap_mod.digest(policy):
            print("입력 정책이 바뀌었다. 새 run-id 가 필요하다.")
            return 3
        return 0 if old.get("approved") else 2
    rep = input_policy.decide(REPO, policy)
    _write(os.path.join(d, "input-decisions.json"), rep)
    print(f"정책 {rep['policy_id']} · 포함 {len(rep['included'])} · 제외 {len(rep['excluded'])} "
          f"· 차단 {len(rep['blocks'])}")
    for b in rep["blocks"][:10]:
        print(f"  차단 {b['code']}: {b['path']} — {b['detail']}")
    if rep["blocks"]:
        print("모델을 부르지 않는다. 입력 경계를 고치고 다시 돌린다.")
        return 2
    return 0


def cmd_snapshot(a):
    d = run_dir(a.run_id)
    rep = _read(os.path.join(d, "input-decisions.json"))
    if rep["blocks"]:
        print("차단된 입력이다. 스냅샷을 만들지 않는다.")
        return 2
    out = os.path.join(d, "snapshot")
    if os.path.exists(out):
        if not a.allow_existing:
            print("이미 스냅샷이 있다 — 덮어쓰지 않는다")
            return 3
        frozen = snap_mod.Snapshot(out)
        if frozen.manifest.get("decision_report_sha256") != snap_mod.digest(rep):
            raise ValueError("snapshot input policy mismatch")
        for path in frozen.by_path:
            frozen.text(path)
        return 0
    m = snap_mod.materialize(REPO, rep, out, f"snap-{a.run_id}", git=_git_rev())
    print(f"스냅샷 {m['snapshot_id']} · {m['totals']['files']}파일 "
          f"· {m['totals']['lines']:,}행 · {m['totals']['bytes']:,}바이트")
    return 0


def _client(a):
    if a.fake:
        return llm_mod.FakeClient(_read(a.fake))
    if getattr(a, "provider", "openai") == "ollama":
        return llm_mod.OllamaClient(a.model, host=a.ollama_host, num_ctx=a.num_ctx,
                                    temperature=a.temperature, max_tokens=a.max_tokens,
                                    timeout=a.ollama_timeout, tokenizer_path=a.tokenizer)
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise SystemExit("OPENAI_API_KEY 가 없다")
    return llm_mod.OpenAiChatClient(a.model, key, max_tokens=a.max_tokens,
                                    temperature=a.temperature)


def cmd_run(a):
    d = run_dir(a.run_id)
    if (os.path.exists(os.path.join(d, "ses.json")) or os.path.exists(os.path.join(d, "current.json"))) and not a.allow_existing:
        print("이미 초안이 있다. 새 run-id 를 쓰거나 --allow-existing 으로 새 리비전을 만든다.")
        return 3
    snap = snap_mod.Snapshot(os.path.join(d, "snapshot"))
    store = run_store.RunStore(d)
    client = _client(a)
    settings = {"max_tokens": a.max_tokens, "temperature": a.temperature,
                "response_format": "json_object"}
    t0 = time.time()
    try:
        payloads, errors = stages_mod.run_pipeline(
            store, snap, client, plan=a.plan,
            stages=tuple(a.stages.split(",")) if a.stages else None, settings=settings,
            line_numbers=a.line_numbers)
    except (OSError, ValueError) as err:
        payloads, errors = {}, [{"kind": "integrity_or_input", "error": str(err)}]
    # 결합은 모델이 아니라 코드가 만든다. e1 의 자리와 e2 의 주체를 짝지어 방향을 낸다.
    if "e1" in payloads and "e2" in payloads:
        payloads = dict(payloads)
        payloads["flow"] = stages_mod.flow_payload(payloads)
    raw_ids = assemble.raw_ids_of(payloads)
    # 실행이 끝난 것과 추출 범위를 채운 것은 다르다. 부분 산출물을 완성된 SES 로
    # 위장하지 않는다 — 한 단계가 죽어도 독립 단계는 돌므로, 이제 "끝났다"만으로는
    # 무엇이 들었는지 알 수 없다.
    planned = [st for st in stages_mod.PLANS[a.plan]]
    blocked = sorted({e["stage"] for e in errors if e.get("kind") == "blocked"})
    stage_failed = sorted({e["stage"] for e in errors
                           if e.get("stage") and e.get("kind") != "blocked"})
    missing = [st for st in planned if st not in payloads]
    run_status = ("completed" if not missing else
                  "partial" if payloads else "failed")
    extraction_run = {
        "run_id": a.run_id, "experiment_kind": "fresh_extraction", "strategy": a.plan,
        "status": run_status,
        "stages_planned": planned, "stages_missing": missing,
        "stages_failed": stage_failed, "stages_blocked": blocked,
        "model": getattr(client, "describe", {}), "model_revision": None,
        "settings": settings, "extractor_revision": run_store.implementation_revision(),
        "validation_profile": ("evidence-v1" if a.plan in stages_mod.EVIDENCE_PLANS
                               else "legacy"),
        "expected_stages": list(stages_mod.PLANS[a.plan]),
        "prompt_manifest_path": "stages/*/request.json",
        "stage_records_path": "stages/", "request_response_path": "stages/",
        "usage_path": "completions.jsonl", "elapsed_s": round(time.time() - t0, 1),
        "errors": errors, "stages_completed": sorted(payloads),
        "stages_from_store": "only resume-key-verified prerequisites",
    }
    source_snapshot = {
        "snapshot_id": snap.snapshot_id, "status": snap.manifest["status"],
        "selection_rule": {"rule_id": snap.manifest.get("policy_id"),
                           "approval_status": "approved"},
        "manifest_path": "snapshot/manifest.json",
        "files": snap.manifest["files"],
        "manifest_sha256": snap.manifest.get("manifest_sha256"),
        "exclusion_report_path": "input-decisions.json",
    }
    stamp = str(time.time_ns())
    extraction_run["raw_ids_path"] = f"raw-ids-{stamp}.json"
    extraction_run["payloads_path"] = f"payloads-{stamp}.json"
    _write(os.path.join(d, extraction_run["raw_ids_path"]), raw_ids)
    _write(os.path.join(d, extraction_run["payloads_path"]), payloads)
    try:
        doc = assemble.build(payloads, snap.snapshot_id, f"{a.run_id}-draft-{stamp}",
                             extraction_run, source_snapshot,
                             no_new_entity_stages=stages_mod.NO_NEW_ENTITY_STAGES)
    except (TypeError, ValueError, AttributeError, KeyError) as err:
        errors.append({"kind": "assembly", "error": str(err)})
        extraction_run["status"] = "failed"
        doc = contract.new_artifact(f"{a.run_id}-failed-{stamp}", extraction_run, source_snapshot)
        doc["unresolved"].append({"id": "U-assembly", "item_ids": [],
            "reason_code": "schema_violation", "origin_raw": payloads,
            "explanation": str(err), "resolution_needed": "Inspect preserved raw responses."})
    _publish(d, "draft", doc)
    print(f"실행 {run_status} · 단계 {sorted(payloads)} · 실패 {stage_failed} "
          f"· 차단 {blocked}")
    print(f"단계 {sorted(payloads)} · 실패 {len(errors)} · 개체 {len(doc['entities'])} "
          f"· 속성 {len(doc['attributes'])} · 분해 {len(doc['decompositions'])} "
          f"· 결합 {len(doc['couplings'])} · 보류 {len(doc['unresolved'])}")
    return 1 if errors else 0


def cmd_validate(a):
    d = run_dir(a.run_id)
    doc = _latest(d)
    snap = snap_mod.Snapshot(os.path.join(d, "snapshot"))
    raw_ids = _raw_ids(d, doc)
    validate_mod.run(doc, snapshot=snap, raw_ids=raw_ids)
    _publish(d, "validated", doc)
    vr = doc["validation_results"]
    for c in vr["checks"]:
        print(f"  {c['result']:<12} {c['check_id']:<24} {c['details']}")
    print(f"승인 가능: {vr['approval_eligible']} · 차단 실패 {vr['blocking_failure_ids']}")
    return 0 if vr["approval_eligible"] else 1


def cmd_report(a):
    d = run_dir(a.run_id)
    doc = _latest(d)
    store = run_store.RunStore(d)
    p = report_mod.write(doc, os.path.join(d, f"report-{time.time_ns()}.md"),
                         run_errors=(doc.get("extraction_run") or {}).get("errors"),
                         store_attempts=store.attempts())
    print(f"보고서: {p}")
    return 0


def cmd_review(a):
    d = run_dir(a.run_id)
    doc = _latest(d)
    snap = snap_mod.Snapshot(os.path.join(d, "snapshot"))
    raw_ids = _raw_ids(d, doc)
    decisions = review_mod.load_decisions(a.decisions)
    decisions_path = os.path.join(d, f"review-decisions-{time.time_ns()}.json")
    _write(decisions_path, {"reviewer": a.reviewer, "decisions": decisions})
    n = 1
    while os.path.exists(os.path.join(d, f"ses-rev{n}.json")):
        n += 1
    new = review_mod.apply_decisions(doc, decisions, f"{a.run_id}-rev{n}",
                                     reviewer=a.reviewer, decisions_path=decisions_path)
    review_mod.finalize(new, snapshot=snap, raw_ids=raw_ids)
    _publish(d, "reviewed", new)
    report_mod.write(new, os.path.join(d, f"report-review-{time.time_ns()}.md"))
    ap = new["approval"]
    print(f"리비전 rev{n} · 승인 {'예' if ap['approved'] else '아니오'} "
          f"· 범위 {len(ap['scope'])}개")
    for r in ap["reasons"]:
        print(f"  막힌 이유: {r}")
    return 0 if ap["approved"] else 1


def cmd_pes(a):
    """요청한 시뮬레이터가 루트를 정한다. 코드는 성립 여부만 본다."""
    d = run_dir(a.run_id)
    doc = _latest(d)
    request = pes_mod.load_request(a.request)
    rid = request.get("request_id")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}", str(rid or "")):
        print("request_id 가 디렉터리 이름으로 쓸 수 없다")
        return 3
    out_dir = snap_mod.safe_path(os.path.join(d, "pes"), rid)
    n = 1
    while os.path.exists(os.path.join(out_dir, f"pes-{n:04d}.json")):
        n += 1
    result = pes_mod.build(doc, request, f"{rid}-{n:04d}")
    # 요청도 함께 남긴다. 같은 요청을 두 번 쓰지 않았다는 것을 뒤에서 확인할 수 있어야 한다.
    _write(os.path.join(out_dir, f"request-{n:04d}.json"), request)
    path = _write(os.path.join(out_dir, f"pes-{n:04d}.json"), result)
    vr = result["validation_results"]
    for c in vr["checks"]:
        print(f"  {c['result']:<12} {c['check_id']:<28} {c['details']}")
    if result["status"] != "built":
        print(f"PES 를 만들지 않았다. 차단 실패 {vr['blocking_failure_ids']}")
        print("요청을 고쳐서 다시 돌린다 — 코드가 빠진 선택을 채우지 않는다.")
        return 1
    print(f"PES {result['pes_id']} · 개체 {len(result['entities'])} "
          f"· 분해 {len(result['decompositions'])} · 결합 {len(result['couplings'])} "
          f"· 빠짐 {len(result['dropped'])} · 활성 미상 {len(result['activation_unknown'])} "
          f"· 범위 내 보류 {len(result['unresolved_in_scope'])}")
    print(f"산출물: {path}")
    return 0


def cmd_pipeline(a):
    extraction_rc = 0
    for step in (cmd_policy, cmd_snapshot, cmd_run):
        rc = step(a)
        if rc not in (0,):
            print(f"중단: {step.__name__} rc={rc}")
            if step is not cmd_run:
                return rc
            extraction_rc = rc
    validation_rc = cmd_validate(a)
    cmd_report(a)
    return extraction_rc or validation_rc


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p, **kw):
        p.add_argument("--run-id", required=True)
        p.add_argument("--allow-existing", action="store_true")
        return p

    common(sub.add_parser("policy")).add_argument("--rule", default="P-evidence-v1")
    common(sub.add_parser("snapshot"))
    p = common(sub.add_parser("run"))
    p.add_argument("--plan", default="T2-evidence", choices=sorted(stages_mod.PLANS))
    p.add_argument("--stages", default=None)
    p.add_argument("--model", default="gpt-4.1-mini")
    p.add_argument("--max-tokens", type=int, default=16000)
    p.add_argument("--temperature", type=float, default=None)
    p.add_argument("--fake", default=None, help="시험용 고정 응답 JSON")
    p.add_argument("--provider", default="openai", choices=("openai", "ollama"))
    p.add_argument("--ollama-host", default="http://127.0.0.1:11434")
    p.add_argument("--num-ctx", type=int, default=32768)
    p.add_argument("--ollama-timeout", type=int, default=7200)
    p.add_argument("--tokenizer", default=None, help="Optional local tokenizer.json for preflight")
    p.add_argument("--line-numbers", action="store_true",
                   help="코드에 행 번호를 붙여 준다 (조건이 달라진다. 요청 기록에 남는다)")
    common(sub.add_parser("validate"))
    common(sub.add_parser("report"))
    p = common(sub.add_parser("review"))
    p.add_argument("--decisions", required=True)
    p.add_argument("--reviewer", default=None)
    p = common(sub.add_parser("pes"))
    p.add_argument("--request", required=True, help="PES 요청 JSON. 사람이 쓴다")
    p = common(sub.add_parser("pipeline"))
    p.add_argument("--rule", default="P-evidence-v1")
    p.add_argument("--plan", default="T2-evidence", choices=sorted(stages_mod.PLANS))
    p.add_argument("--stages", default=None)
    p.add_argument("--model", default="gpt-4.1-mini")
    p.add_argument("--max-tokens", type=int, default=16000)
    p.add_argument("--temperature", type=float, default=None)
    p.add_argument("--fake", default=None)
    p.add_argument("--provider", default="openai", choices=("openai", "ollama"))
    p.add_argument("--ollama-host", default="http://127.0.0.1:11434")
    p.add_argument("--num-ctx", type=int, default=32768)
    p.add_argument("--ollama-timeout", type=int, default=7200)
    p.add_argument("--tokenizer", default=None, help="Optional local tokenizer.json for preflight")
    p.add_argument("--line-numbers", action="store_true")

    a = ap.parse_args(argv)
    return {"policy": cmd_policy, "snapshot": cmd_snapshot, "run": cmd_run,
            "validate": cmd_validate, "report": cmd_report, "review": cmd_review,
            "pes": cmd_pes, "pipeline": cmd_pipeline}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
