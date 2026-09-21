# -*- coding: utf-8 -*-
"""템플릿 초안의 **빈칸을 제공자의 일 목록으로** 바꾼다.

지금까지 보류는 `unjudged`·`unresolved` 로만 남았다. 그것은 추출기의 사정이지 제공자의
할 일이 아니다. 같은 사실을 "템플릿의 어느 칸이 왜 비었고 무엇을 하면 되는가"로 적으면
제공자의 작업이 직접 줄어든다 — 처음부터 쓰는 대신 근거가 있는 초안을 확인하고 부족한
부분만 채우면 된다.

지키는 것 셋.

  · **채운 칸과 빈 칸을 가른다.** 채운 칸에는 근거 ID 가 붙어 있어 대조할 수 있다
  · **사람이 넣은 것은 `origin: "human"` 이다**(규칙 11). 보고서가 갈라서 센다
  · **근거 코드가 바뀌면 지우지 않고 재검토로 표시한다.** 사람의 보충을 덮지 않는다
"""
from __future__ import annotations

import hashlib

from .template import SLOTS

#: 작업 종류마다 **실제로 쓰는 슬롯**만 묻는다. 준비 여부를 확인하는 작업에 허용값을
#: 물으면 빈칸이 늘기만 하고 제공자의 일이 는다.
SLOTS_OF = {
    "choose_alternative": ("decides", "options", "answer_shape", "check",
                           "applies_when", "after", "default_rule", "delivers_to"),
    "decide_count": ("decides", "check", "applies_when", "after",
                     "default_rule", "delivers_to"),
    "provide_value": ("decides", "answer_shape", "check", "applies_when", "after",
                      "default_rule", "delivers_to"),
    "check_parts_ready": ("decides", "applies_when"),
    "confirm_link": ("decides", "applies_when", "delivers_to"),
    "check_needed": ("decides", "applies_when"),
}

#: 상태 -> 제공자가 할 일.
ACTION_OF = {
    "extracted": "의미 확인",
    "proposed": "대응 승인",
    "unlinked": "기존 기능 지정",
    "missing": "보충",
    "filled": "확인 완료",
    "stale": "재검토",
}

#: 연결을 승인해야 채워지는 슬롯. 여기서만 `proposed`·`unlinked` 가 나온다 — 나머지
#: 빈칸은 근거가 없다는 뜻이지 연결을 못 찾았다는 뜻이 아니다.
LINK_SLOTS = ("delivers_to",)


def _digest(evidence_ids, evidence_files, file_digests):
    """이 칸의 근거가 선 파일들의 해시. 다음 실행에서 달라지면 재검토 대상이 된다."""
    paths = sorted({evidence_files[e] for e in evidence_ids or [] if e in evidence_files})
    if not paths:
        return None
    key = "|".join(f"{p}:{file_digests.get(p, '?')}" for p in paths)
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def _slot_evidence(task, slot):
    """이 칸이 감시할 근거. 칸에 붙은 것이 있으면 그것을, 없으면 **작업의 것**을 본다.

    빈칸도 근거를 져야 한다. 제공자가 채운 뒤 그 작업을 만든 코드가 바뀌면 다시 봐야
    하는데, 빈칸에 해시가 없으면 그 변화를 알 길이 없다.
    """
    value = task["slots"].get(slot)
    if isinstance(value, dict) and value.get("evidence_ids"):
        return list(value["evidence_ids"])
    return list(task.get("evidence_ids") or [])


def _state(task, slot):
    value = task["slots"].get(slot)
    if value is not None:
        return "extracted"
    if slot in LINK_SLOTS:
        return "proposed" if task["binding_id"] else "unlinked"
    return "missing"


def rows(draft, evidence_files=None, file_digests=None):
    """작업 × 슬롯 한 줄씩. **그 작업이 쓰는 슬롯만** 낸다."""
    evidence_files = evidence_files or {}
    file_digests = file_digests or {}
    out = []
    for task in draft["tasks"]:
        for slot in SLOTS_OF.get(task["kind"], SLOTS):
            state = _state(task, slot)
            # 연결이 제안된 칸은 값이 있어도 승인 전이다 — 확인이 아니라 승인을 묻는다.
            if slot in LINK_SLOTS and task["binding_id"] and state == "extracted":
                state = "proposed"
            ids = _slot_evidence(task, slot)
            out.append({
                "point_id": task["point_id"], "slot": slot, "state": state,
                "provider_action": ACTION_OF[state],
                "value": task["slots"].get(slot),
                "origin": "extracted" if state == "extracted" else "none",
                "reviewer": None, "why": None,
                "task_kind": task["kind"], "disposition": task["disposition"],
                "binding_id": task["binding_id"], "evidence_ids": ids,
                "source_digest": _digest(ids, evidence_files, file_digests)})
    return out


def apply(rows_in, fills, reviewer=None):
    """사람이 채운 것을 얹는다. **갈라서 표시한다** — 추출한 것과 같은 칸에 섞지 않는다.

    모르는 자리는 조용히 더하지 않고 거절한다. 오타로 만든 칸이 목록에 들어오면 제공자가
    있지도 않은 슬롯을 채우게 된다.
    """
    index = {(r["point_id"], r["slot"]): r for r in rows_in}
    out = [dict(r) for r in rows_in]
    at = {(r["point_id"], r["slot"]): r for r in out}
    for fill in fills or []:
        key = (fill.get("point_id"), fill.get("slot"))
        if key not in index:
            raise ValueError(f"목록에 없는 자리다: {key}")
        row = at[key]
        row.update({"value": fill.get("value"), "origin": "human",
                    "reviewer": fill.get("reviewer") or reviewer,
                    "why": fill.get("why"), "state": "filled",
                    "provider_action": ACTION_OF["filled"],
                    "extracted_value": index[key]["value"]})
    return out


def restale(filled_rows, fresh_rows):
    """근거 코드가 바뀐 칸을 재검토로 표시한다. **값은 남긴다.**

    사람이 채운 칸에만 붙인다. 추출한 칸은 새 실행이 다시 채우므로 표시할 것이 없다.
    """
    fresh = {(r["point_id"], r["slot"]): r for r in fresh_rows}
    out = []
    for row in filled_rows:
        row = dict(row)
        now = fresh.get((row["point_id"], row["slot"]))
        if row["origin"] == "human" and now is not None:
            was = row.get("source_digest")
            if was != now.get("source_digest"):
                row["state"] = "stale"
                row["provider_action"] = ACTION_OF["stale"]
                row["stale_from"] = was
                row["stale_to"] = now.get("source_digest")
        out.append(row)
    return out


def summary(rows_in):
    """제공자 부담을 재는 수. **합치지 않는다** — 채운 주체를 갈라 센다."""
    by_state, by_origin = {}, {"extracted": 0, "human": 0, "none": 0}
    for r in rows_in:
        by_state[r["state"]] = by_state.get(r["state"], 0) + 1
        by_origin[r["origin"]] = by_origin.get(r["origin"], 0) + 1
    return {"total": len(rows_in), "by_state": by_state, "by_origin": by_origin}
