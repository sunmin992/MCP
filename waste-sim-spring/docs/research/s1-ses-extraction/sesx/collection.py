# -*- coding: utf-8 -*-
"""컬렉션 필드에서 MULTI 분해를 만든다. **모델이 참여하지 않는다.**

`axis.py` 와 같은 자리다. 축이 열거 상수에서 나왔듯, 집합은 컬렉션 필드에서 나온다.

왜 코드가 하는가. 도메인 개체를 모델에게 물었을 때(`n` 단계의 `object` 역할) 24개 파일에
하나씩 답했다 — `RoutePlanner` · `TravelTimeCalculator` · `ChatMessage` 까지. 과제가 금한
"클래스 목록을 개체 목록으로 바꾸는 것"이다. 반면 컬렉션 필드는 스냅샷에서 결정적으로
찾을 수 있고, **원소 유형이 이 프로젝트에 선언된 유형인가**가 잡음을 거른다.

거르는 자리 셋:

  1. 원소 유형이 스냅샷에 `class|enum|record|interface` 로 선언돼 있어야 한다.
     `List<String>` · `Map<String, Double>` 이 여기서 빠진다.
  2. **클래스 본문 깊이**(중괄호 깊이 1)에 있어야 한다. 메서드 안의 지역 변수를 거른다 —
     `final List<WasteType> types = ...` 처럼 수식어가 붙은 지역 변수가 실제로 6건 걸렸다.
  3. 이름 뒤가 `=` 또는 `;` 여야 한다. 메서드 선언과 매개변수를 거른다.

코드가 정하지 **않는** 것:

  · 이름. `sites` 를 `수거지점 집합` 이라 부르는 것은 사람의 일이다.
  · 반복 개수. 컬렉션 선언은 몇 개인지 말하지 않는다 — `unknown` 으로 둔다.
  · 이 집합이 대상 세계의 것인가. `List<Series> series` 는 화면 응답일 수 있다.
    거르지 않고 후보로 내고, 판정은 근거 검증과 사람이 한다.
"""
from __future__ import annotations

import re

from .index import TYPE_DECL, code_lines

#: 클래스 본문의 컬렉션 필드. 이름 뒤가 `=` 나 `;` 여야 한다(메서드·매개변수 제외).
FIELD = re.compile(
    r"^\s*(?:public|private|protected|static|final|transient|volatile|\s)+"
    r"(List|Set|Collection|Map|Queue|Deque)\s*<([^<>]*(?:<[^<>]*>)?[^<>]*)>\s+(\w+)\s*(?:=|;)")

#: 유형 선언의 종류 → 개체 종류.
DECL_KIND = {"enum": "type", "class": "stateful", "record": "stateful",
             "interface": "type", "@interface": "type"}

CLASS_BODY_DEPTH = 1


def declared_types(snapshot):
    """스냅샷에 선언된 유형과 그 선언 자리. 원소 유형을 거르는 기준이다."""
    out = {}
    for path in snapshot.by_path:
        for n, line in enumerate(snapshot.lines(path), start=1):
            m = TYPE_DECL.search(line)
            if m and m.group(1) not in out:
                word = re.search(r"\b(class|interface|enum|record|@interface)\b", line)
                out[m.group(1)] = {
                    "file_path": path, "start_line": n, "end_line": n,
                    "quote": line.rstrip("\n"), "symbol": m.group(1),
                    "decl": word.group(1) if word else "class"}
    return out


def container_fields(snapshot, declared):
    """클래스 본문에 선언된 컬렉션 필드 중 원소가 프로젝트 유형인 것."""
    found = []
    for path in snapshot.by_path:
        depth, owner = 0, None
        for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
            t = TYPE_DECL.search(code)
            if t and depth == 0:
                owner = t.group(1)      # 이 필드를 가진 유형. 동명 필드를 가르는 열쇠다
            m = FIELD.match(code)
            if m and depth == CLASS_BODY_DEPTH:
                args = [a.strip() for a in m.group(2).split(",")]
                element = args[-1]
                if element in declared:
                    found.append({
                        "container": m.group(3), "shape": m.group(1), "element": element,
                        "owner_type": owner,
                        "site": {"file_path": path, "start_line": n, "end_line": n,
                                 "quote": line.rstrip("\n"), "symbol": m.group(3)}})
            depth += code.count("{") - code.count("}")
    return found


def derive(snapshot):
    """집합·원소 개체와 MULTI 분해. 같은 원소를 담는 필드가 여럿이면 각각 낸다."""
    declared = declared_types(snapshot)
    entities, decompositions, seen = [], [], set()
    for f in container_fields(snapshot, declared):
        decl = declared[f["element"]]
        if f["element"] not in seen:
            seen.add(f["element"])
            entities.append({
                "name": f["element"],
                "kind": DECL_KIND.get(decl["decl"], "stateful"),
                "scope": "simulation_target",
                "why_entity": f"{f['shape']} 의 원소 유형으로 선언됐다",
                # 유형은 선언 자리가 곧 동일성이다.
                "anchor": {"file_path": decl["file_path"], "owner_type": None,
                           "symbol": f["element"]},
                "evidence": [{k: v for k, v in decl.items() if k != "decl"}],
                "origin": "derived_by_code"})
        name = f["container"]
        ckey = (f["site"]["file_path"], f.get("owner_type"), name)
        if ckey in seen:
            continue
        seen.add(ckey)
        entities.append({
            "name": name, "kind": "set", "scope": "simulation_target",
            "why_entity": f"{f['element']} 여럿을 담는 {f['shape']} 필드다",
            # 필드는 **소유 유형까지** 있어야 가려진다. `byId` 가 세 클래스에 있다.
            "anchor": {"file_path": f["site"]["file_path"],
                       "owner_type": f.get("owner_type"), "symbol": name},
            "evidence": [f["site"]], "origin": "derived_by_code"})
        decompositions.append({
            "parent": name, "kind": "MULTI", "label": f"{f['element']} 반복",
            "members": [f["element"]],
            "member_evidence": {f["element"]: [{k: v for k, v in decl.items()
                                                if k != "decl"}]},
            "selection": None,
            # 컬렉션 선언은 몇 개인지 말하지 않는다. 지어내지 않는다.
            "multiplicity": {"count_expression": None, "minimum": None, "maximum": None,
                             "evidence": [f["site"]]},
            "evidence": [f["site"]],
            "activation": {"state": "unknown", "clauses": [], "combination": None,
                           "coverage": "incomplete"},
            "origin": "derived_by_code"})
    return {"entities": entities, "decompositions": decompositions}
