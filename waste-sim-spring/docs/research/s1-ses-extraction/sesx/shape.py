# -*- coding: utf-8 -*-
"""트리 모양 계약. ref-v8 이 쓰는 세 층만 합법으로 둔다.

    boundary ─ASPECT→ boundary · set
    set      ─MULTI → 개체 하나(stateful)
    stateful ─SPEC  → type

판정만 한다. 집행은 조립기가 보류로 한다 — 계약 위반으로 올리지 않는 이유는
거짓 양성이 승인 경로 전체를 막기 때문이다.

역할이 `unknown` 이면 막지 않는다. 확인된 위반만 잡는다.
"""
from __future__ import annotations

# (부모 역할, 분해 종류) -> 허용되는 자식 역할
LEGAL_MEMBERS = {
    ("boundary", "ASPECT"): ("boundary", "set"),
    ("set", "MULTI"): ("stateful",),
    ("stateful", "SPEC"): ("type",),
}

UNKNOWN = "unknown"


def edge_error(parent_kind, decomp_kind, member_kinds):
    """모양 계약 위반 문구. 합법이거나 판정할 수 없으면 None."""
    kind = str(decomp_kind or "").upper()
    if parent_kind == UNKNOWN or not parent_kind:
        return None
    allowed = LEGAL_MEMBERS.get((parent_kind, kind))
    if allowed is None:
        legal = [f"{p} ─{k}→" for (p, k) in LEGAL_MEMBERS if p == parent_kind]
        return (f"{parent_kind} 부모에 {kind} 분해는 모양 계약에 없다. "
                f"허용: {legal or '없음'}")
    if kind == "MULTI" and len(member_kinds) != 1:
        return f"MULTI 는 개체 하나를 복제한다. 자식 {len(member_kinds)}개를 받았다"
    bad = [k for k in member_kinds if k != UNKNOWN and k and k not in allowed]
    if bad:
        return (f"{parent_kind} ─{kind}→ 의 자식은 {list(allowed)} 여야 한다. "
                f"받은 역할: {bad}")
    return None


def _sites(evidence):
    out = set()
    for e in evidence or []:
        if not isinstance(e, dict):
            continue
        path, start = e.get("file_path"), e.get("start_line")
        if isinstance(path, str) and isinstance(start, int):
            out.add((path, start, e.get("end_line", start)))
    return out


def shared_declaration(member_evidence, members):
    """두 자식의 역할 근거가 **같은 선언 행**을 가리키면 그 두 이름.

    `new double[nB][nT]` 한 줄을 두 자식의 근거로 쓴 경우다. 같은 선언의 서로
    다른 차원은 형제이지 부모-자식이 아니다.
    """
    names = [m for m in (members or []) if isinstance(m, str)]
    for i, a in enumerate(names):
        sa = _sites((member_evidence or {}).get(a))
        if not sa:
            continue
        for b in names[i + 1:]:
            if sa & _sites((member_evidence or {}).get(b)):
                return (a, b)
    return None
