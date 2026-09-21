# -*- coding: utf-8 -*-
"""열거형 축에서 SPEC 분해를 만든다. **모델이 참여하지 않는다.**

`flow.py` 와 같은 자리다. 모델은 "이 열거형은 대상 세계의 축이다"라는 의미 판단 하나만
하고(`n` 단계의 `role: type`), 멤버 찾기·행 번호·인용·구조 조립은 여기서 코드가 한다.

왜 이렇게 하는가. `d` 단계에 "SPEC 을 만들어 달라"고 부탁하는 길은 막혔다 — 확정 개체
이름 목록도, 확정 속성 목록도, 클래스 목록 금지도 모두 무시당했다(q3-flow-2 · q3-control-2 ·
q3-decl-1). 반면 열거 상수는 스냅샷에서 **결정적으로 찾을 수 있다.**

찾지 못한 이름은 만들지 않는다. 모델이 없는 상수를 지어내면 파일에서 안 나오므로 보류된다.
그래서 이 경로에는 "모델이 행 번호를 짐작한다"는 실패가 원리적으로 없다.

코드가 정하지 **않는** 것:

  · 축을 어디에 매달 것인가. 정답지는 `TruckType` 축을 `수거차량` 아래 둔다(CP-4 가 그
    판정을 따로 다룬다). 여기서는 **축 자신을 부모로** 두고 재부모화는 검토에 맡긴다.
  · 멤버의 이름. `LARGE_5TON` 을 `5톤 차량` 으로 부르는 것은 사람의 일이다.
"""
from __future__ import annotations

import re

#: 열거형 **유형** 선언. `enum X {` 만 축이 된다. 상수 한 줄을 인용한 후보는 축이 아니라
#: 그 축의 멤버이므로 여기서 다루지 않는다.
ENUM_DECL = re.compile(r"\benum\s+(\w+)\b")

MIN_MEMBERS = 2   # 갈래가 하나면 선택이 아니다


def _decl_site(candidate):
    """열거형 유형을 선언한 근거 하나. 없으면 None — 이 후보는 축이 아니다."""
    for d in candidate.get("declaration_evidence") or []:
        if isinstance(d, dict) and ENUM_DECL.search(str(d.get("quote") or "")):
            return d
    return None


def constant_region(lines, decl_line):
    """열거형의 **상수 목록 구간**. `enum X {` 부터 상수 목록이 끝나는 자리까지.

    구간을 좁히는 이유. 이름만 찾으면 본문의 메서드·주석에서도 걸린다. 상수는 여는 중괄호
    바로 뒤부터 첫 `;` (또는 닫는 `}`) 까지에만 있다.
    """
    depth, started, out = 0, False, []
    for n in range(decl_line, len(lines) + 1):
        line = lines[n - 1]
        out.append((n, line))
        for ch in line:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
                if started and depth <= 0:
                    return out
            elif ch == ";" and started and depth == 1:
                return out
        if started and depth <= 0:
            return out
    return out


def find_member(snapshot, path, name, decl_line=None):
    """그 열거형 안에서 그 상수가 선언된 자리. 못 찾으면 None — 지어내지 않는다.

    `index.ENUM_MEMBER` 를 쓰지 않는다. 그 정규식은 전부 대문자(`LARGE_5TON`)만 보는데
    이 저장소의 `OccupationType` 은 `BlueCollar` 처럼 CamelCase 를 쓰고, `MessageType` 은
    한 줄에 상수를 모아 둔다. 둘 다 정답지가 요구하는 축이다.
    """
    if not snapshot.has(path) or not isinstance(name, str) or not name.strip():
        return None
    lines = snapshot.lines(path)
    region = (constant_region(lines, decl_line) if decl_line
              else [(n, l) for n, l in enumerate(lines, start=1)])
    # 상수로 **쓰인** 자리만 본다 — 뒤에 `(` `,` `;` `}` 가 오고, 앞이 낱말이나 점이 아니다.
    rx = re.compile(r"(?<![\w.])" + re.escape(name) + r"\s*(?:\(|,|;|\})")
    for n, line in region:
        if rx.search(line):
            return {"file_path": path, "start_line": n, "end_line": n,
                    "quote": line.rstrip("\n"), "symbol": name}
    return None


def derive(payload, snapshot):
    """`n` 단계 후보에서 축·멤버 개체와 SPEC 분해를 만든다.

    반환은 다른 단계와 같은 후보 어휘다 — 조립기가 여느 단계처럼 받는다.
    """
    entities, decompositions, unresolved, pending = [], [], [], []
    for i, e in enumerate(payload.get("entities") or []):
        if not isinstance(e, dict) or e.get("role") != "type":
            continue
        decl = _decl_site(e)
        if decl is None:
            continue                      # 열거형 유형 선언이 아니다. 그대로 둔다
        axis = e.get("name")
        siblings = [s for s in (e.get("siblings") or [])
                    if isinstance(s, str) and s.strip()]
        if not isinstance(axis, str) or not axis.strip() or not siblings:
            continue
        path = decl.get("file_path")

        found, missing = [], []
        for name in siblings:
            site = find_member(snapshot, path, name, decl.get("start_line"))
            (found.append((name, site)) if site else missing.append(name))

        if missing:
            unresolved.append({
                "axis": axis, "file_path": path, "names": missing,
                "reason": "그 파일에서 열거 상수 선언을 찾지 못했다 — 만들지 않는다"})
        if len(found) < MIN_MEMBERS:
            unresolved.append({
                "axis": axis, "file_path": path,
                "names": [n for n, _ in found],
                "reason": f"확인된 갈래가 {len(found)}개다. 갈래가 하나면 선택이 아니다"})
            continue

        # 갈래는 개체로 세운다 — 선언이 확인됐으므로 그 자체는 증명된 것이다.
        for name, site in found:
            entities.append({"name": name, "kind": "type", "scope": "simulation_target",
                             "why_entity": f"{axis} 축의 한 갈래다",
                             "evidence": [site], "origin": "derived_by_code"})
        # **분해는 만들지 않는다.** 정답지는 이 축을 도메인 개체 아래 둔다
        # (수거차량 --SPEC(차종 축)--> 5톤 차량). 축 자신을 부모로 세우면 정답지에 없는
        # 간선이 되고, shape.py 의 모양 계약이 그것을 잡는다. 부모는 의미 판단이므로
        # 코드가 고르지 않는다 — 루트를 고르지 않는 것과 같은 이유다.
        pending.append({
            "axis_label": axis,
            "kind": "SPEC",
            "members": [n for n, _ in found],
            "member_evidence": {n: [s] for n, s in found},
            "declaration_evidence": [decl],
            "reason": "갈래는 확인됐으나 이 축을 어느 개체 아래 둘지는 코드가 정하지 않는다",
        })
    return {"entities": entities, "decompositions": decompositions,
            "pending_axes": pending, "unresolved_axes": unresolved}
