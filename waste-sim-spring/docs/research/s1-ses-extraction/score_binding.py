# -*- coding: utf-8 -*-
"""연결 채점기 — 제공자 부담을 잰다.

`구성결정-SES-대응표.md` 를 읽지 않는다. 그 문서는 사람이 쓴 산문이고, 대조 상대는
**코드가 된 손표**인 `SesFieldMapping.java` 다. 문서와 코드가 갈라져 있으면 채점이
조용히 문서 쪽을 따라가기 때문이다.

내는 수는 넷이고 **합치지 않는다**. `score_roles.py` 가 오통과와 누락을 따로 내는 것과
같은 규칙이다 — 합친 하나의 '정확도'는 어느 쪽을 고쳐야 하는지 말해 주지 않는다.

    시스템이 근거와 함께 채운 칸   filled_with_evidence
    제공자가 보충할 칸             blank
    손표와 어긋난 대응             mismatch
    연결을 못 찾은 필드            unlinked

    python score_binding.py exp/r1
"""
from __future__ import annotations

import io
import json
import os
import re
import sys

KIT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(KIT, "..", "..", ".."))

#: 손으로 쓴 대응표. 이것이 대조 상대다.
HAND_TABLE = os.path.join(REPO, "src", "main", "java", "com", "wastesim",
                          "ses", "SesFieldMapping.java")

#: 이번 증분이 끝까지 잇기로 한 셋.
PILOT = ("truckType", "truckCount", "dispatchIntervalMinutes")

_BINDING = re.compile(r"new\s+FieldBinding\s*\(\s*\"([^\"]+)\"\s*,\s*\"([^\"]+)\"")


def hand_written(path=HAND_TABLE):
    """손표의 (지점, 답변 필드) 쌍. 파일이 없으면 대조할 것이 없다고 말한다."""
    if not os.path.exists(path):
        raise FileNotFoundError(f"손으로 쓴 대응표가 없다: {path}")
    with io.open(path, encoding="utf-8") as f:
        source = f.read()
    return [{"point_id": point, "answer_field": field}
            for point, field in _BINDING.findall(source)]


def _entity_of(point_id):
    """지점 ID 에서 개체 이름. 축 이름이 갈려도 개체는 같아야 한다."""
    parts = point_id.split(":")
    return parts[1] if len(parts) > 1 else None


def _kind_of(point_id):
    return point_id.split(":", 1)[0]


def score(draft, hand=None, pilot=PILOT):
    """작업 초안을 손표와 대조한다. 어긋난 것을 지우지 않고 드러낸다."""
    hand = hand if hand is not None else hand_written()
    by_answer = {h["answer_field"]: h for h in hand}
    ours = {t["slots"].get("asks_as"): t for t in draft["tasks"] if t["slots"].get("asks_as")}

    rows = {}
    for field in pilot:
        want = by_answer.get(field)
        got = ours.get(field)
        if got is None:
            rows[field] = {"answer_field": "missing", "point_id": "missing",
                           "hand": want["point_id"] if want else None, "ours": None,
                           "why": "이 답변 필드에 닿은 작업 후보가 없다"}
            continue
        same = want is not None and want["point_id"] == got["point_id"]
        row = {"answer_field": "covered",
               "point_id": "match" if same else "differs",
               "hand": want["point_id"] if want else None, "ours": got["point_id"],
               "disposition": got["disposition"]}
        if not same and want is not None:
            row["same_entity"] = _entity_of(want["point_id"]) == _entity_of(got["point_id"])
            row["same_kind"] = _kind_of(want["point_id"]) == _kind_of(got["point_id"])
        rows[field] = row

    filled = blank = 0
    for task in draft["tasks"]:
        for slot, value in task["slots"].items():
            if slot == "decides":
                continue                       # 무엇을 정하는가는 언제나 트리에서 나온다
            if value is None:
                blank += 1
            else:
                filled += 1

    return {
        "pilot": rows,
        "slots": {"total": filled + blank, "filled_with_evidence": filled, "blank": blank},
        "mismatch": sum(1 for r in rows.values() if r["point_id"] == "differs"),
        "unlinked": sum(1 for t in draft["tasks"] if t["binding_id"] is None),
        "tasks": len(draft["tasks"]),
    }


def _print(result):
    print(f"작업 후보 {result['tasks']} · 연결 못 함 {result['unlinked']}")
    s = result["slots"]
    print(f"슬롯 {s['total']} — 근거와 함께 채움 {s['filled_with_evidence']} "
          f"· 제공자가 보충할 칸 {s['blank']}")
    print(f"손표와 어긋난 대응 {result['mismatch']}")
    for field, row in result["pilot"].items():
        mark = {"covered": "닿음", "missing": "닿지 못함"}[row["answer_field"]]
        print(f"  {field:26} {mark}  지점 {row['point_id']}")
        if row["point_id"] == "differs":
            print(f"    손표 {row['hand']}")
            print(f"    우리 {row['ours']}")


def main(argv=None):
    argv = list(argv if argv is not None else sys.argv[1:])
    if not argv:
        print(__doc__.strip().splitlines()[-1].strip())
        return 2
    path = os.path.join(argv[0], "binding", "template.json")
    if not os.path.exists(path):
        print(f"템플릿 초안이 없다: {path}")
        return 2
    with io.open(path, encoding="utf-8") as f:
        draft = json.load(f)
    result = score(draft)
    _print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
