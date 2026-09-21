# -*- coding: utf-8 -*-
"""후보 묶음마다 **짧은 판정**을 받는다. 긴 산출물을 한 번에 받지 않는다.

모델이 답하는 것은 후보 ID 와 한 줄짜리 판정뿐이다. 인용·행 번호·개체 ID 는 코드가 채운다.
그래서 이 경로에는 "모델이 행 번호를 짐작한다"는 실패가 없다.

묶음이 잘리면 **반으로 쪼개 다시 묻는다.** 끝내 답을 못 받은 후보는 보류하고, 다른
후보는 계속 처리한다. 한 묶음의 실패가 전체를 멈추지 않는다.

한 가지 조심할 것. 재시도는 조건을 바꾼다 — 같은 후보를 더 작은 묶음에서 다시 묻는 것은
같은 질문이 아니다. 그래서 몇 번째 시도였는지, 묶음이 얼마였는지를 판정마다 남긴다.
"""
from __future__ import annotations

import json

from .llm import LlmError

#: 한 번에 묻는 후보 수. 잘리면 반으로 줄여 다시 묻는다.
DEFAULT_BATCH = 12
MIN_BATCH = 1

#: 쪼개기 상한. 이보다 더 잘게 나누지 않는다 — 끝없이 쪼개며 비용만 쓰는 것을 막는다.
MAX_SPLITS = 3

VERDICTS = ("simulation_target", "support_software", "unclear")
ROLES = ("object", "set", "type", "none")


def _answers(text):
    """짧은 판정 목록. 모양이 아니면 그대로 올린다 — 조용히 넘기지 않는다."""
    payload = json.loads(text)
    if not isinstance(payload, dict):
        raise ValueError("최상위가 객체가 아니다")
    rows = payload.get("judgments")
    if not isinstance(rows, list):
        raise ValueError("judgments 가 배열이 아니다")
    return rows


def _clean(row, known):
    """판정 하나를 계약에 맞춘다. 모르는 값은 버리지 않고 unclear 로 내려 적는다."""
    if not isinstance(row, dict):
        return None, "판정이 객체가 아니다"
    cid = row.get("cand_id")
    if cid not in known:
        return None, f"모르는 후보 ID {cid!r}"
    verdict = row.get("verdict")
    if verdict not in VERDICTS:
        return {"cand_id": cid, "verdict": "unclear", "role": "none",
                "why": row.get("why"), "note": f"알 수 없는 판정값 {verdict!r}"}, None
    role = row.get("role") if row.get("role") in ROLES else "none"
    return {"cand_id": cid, "verdict": verdict, "role": role,
            "why": row.get("why")}, None


def judge_batch(client, stage, system, render, cands, batch=DEFAULT_BATCH, splits=0,
                on_attempt=None):
    """한 묶음을 묻고, 잘리면 반으로 쪼개 다시 묻는다.

    반환: (판정 목록, 보류 목록). 보류에는 **무엇을 왜 못 받았는지**가 들어간다.
    """
    if not cands:
        return [], []
    known = {c["cand_id"] for c in cands}
    if len(cands) > batch:
        out, held = [], []
        for i in range(0, len(cands), batch):
            a, b = judge_batch(client, stage, system, render, cands[i:i + batch],
                               batch, splits, on_attempt)
            out += a
            held += b
        return out, held

    try:
        text, usage = client.complete(stage, system, render(cands))
        if usage.get("done_reason") == "length":
            raise ValueError("model output reached the token limit")
        rows = _answers(text)
    except (LlmError, ValueError, json.JSONDecodeError) as err:
        if on_attempt:
            on_attempt(cands, "failed", str(err), usage=None)
        # 쪼개서 다시 묻는다. 하나짜리도 실패하면 그 후보만 보류한다.
        if len(cands) > MIN_BATCH and splits < MAX_SPLITS:
            half = max(MIN_BATCH, len(cands) // 2)
            a1, h1 = judge_batch(client, stage, system, render, cands[:half],
                                 half, splits + 1, on_attempt)
            a2, h2 = judge_batch(client, stage, system, render, cands[half:],
                                 half, splits + 1, on_attempt)
            return a1 + a2, h1 + h2
        return [], [{"cand_id": c["cand_id"], "name": c["name"], "anchor": c["anchor"],
                     "reason": "unjudged", "detail": str(err), "batch": len(cands),
                     "splits": splits} for c in cands]

    if on_attempt:
        on_attempt(cands, "completed", None, usage=usage)
    good, held = [], []
    seen = set()
    for row in rows:
        clean, why = _clean(row, known)
        if clean is None:
            continue                      # 모르는 ID 는 아래에서 미답으로 잡힌다
        clean["batch"] = len(cands)
        clean["splits"] = splits
        good.append(clean)
        seen.add(clean["cand_id"])
    for c in cands:
        if c["cand_id"] not in seen:
            held.append({"cand_id": c["cand_id"], "name": c["name"], "anchor": c["anchor"],
                         "reason": "unjudged", "detail": "묶음에 답이 오지 않았다",
                         "batch": len(cands), "splits": splits})
    return good, held


def to_payload(cands, judgments, held):
    """판정을 조립기가 아는 후보 어휘로 옮긴다. **근거는 코드가 채운다.**"""
    by_id = {c["cand_id"]: c for c in cands}
    entities, unjudged = [], list(held)
    for j in judgments:
        c = by_id.get(j["cand_id"])
        if c is None:
            continue
        if j["verdict"] != "simulation_target" or j["role"] == "none":
            unjudged.append({**j, "name": c["name"], "anchor": c["anchor"],
                             "reason": "not_in_world" if j["verdict"] != "simulation_target"
                             else "no_role"})
            continue
        entities.append({
            "name": c["name"],
            "kind": {"object": "stateful", "set": "set", "type": "type"}[j["role"]],
            "scope": "simulation_target",
            "why_entity": j.get("why"),
            "anchor": c["anchor"],
            "evidence": list(c["sites"]),
            "origin": "judged",
            "judgment": {k: j.get(k) for k in ("verdict", "role", "batch", "splits")},
        })
    return {"entities": entities, "unjudged": unjudged}
