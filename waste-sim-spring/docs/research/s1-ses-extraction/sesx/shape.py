# -*- coding: utf-8 -*-
"""트리 모양 계약. ref-v8 이 **실제로 지키는** 불변만 합법으로 둔다.

정답지의 간선을 전부 훑어 뽑은 표다. 좁게 잡으면 정답지 자신이 위반이 된다 —
`경로 교통 시스템 ─aspect→ 수거 경로`(자식이 집합이 아니다), `민원 판정 ─aspect→
적재초과 판정`(개체가 ASPECT 부모다), `시나리오 실험 ─spec→ 단일 실행`(boundary 가
SPEC 부모다) 이 모두 정답지에 있다.

남는 불변은 셋이다.

    MULTI  부모는 **집합**이고 자식은 **개체 하나**다 (정답지 5건이 모두 그렇다)
    SPEC   자식은 **유형**이다 — 축의 값들이지 부분이 아니다
    ASPECT 자식은 부분이다. **유형은 올 수 없다**
    집합은 MULTI 로만 가른다. 유형은 부모가 되지 않는다

판정만 한다. 집행은 조립기가 보류로 한다 — 계약 위반으로 올리지 않는 이유는
거짓 양성이 승인 경로 전체를 막기 때문이다.

역할이 `unknown` 이면 막지 않는다. 확인된 위반만 잡는다.
"""
from __future__ import annotations

# (부모 역할, 분해 종류) -> 허용되는 자식 역할
PART_KINDS = ("boundary", "set", "stateful")

LEGAL_MEMBERS = {
    ("boundary", "ASPECT"): PART_KINDS,
    ("stateful", "ASPECT"): PART_KINDS,
    ("set", "MULTI"): ("stateful",),
    ("boundary", "SPEC"): ("type",),
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


def shared_declaration(member_evidence, members, decomp_kind=None):
    """두 자식의 역할 근거가 **같은 선언 행**을 가리키면 그 두 이름.

    `new double[nB][nT]` 한 줄을 두 자식의 근거로 쓴 경우다. 같은 선언의 서로 다른
    차원은 형제이지 부모-자식이 아니다. 한 줄이 두 자식을 똑같이 가리킨다면 그 줄은
    **어느 쪽이 어느 쪽인지 보이지 못하므로** 역할 근거가 아니다.

    **SPEC 은 보지 않는다.** 축의 값들은 열거 상수 선언 한 줄에 함께 적히는 것이
    정상이다 — 정답지의 `직업 축`(5종)이 그렇다.
    """
    if str(decomp_kind or "").upper() == "SPEC":
        return None
    names = [m for m in (members or []) if isinstance(m, str)]
    for i, a in enumerate(names):
        sa = _sites((member_evidence or {}).get(a))
        if not sa:
            continue
        for b in names[i + 1:]:
            if sa & _sites((member_evidence or {}).get(b)):
                return (a, b)
    return None
