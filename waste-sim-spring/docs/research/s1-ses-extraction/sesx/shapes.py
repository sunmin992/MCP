# -*- coding: utf-8 -*-
"""근거가 가리키는 코드의 **모양**을 이름 붙인다.

이것은 판정이 아니라 기록이다.

  · 선언·생성·등록을 인용했다고 식별이 입증되는 것이 아니다 — 그 클래스가 실행 지원
    클래스일 수도 있고, 생성 코드가 결과 DTO 를 만드는 자리일 수도 있다.
  · 반대로 배열 인덱스·키의 대응이 식별을 보여 주는데 그 모양에는 맞지 않을 수 있다.

충분조건도 아니고 필요조건도 아니므로 **차단하지 않는다.** 같은 종류의 잘못된 근거가
반복되고 코드로 판별할 조건이 드러나면 그때 자동 검사 후보가 된다.
"""
from __future__ import annotations

import re

SHAPES = (
    ("type_declaration", re.compile(r"\b(?:class|interface|enum|record|@interface)\s+\w+")),
    # 열거 상수 한 줄. 정답지의 SPEC 자식(생산직·학생·5톤 차량…)이 이 모양으로 선언된다.
    # type_declaration 뒤에 둔다 — `enum OccupationType {` 은 그쪽이 먼저 잡아야 한다.
    ("enum_member", re.compile(r"^\s*[A-Z][A-Z0-9_]{2,}\s*(?:\(|,|;|$)")),
    ("array_alloc", re.compile(r"=\s*new\s+[\w<>., ]+\s*\[")),
    # 등록을 생성보다 먼저 본다 — `map.put(k, new V(...))` 는 만드는 자리이기도 하지만
    # 그 개체가 **무엇으로 찾아지는가**를 보이는 자리이기도 하다. 뒤쪽이 식별에 가깝다.
    ("registration", re.compile(r"\.\s*(?:put|add|register|save|insert)\s*\(")),
    ("construction", re.compile(r"\bnew\s+\w+\s*\(")),
    ("branch", re.compile(r"^\s*(?:if|else|switch|case|while|for)\b")),
    ("field_declaration", re.compile(
        r"^\s*(?:public|private|protected|static|final|transient|volatile)\s+[\w<>\[\],.?& ]+\s+\w+\s*(?:=|;)")),
    ("assignment", re.compile(r"[^=!<>]=[^=]")),
)

# 식별을 **직접** 보여 주는 모양이라고 흔히 여겨지는 것들. 여기 없다고 틀린 근거는 아니다.
DIRECT_IDENTITY = ("type_declaration", "construction", "registration", "field_declaration")


def identity_shape(quote):
    """인용 한 덩어리의 모양 이름. 판정이 아니라 기록용이다."""
    if not isinstance(quote, str) or not quote.strip():
        return "none"
    text = quote.strip()
    for name, rx in SHAPES:
        if rx.search(text):
            return name
    return "other"
