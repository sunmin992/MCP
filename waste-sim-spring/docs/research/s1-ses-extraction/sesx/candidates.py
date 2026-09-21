# -*- coding: utf-8 -*-
"""코드가 후보와 그 근거 묶음을 먼저 만든다. **모델이 참여하지 않는다.**

왜 이렇게 바꾸는가. 지금까지는 매 단계가 소스 전문(약 66,000토큰)을 지고 가서 긴 답을
한 번에 받았다. 그래서 두 가지가 같이 망가졌다 — 출력이 길어지면 잘려 실행이 멈추고,
모델이 인용과 행 번호를 직접 써서 틀린다. 실측하면 코드가 만든 인용은 100% 통과하고
모델이 쓴 인용은 73~94% 통과한다.

후보를 코드가 세면 모델에게 물을 것이 짧아진다. "이 후보는 대상 세계의 것인가, 무엇으로
볼 것인가" 한 줄이면 된다. 인용·행 번호·ID 는 코드가 채운다.

후보 ID 는 **코드 자리**에서 나온다. 이름이 아니다 — `byId` 가 세 클래스에 있고
`PAPER_BASELINE` 이 두 열거형에 있다.
"""
from __future__ import annotations

import hashlib
import re

from .assemble import anchor_key
from .collection import FIELD, DECL_KIND, CLASS_BODY_DEPTH
from .index import TYPE_DECL, code_lines, strip_comments

#: 후보 하나가 지고 가는 근거의 최대 개수. 묶음이 커지면 출력이 아니라 입력이 문제가 된다.
MAX_SITES = 3

DECL_WORD = re.compile(r"\b(class|interface|enum|record|@interface)\b")


def candidate_id(anchor):
    """앵커에서 나오는 안정된 ID. 같은 자리면 언제나 같은 ID 다."""
    key = anchor_key(anchor)
    if not key:
        return None
    return "CD-" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:10]


def _site(path, n, line, symbol):
    return {"file_path": path, "start_line": n, "end_line": n,
            "quote": line.rstrip("\n"), "symbol": symbol}


def enumerate_types(snapshot):
    """스냅샷에 선언된 유형 하나마다 후보 하나."""
    out = []
    for path in snapshot.by_path:
        # 대조는 주석을 지운 사본으로, 인용은 원문으로 한다.
        for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
            m = TYPE_DECL.search(code)
            if not m:
                continue
            word = DECL_WORD.search(code)
            anchor = {"file_path": path, "owner_type": None, "symbol": m.group(1)}
            out.append({
                "cand_id": candidate_id(anchor), "kind": "type_declaration",
                "name": m.group(1), "anchor": anchor,
                "declared_as": word.group(1) if word else "class",
                "sites": [_site(path, n, line, m.group(1))]})
    return out


def enumerate_fields(snapshot):
    """클래스 본문의 컬렉션 필드 하나마다 후보 하나. 원소 유형을 함께 적는다."""
    out = []
    for path in snapshot.by_path:
        depth, owner = 0, None
        for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
            t = TYPE_DECL.search(code)
            if t and depth == 0:
                owner = t.group(1)
            m = FIELD.match(code)
            if m and depth == CLASS_BODY_DEPTH:
                element = [a.strip() for a in m.group(2).split(",")][-1]
                anchor = {"file_path": path, "owner_type": owner, "symbol": m.group(3)}
                out.append({
                    "cand_id": candidate_id(anchor), "kind": "collection_field",
                    "name": m.group(3), "anchor": anchor,
                    "element_type": element, "shape": m.group(1),
                    "sites": [_site(path, n, line, m.group(3))]})
            depth += code.count("{") - code.count("}")
    return out


def usage_sites(snapshot, name, limit=MAX_SITES, skip=None):
    """그 이름이 쓰이는 자리 몇 곳. 선언만으로는 대상 세계의 것인지 보이지 않는다."""
    rx = re.compile(r"(?<![\w.])" + re.escape(name) + r"(?![\w])")
    out = []
    for path in snapshot.by_path:
        for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
            if (path, n) == skip or not rx.search(code):
                continue
            out.append(_site(path, n, line, name))
            if len(out) >= limit:
                return out
    return out


def build(snapshot):
    """후보 목록. 같은 자리는 한 번만 나온다."""
    out, seen = [], set()
    for cand in enumerate_types(snapshot) + enumerate_fields(snapshot):
        if cand["cand_id"] in seen:
            continue
        seen.add(cand["cand_id"])
        first = cand["sites"][0]
        cand["sites"] += usage_sites(snapshot, cand["name"],
                                     skip=(first["file_path"], first["start_line"]))
        out.append(cand)
    return out


def prompt_rows(cands):
    """모델에게 보일 줄. **인용은 보이되 행 번호는 감춘다** — 모델이 쓸 일이 없다."""
    rows = []
    for c in cands:
        head = f"{c['cand_id']}  {c['name']}"
        if c["kind"] == "collection_field":
            head += f"  ({c['shape']}<{c['element_type']}> · {c['anchor']['owner_type']} 의 필드)"
        else:
            head += f"  ({c['declared_as']} 선언)"
        rows.append(head)
        for s in c["sites"]:
            rows.append("     " + s["quote"].strip()[:110])
    return rows
