"""V3 — 판정 기록(plan-history JSONL)과 브로커 매칭을 실행마다 묶어 센다.

실행 결과(runs/*.json)의 planIds 로 plan-history 파일의 사건을 고르고, brokerCalls 로 매칭 차례를 센다.
파일 날짜는 UTC 라 한 요청이 두 파일에 걸칠 수 있으므로 폴더의 파일을 모두 읽는다.

  rounds          그 planId 의 round 사건 수(답변 · 출처 · 실험 변수가 바뀐 호출)
  rechecks        recheck 사건 수(같은 답에 근거만 보탠 호출)
  completedAt     처음으로 complete 가 된 단계(없으면 None)
  minRounds       서버가 계산한 최소 필요 단계(마지막 round 사건 값)
  excess          completedAt - minRounds
  evidence        마지막 판정의 FILLED 인스턴스 근거 표시 수(VERIFIED / UNVERIFIED / NOT_CHECKED)
  reasons         단계 전체에서 붙은 UNFILLED 사유 코드 수
  builds          build 사건(valid / rejectedBy)
  matchRounds     브로커 find_simulators 호출 수, verdicts 는 그 판정 순서

python plan_history.py <plan-history 폴더>   → plan-history.json, plan-history.md
"""
import glob, json, os, sys
from collections import Counter
from statistics import mean

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "plan-history")

events = {}
for f in sorted(glob.glob(os.path.join(DIR, "plan-history-*.jsonl"))):
    for line in open(f, encoding="utf-8"):
        if line.strip():
            e = json.loads(line)
            events.setdefault(e.get("planId"), []).append(e)

rows = []
for p in sorted(glob.glob(os.path.join(HERE, "runs", "*.json"))):
    run = json.load(open(p, encoding="utf-8"))
    ev = sorted((e for pid in run.get("planIds") or [] for e in events.get(pid, [])), key=lambda e: e["ts"])
    rounds = [e for e in ev if e["event"] == "round"]
    rechecks = [e for e in ev if e["event"] == "recheck"]
    judged = rounds + rechecks
    done = [e.get("completedAtRound") for e in judged if e.get("complete")]
    last = max(judged, key=lambda e: e["ts"]) if judged else None
    reasons = Counter(i["reason"]["code"] for e in judged for i in e.get("instances", []) if i.get("reason"))
    evidence = Counter(i.get("evidence") for i in (last or {}).get("instances", []) if i.get("status") == "FILLED")
    builds = [{"valid": e.get("valid"), "rejectedBy": e.get("rejectedBy")} for e in ev if e["event"] == "build"]
    completed = done[0] if done else None
    minr = rounds[-1].get("minRounds") if rounds else None
    rows.append({
        "id": run["id"], "server": run.get("serverCalled"), "planIds": run.get("planIds") or [],
        "matchRounds": len(run.get("brokerCalls", [])),
        "verdicts": [b.get("verdict") for b in run.get("brokerCalls", [])],
        "matchIdLinked": any(e.get("matchId") for e in ev),
        "rounds": len(rounds), "rechecks": len(rechecks),
        "completedAt": completed, "minRounds": minr,
        "excess": completed - minr if completed is not None and minr is not None else None,
        "evidence": dict(evidence), "reasons": dict(reasons), "builds": builds,
        "scenarioState": run.get("scenarioState"),
    })

json.dump(rows, open(os.path.join(HERE, "plan-history.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

traced = [r for r in rows if r["rounds"]]
lines = ["# 판정 기록 집계", "",
         f"실행 {len(rows)}개 가운데 판정 기록이 있는 실행 {len(traced)}개(planId 를 받는 서버는 장량동뿐이다).", ""]
if traced:
    ok = [r for r in traced if r["completedAt"] is not None]
    lines += [
        "| 지표 | 값 |", "|---|---|",
        f"| 완료에 닿은 실행 | {len(ok)} / {len(traced)} |",
        f"| 평균 단계(round) | {mean(r['rounds'] for r in traced):.2f} |",
        f"| 평균 재확인(recheck) | {mean(r['rechecks'] for r in traced):.2f} |",
        f"| 평균 완료 단계 | {mean(r['completedAt'] for r in ok):.2f} |" if ok else "| 평균 완료 단계 | - |",
        f"| 평균 최소 필요 단계 | {mean(r['minRounds'] for r in ok):.2f} |" if ok else "| 평균 최소 필요 단계 | - |",
        f"| 최소 단계에 끝난 실행 | {sum(r['excess'] == 0 for r in ok)} / {len(ok)} |",
        f"| 브로커 matchId 가 이어진 실행 | {sum(r['matchIdLinked'] for r in traced)} / {len(traced)} |",
        "",
    ]
    ev = Counter()
    for r in traced:
        ev.update(r["evidence"])
    lines += ["마지막 판정의 FILLED 근거 표시: " + ", ".join(f"{k} {v}" for k, v in sorted(ev.items())), ""]
    rs = Counter()
    for r in traced:
        rs.update(r["reasons"])
    lines += ["단계 중 붙은 UNFILLED 사유: " + (", ".join(f"{k} {v}" for k, v in rs.most_common()) or "없음"), ""]
lines += ["## 실행별", "", "| 실행 | 매칭 판정 | 단계 | 재확인 | 완료 | 최소 | 초과 | 사유 | 생성 |", "|---|---|---|---|---|---|---|---|---|"]
for r in rows:
    b = ", ".join("ok" if x["valid"] else f"거절({x['rejectedBy']})" for x in r["builds"]) or "-"
    lines.append(f"| {r['id']} | {' → '.join(v or '?' for v in r['verdicts']) or '-'} | {r['rounds']} | {r['rechecks']} | "
                 f"{r['completedAt'] if r['completedAt'] is not None else '-'} | {r['minRounds'] if r['minRounds'] is not None else '-'} | "
                 f"{r['excess'] if r['excess'] is not None else '-'} | {', '.join(f'{k}:{v}' for k, v in r['reasons'].items()) or '-'} | {b} |")
open(os.path.join(HERE, "plan-history.md"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(len(rows), "runs,", len(traced), "with plan history")
