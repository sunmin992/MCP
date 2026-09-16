# -*- coding: utf-8 -*-
"""SES 추출·검증·검토 실행기.

  python extract.py policy   --rule P-pilot --run-id r1
  python extract.py snapshot --run-id r1
  python extract.py run      --run-id r1 --plan T2 --model gpt-4.1-mini
  python extract.py validate --run-id r1
  python extract.py report   --run-id r1
  python extract.py review   --run-id r1 --decisions decisions.json
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

KIT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(KIT, "..", "..", ".."))
sys.path.insert(0, KIT)

from sesx import (assemble, contract, input_policy, llm as llm_mod, report as report_mod,  # noqa: E402
                  review as review_mod, run_store, snapshot as snap_mod, stages as stages_mod,
                  validate as validate_mod)

EXP = os.path.join(KIT, "exp")


def run_dir(run_id):
    return os.path.join(EXP, run_id)


def _write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")
    return path


def _read(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


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
    if os.path.exists(os.path.join(d, "input-decisions.json")) and not a.allow_existing:
        print(f"이미 있다: {d}/input-decisions.json — 덮어쓰지 않는다")
        return 3
    policy = input_policy.load_policy(os.path.join(KIT, "sesx", "rules", a.rule + ".json"))
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
    if os.path.exists(os.path.join(out, "manifest.json")) and not a.allow_existing:
        print("이미 스냅샷이 있다 — 덮어쓰지 않는다")
        return 3
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
    if os.path.exists(os.path.join(d, "ses.json")) and not a.allow_existing:
        print("이미 초안이 있다 — 덮어쓰지 않는다. 새 run-id 를 쓰거나 --allow-existing")
        return 3
    snap = snap_mod.Snapshot(os.path.join(d, "snapshot"))
    store = run_store.RunStore(d)
    client = _client(a)
    settings = {"max_tokens": a.max_tokens, "temperature": a.temperature,
                "response_format": "json_object"}
    t0 = time.time()
    payloads, errors = stages_mod.run_pipeline(
        store, snap, client, plan=a.plan,
        stages=tuple(a.stages.split(",")) if a.stages else None, settings=settings,
        line_numbers=a.line_numbers)

    # 이번 호출에서 돌지 않은 단계도 기록이 있으면 조립에 넣는다 — 산출물은 이 실행
    # 디렉터리의 상태를 나타내야 한다. 어디서 왔는지는 extraction_run 에 남긴다.
    from_store = []
    for st in stages_mod.PLANS.get(a.plan, ()):
        if st in payloads:
            continue
        rec = store.last_completion(st)
        if rec:
            pth = os.path.join(d, rec["payload_path"].replace("/", os.sep))
            if os.path.exists(pth):
                payloads[st] = _read(pth)
                from_store.append(st)
    # 결합은 모델이 아니라 코드가 만든다. e1 의 자리와 e2 의 주체를 짝지어 방향을 낸다.
    if "e1" in payloads and "e2" in payloads:
        payloads = dict(payloads)
        payloads["flow"] = stages_mod.flow_payload(payloads)
    raw_ids = assemble.raw_ids_of(payloads)
    extraction_run = {
        "run_id": a.run_id, "experiment_kind": "fresh_extraction", "strategy": a.plan,
        "status": "failed" if errors else "completed",
        "model": getattr(client, "describe", {}), "model_revision": None,
        "settings": settings, "extractor_revision": contract.SCHEMA_VERSION,
        "prompt_manifest_path": "stages/*/request.json",
        "stage_records_path": "stages/", "request_response_path": "stages/",
        "usage_path": "completions.jsonl", "elapsed_s": round(time.time() - t0, 1),
        "errors": errors, "stages_completed": sorted(payloads),
        "stages_from_store": sorted(from_store),
    }
    source_snapshot = {
        "snapshot_id": snap.snapshot_id, "status": snap.manifest["status"],
        "selection_rule": {"rule_id": snap.manifest.get("policy_id"),
                           "approval_status": "approved"},
        "manifest_path": "snapshot/manifest.json",
        "files": snap.manifest["files"],
        "exclusion_report_path": "input-decisions.json",
    }
    doc = assemble.build(payloads, snap.snapshot_id, f"{a.run_id}-draft-1",
                         extraction_run, source_snapshot,
                         no_new_entity_stages=stages_mod.NO_NEW_ENTITY_STAGES)
    _write(os.path.join(d, "raw-ids.json"), raw_ids)
    _write(os.path.join(d, "ses.json"), doc)
    print(f"단계 {sorted(payloads)} · 실패 {len(errors)} · 개체 {len(doc['entities'])} "
          f"· 속성 {len(doc['attributes'])} · 분해 {len(doc['decompositions'])} "
          f"· 결합 {len(doc['couplings'])} · 보류 {len(doc['unresolved'])}")
    return 1 if errors else 0


def cmd_validate(a):
    d = run_dir(a.run_id)
    doc = _read(os.path.join(d, "ses.json"))
    snap = snap_mod.Snapshot(os.path.join(d, "snapshot"))
    raw_ids = _read(os.path.join(d, "raw-ids.json"))
    validate_mod.run(doc, snapshot=snap, raw_ids=raw_ids)
    _write(os.path.join(d, "ses.json"), doc)
    vr = doc["validation_results"]
    for c in vr["checks"]:
        print(f"  {c['result']:<12} {c['check_id']:<24} {c['details']}")
    print(f"승인 가능: {vr['approval_eligible']} · 차단 실패 {vr['blocking_failure_ids']}")
    return 0 if vr["approval_eligible"] else 1


def cmd_report(a):
    d = run_dir(a.run_id)
    doc = _read(os.path.join(d, "ses.json"))
    store = run_store.RunStore(d)
    p = report_mod.write(doc, os.path.join(d, "report.md"),
                         run_errors=(doc.get("extraction_run") or {}).get("errors"),
                         store_attempts=store.attempts())
    print(f"보고서: {p}")
    return 0


def cmd_review(a):
    d = run_dir(a.run_id)
    doc = _read(os.path.join(d, "ses.json"))
    snap = snap_mod.Snapshot(os.path.join(d, "snapshot"))
    raw_ids = _read(os.path.join(d, "raw-ids.json"))
    decisions = review_mod.load_decisions(a.decisions)
    n = 1
    while os.path.exists(os.path.join(d, f"ses-rev{n}.json")):
        n += 1
    new = review_mod.apply_decisions(doc, decisions, f"{a.run_id}-rev{n}",
                                     reviewer=a.reviewer, decisions_path=a.decisions)
    review_mod.finalize(new, snapshot=snap, raw_ids=raw_ids)
    _write(os.path.join(d, f"ses-rev{n}.json"), new)
    report_mod.write(new, os.path.join(d, f"report-rev{n}.md"))
    ap = new["approval"]
    print(f"리비전 rev{n} · 승인 {'예' if ap['approved'] else '아니오'} "
          f"· 범위 {len(ap['scope'])}개")
    for r in ap["reasons"]:
        print(f"  막힌 이유: {r}")
    return 0 if ap["approved"] else 1


def cmd_pipeline(a):
    for step in (cmd_policy, cmd_snapshot, cmd_run):
        rc = step(a)
        if rc not in (0,):
            print(f"중단: {step.__name__} rc={rc}")
            if step is not cmd_run:
                return rc
    cmd_validate(a)
    return cmd_report(a)


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p, **kw):
        p.add_argument("--run-id", required=True)
        p.add_argument("--allow-existing", action="store_true")
        return p

    common(sub.add_parser("policy")).add_argument("--rule", default="P-pilot")
    common(sub.add_parser("snapshot"))
    p = common(sub.add_parser("run"))
    p.add_argument("--plan", default="T2", choices=sorted(stages_mod.PLANS))
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
    p = common(sub.add_parser("pipeline"))
    p.add_argument("--rule", default="P-pilot")
    p.add_argument("--plan", default="T2", choices=sorted(stages_mod.PLANS))
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
            "pipeline": cmd_pipeline}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
