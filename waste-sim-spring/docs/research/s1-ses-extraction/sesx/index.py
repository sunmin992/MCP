# -*- coding: utf-8 -*-
"""가벼운 기호 색인. 확인할 수 있는 것만 확인하고, 나머지는 not_checked 로 남긴다.

정규식 색인이다. 파서가 아니다. 그래서 하는 말은 하나뿐이다 —
"이 이름이 이 파일에 **선언으로 보이는 자리**를 갖는가."
선언을 못 찾으면 틀렸다고 말하지 않는다. 모른다고 말한다.
"""
from __future__ import annotations

import re

from .snapshot import split_lines

TYPE_DECL = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+(\w+)")
METHOD_DECL = re.compile(r"\b(\w+)\s*\([^;()]*\)\s*(?:throws\s+[\w., ]+)?\s*\{")
FIELD_DECL = re.compile(r"^\s*(?:public|private|protected|static|final|transient|volatile|\s)*"
                        r"[\w<>\[\],.?& ]+\s+(\w+)\s*(?:=[^=]|;)")
ENUM_MEMBER = re.compile(r"^\s*([A-Z][A-Z0-9_]{2,})\s*(?:\(|,|;|$)")
BRANCH = re.compile(r"\b(?:if|else\s+if|switch|case|while|for|\?|&&|\|\|)\b|\?")


def build(snapshot, path):
    """{name: {"declarations": [행], "occurrences": [행]}}"""
    lines = snapshot.lines(path)
    decl, occ = {}, {}
    for n, line in enumerate(lines, start=1):
        for rx in (TYPE_DECL, METHOD_DECL, FIELD_DECL, ENUM_MEMBER):
            for m in rx.finditer(line):
                decl.setdefault(m.group(1), []).append(n)
        for m in re.finditer(r"\w+", line):
            occ.setdefault(m.group(0), []).append(n)
    out = {}
    for name in set(decl) | set(occ):
        out[name] = {"declarations": sorted(set(decl.get(name, []))),
                     "occurrences": sorted(set(occ.get(name, [])))}
    return out


def leaf(symbol):
    """`Class.method` -> `method`. 마지막 마디만 기계적으로 확인할 수 있다."""
    if not isinstance(symbol, str) or not symbol:
        return None
    return re.split(r"[.#:]", symbol.strip())[-1] or None


def check_symbol(snapshot, path, symbol):
    """pass | fail | not_checked 와 사유."""
    name = leaf(symbol)
    if not name:
        return "not_checked", "기호가 없다"
    idx = build(snapshot, path)
    hit = idx.get(name)
    if not hit:
        return "fail", f"{name} 이 파일에 나타나지 않는다"
    if hit["declarations"]:
        return "pass", f"{name} 선언 행 {hit['declarations'][:3]}"
    return "not_checked", f"{name} 이 쓰이기는 하나 선언을 확인하지 못했다"


def is_inside_branch(snapshot, path, start_line, end_line, window=40):
    """근처에 분기가 있는가. **무조건 활성의 근거로 쓰지 않는다** — 호출자가 조건부일 수 있다.

    여기서 낼 수 있는 답은 '분기가 보인다'와 '이 창에서는 못 봤다' 둘뿐이다.
    """
    lines = split_lines(snapshot.text(path))
    lo = max(1, (start_line or 1) - window)
    hi = min(len(lines), (end_line or 1) + 2)
    seen = [n for n in range(lo, hi + 1) if BRANCH.search(lines[n - 1])]
    if seen:
        return True, f"분기로 보이는 행 {seen[:3]}"
    return False, f"{lo}-{hi} 창에서 분기를 보지 못했다 (호출자 조건은 확인하지 않았다)"
