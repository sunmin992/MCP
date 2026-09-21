# -*- coding: utf-8 -*-
"""형식 지표와 사람의 의미 지지 판정을 **나란히, 그러나 따로** 낸다.

두 수를 한 칸에 적으면 "인용이 실재한다"와 "그 인용이 식별을 보인다"가 뭉개진다.
사람 판정은 `exp/<run>/identity-review.json` 에서 읽고, 없으면 비워 둔다 — 없는 것을
통과로 세지 않는다.

사용:  python tally_identity.py q3-r1 q3-r2 q3-r3
"""
from __future__ import annotations

import io
import json
import os
import sys

KIT = os.path.dirname(os.path.abspath(__file__))
VERDICTS = ("지지됨", "부분 지지", "지지하지 않음", "판단 불가")


def load(path):
    return json.loads(io.open(path, encoding="utf-8").read())


def row(run_id):
    d = load(os.path.join(KIT, "exp", run_id, "ses.json"))
    checks = {c["check_id"]: c for c in (d.get("validation_results") or {}).get("checks", [])}
    ev = d.get("evidence") or []
    ok = sum(1 for e in ev if (e.get("verification") or {}).get("verdict") == "pass")
    modes = {}
    for e in ev:
        m = (e.get("verification") or {}).get("quote_match_mode")
        if m:
            modes[m] = modes.get(m, 0) + 1
    ents = d.get("entities") or []

    rev_path = os.path.join(KIT, "exp", run_id, "identity-review.json")
    counts = {v: 0 for v in VERDICTS}
    reviewed = 0
    if os.path.exists(rev_path):
        for j in load(rev_path).get("judgements") or []:
            if j.get("verdict") in counts:
                counts[j["verdict"]] += 1
                reviewed += 1
    return {
        "run": run_id,
        "entities": len(ents),
        "attrs": len(d.get("attributes") or []),
        "evidence": len(ev),
        "evidence_pass": ok,
        "exact": modes.get("exact", 0),
        "relaxed": modes.get("indent_normalized", 0) + modes.get("line_joined", 0),
        "shape_warn": checks.get("IDENTITY_EVIDENCE_SHAPE", {}).get("result", "-"),
        "shape_detail": checks.get("IDENTITY_EVIDENCE_SHAPE", {}).get("details", ""),
        "held": len(d.get("unresolved") or []),
        "reviewed": reviewed,
        "verdicts": counts,
    }


def main(run_ids):
    rows = [row(r) for r in run_ids]
    print("── 형식 검사 (자동) " + "─" * 46)
    print(f"{'실행':<10}{'개체':>5}{'속성':>5}{'근거':>5}{'통과':>5}{'정확':>5}{'보정':>5}"
          f"{'보류':>5}  {'모양경고':<8}")
    for r in rows:
        print(f"{r['run']:<10}{r['entities']:>5}{r['attrs']:>5}{r['evidence']:>5}"
              f"{r['evidence_pass']:>5}{r['exact']:>5}{r['relaxed']:>5}{r['held']:>5}"
              f"  {r['shape_warn']:<8}")
    print()
    print("── 의미 지지 (사람) " + "─" * 46)
    print(f"{'실행':<10}{'검토됨':>7}" + "".join(f"{v:>10}" for v in VERDICTS))
    for r in rows:
        if not r["reviewed"]:
            print(f"{r['run']:<10}{'미검토':>7}" + "".join(f"{'-':>10}" for _ in VERDICTS))
        else:
            print(f"{r['run']:<10}{r['reviewed']:>7}"
                  + "".join(f"{r['verdicts'][v]:>10}" for v in VERDICTS))
    print()
    for r in rows:
        if r["shape_detail"]:
            print(f"  {r['run']} 모양: {r['shape_detail']}")
    if not any(r["reviewed"] for r in rows):
        print("\n  사람 판정이 아직 없다. 기준은 의미지지-검토기준.md, 기록은 "
              "exp/<run>/identity-review.json 이다.")


if __name__ == "__main__":
    main(sys.argv[1:] or ["q3-1"])
