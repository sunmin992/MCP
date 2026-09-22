# -*- coding: utf-8 -*-
"""어느 **계획**이 결정 표면에 닿는가.

`bind → template` 을 산출물마다 돌려, 그 SES 가 실행설정의 결정에 얼마나 닿는지 잰다.
LLM 을 부르지 않는다 — 이미 있는 산출물을 다시 읽을 뿐이다.

이 측정이 쉽게 틀리는 자리 둘을 코드가 막는다.

**하나. 닿을 수 없었던 실행을 못 닿은 것으로 세지 않는다.** 스냅샷에 `SimulationConfig.java`
가 없으면 그것은 계획의 성질이 아니라 경계의 성질이다. P-micro 로 돌린 8건이 그렇다 —
파일 5개짜리 스냅샷에 설정 클래스가 없다.

**둘. 모델·정책이 계획과 같이 움직인다.** `jn-*` 는 전부 P-target · gpt-4.1-mini 이고
`q3-*` 는 전부 P-pilot · qwen3-coder:30b 다. 무리를 건너 비교하면 계획의 차이인지 모델의
차이인지 갈리지 않는다. 그래서 (정책, 모델)이 같은 실행끼리만 나란히 놓고, 계획이 하나뿐인
무리에서는 **비교하지 않는다.**

계획 이름은 `request.json` 에 적혀 있다. 단계 폴더 이름으로 짐작하지 않는다 — 단계 목록이
같은 계획이 여럿 있다(`T2-evidence` 와 `T2-whole-control` 이 그렇다).

    python score_reach.py                 # exp/ 전부
    python score_reach.py --runs 'jn-*'   # 골라서
"""
from __future__ import annotations

import argparse
import glob
import io
import json
import os
import shutil
import sys
import tempfile

KIT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, KIT)

#: 이 파일이 스냅샷에 없으면 설정에 닿을 길이 없다. 결정이 사는 자리이기 때문이다.
REQUIRED_FILE = "SimulationConfig.java"

#: 설정 필드의 총수. 닿은 비율의 분모다(`binding.collect` 가 내는 수와 같아야 한다).
CONFIG_FIELDS = 42


def eligible(manifest):
    """이 실행이 애초에 닿을 수 있었는가. (가능, 못 한 사유)"""
    names = {os.path.basename(f.get("path", "")) for f in (manifest or {}).get("files") or []}
    if REQUIRED_FILE not in names:
        return False, f"스냅샷에 {REQUIRED_FILE} 이 없다 — 닿을 수 없었다"
    return True, None


def produced(artifact):
    """이 실행이 잴 것을 내기는 했는가. (냈다, 못 낸 사유)

    근거가 하나도 없는 산출물은 **못 닿은 것이 아니라 아무것도 못 낸 것**이다. 0%로 세면
    계획의 성질이 아닌 것이 계획의 수가 된다 — jn-S2-1 이 그 자리다.
    """
    if not (artifact or {}).get("evidence"):
        return False, "산출물에 근거가 없다 — 잴 것을 내지 못했다"
    return True, None


def _read(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def run_meta(run_dir):
    """실행 하나의 조건. **없는 것은 짐작하지 않고 None 으로 둔다.**"""
    out = {"run": os.path.basename(run_dir.rstrip("/\\")), "plan": None, "model": None,
           "policy": None, "eligible": False, "why": "확인하지 못했다"}
    for req in sorted(glob.glob(os.path.join(run_dir, "stages", "*", "attempt-1",
                                             "request.json")))[:1]:
        try:
            payload = _read(req)
        except (OSError, ValueError):
            continue
        out["plan"] = payload.get("plan")
        model = payload.get("model")
        out["model"] = model.get("name") if isinstance(model, dict) else model
    try:
        out["policy"] = _read(os.path.join(run_dir, "input-decisions.json")).get("policy_id")
    except (OSError, ValueError):
        pass
    try:
        out["eligible"], out["why"] = eligible(
            _read(os.path.join(run_dir, "snapshot", "manifest.json")))
    except (OSError, ValueError):
        out["eligible"], out["why"] = False, "스냅샷을 읽지 못했다"
    return out


def reach(draft):
    """이 산출물이 결정에 얼마나 닿았는가.

    **닿은 결정은 작업 수가 아니라 설정 필드 수로 센다.** 한 필드에 작업 둘이 붙어도
    정해진 결정은 하나다.
    """
    tasks = draft["tasks"]
    bound = [t for t in tasks if t["binding_id"]]
    fields = {t["slots"].get("delivers_to") for t in bound if t["slots"].get("delivers_to")}
    bridge = draft.get("bridge") or {}
    return {
        "tasks": len(tasks), "bound": len(bound), "fields": len(fields),
        "usable": sum(1 for t in bound if t["disposition"] != "unknown"),
        "evidence": bridge.get("evidence", 0),
        "evidence_at_config": bridge.get("evidence_in_binding_files", 0),
        "field_names": sorted(fields),
    }


def group(runs):
    """(정책, 모델) 별로 묶는다 — 계획과 같이 움직이는 것들이다."""
    out = {}
    for r in runs:
        out.setdefault((r["policy"], r["model"]), []).append(r)
    return out


def by_plan(runs):
    """무리 -> 계획 -> 모아 놓은 수. `n` 과 `single_run` 을 함께 낸다."""
    out = {}
    for key, members in group(runs).items():
        plans = {}
        for r in members:
            plans.setdefault(r["plan"], []).append(r)
        out[key] = {plan: {"n": len(rs), "single_run": len(rs) == 1, "runs": rs}
                    for plan, rs in plans.items()}
    return out


def comparable(rows):
    """계획을 비교해도 되는 무리가 하나라도 있는가. 계획이 하나뿐이면 비교가 아니다."""
    return any(len(plans) > 1 for plans in rows.values())


def _mean(values):
    return sum(values) / len(values) if values else 0.0


def measure(exp_dir, pattern="*"):
    """산출물마다 bind·template 을 돌려 닿은 정도를 잰다. 원본을 건드리지 않는다."""
    import extract                                    # 늦게 들여온다 — 시험이 가볍게 돈다

    rows = []
    for d in sorted(glob.glob(os.path.join(exp_dir, pattern, ""))):
        if not os.path.exists(os.path.join(d, "ses.json")):
            continue
        meta = run_meta(d)
        if meta["eligible"]:
            made, why = produced(_read(os.path.join(d, "ses.json")))
            if not made:
                meta["eligible"], meta["why"] = False, why
        if not meta["eligible"] or not meta["plan"]:
            rows.append({**meta, "reach": None})
            continue
        tmp = tempfile.mkdtemp(prefix="reach-")
        try:
            extract.EXP = tmp
            run = meta["run"]
            os.makedirs(os.path.join(tmp, run))
            shutil.copy(os.path.join(d, "ses.json"), os.path.join(tmp, run, "ses.json"))
            shutil.copytree(os.path.join(d, "snapshot"), os.path.join(tmp, run, "snapshot"))
            extract.main(["bind", "--run-id", run, "--rule", "P-binding"])
            extract.main(["template", "--run-id", run])
            draft = _read(os.path.join(tmp, run, "binding", "template.json"))
            rows.append({**meta, "reach": reach(draft)})
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return rows


def report(rows):
    """무리별로 계획을 나란히 놓는다. **무리를 건너 비교하지 않는다.**"""
    skipped = [r for r in rows if r["reach"] is None]
    usable = [r for r in rows if r["reach"] is not None]
    out = by_plan(usable)

    print(f"잰 실행 {len(usable)} · 제외 {len(skipped)}")
    for r in skipped:
        print(f"  제외 {r['run']:28} {r['why']}")

    for (policy, model), plans in sorted(out.items(), key=lambda kv: str(kv[0])):
        print(f"\n=== {policy} · {model} ===")
        if len(plans) < 2:
            print("  계획이 하나뿐이다 — 이 무리에서는 계획을 비교하지 않는다.")
        print(f"  {'계획':22} {'n':>2}  {'설정근거':>8} {'작업':>5} {'붙은것':>6} "
              f"{'닿은필드':>8} {'쓸수있는것':>10}")
        for plan, cell in sorted(plans.items(), key=lambda kv: -_mean(
                [r["reach"]["fields"] for r in kv[1]["runs"]])):
            rs = [r["reach"] for r in cell["runs"]]
            pct = _mean([100 * r["evidence_at_config"] / r["evidence"] if r["evidence"] else 0
                         for r in rs])
            mark = " *" if cell["single_run"] else ""
            print(f"  {plan + mark:22} {cell['n']:2}  {pct:7.0f}% {_mean([r['tasks'] for r in rs]):5.0f} "
                  f"{_mean([r['bound'] for r in rs]):6.0f} "
                  f"{_mean([r['fields'] for r in rs]):8.0f} "
                  f"{_mean([r['usable'] for r in rs]):10.0f}")
    print("\n* 은 실행이 하나뿐인 계획이다. 그 수는 계획의 성질이라고 부를 수 없다.")
    if not comparable(out):
        print("어느 무리에도 계획이 둘 이상 없다 — 계획 비교를 하지 않았다.")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default="*", help="실행 이름 글로브")
    ap.add_argument("--exp", default=os.path.join(KIT, "exp"))
    ap.add_argument("--json", default=None, help="잰 수를 이 파일에 적는다")
    a = ap.parse_args(argv)
    rows = measure(a.exp, a.runs)
    report(rows)
    if a.json:
        with io.open(a.json, "w", encoding="utf-8") as f:
            f.write(json.dumps(rows, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
