# -*- coding: utf-8 -*-
"""근거 검증. 스냅샷 사본에 대고 파일·범위·인용·기호를 각각 따로 판정한다.

인용은 **정확히** 대조한다. 개행만 정규화하고(CRLF·CR -> LF) 그 밖의 공백은 건드리지 않는다.
공백을 축약해 맞추면 문자열 리터럴 안의 공백 차이가 숨는다.

네 검사는 각각 pass|fail|not_checked 다. 한 인용이 맞았다고 그 항목의 이름·단위·조건이
모두 정당해지는 것은 아니다 — 그래서 근거는 `supports` 로 **어떤 항목의 어떤 필드**를
지지하는지 적는다.
"""
from __future__ import annotations

import re

from . import index as symindex
from .snapshot import normalize_newlines


def verify_record(snapshot, rec):
    """근거 하나를 검사해 verification 을 채운 사본을 돌려준다."""
    v = {"file": "not_checked", "range": "not_checked", "quote": "not_checked",
         "symbol": "not_checked", "details": {}}
    path = rec.get("file_path")

    if not isinstance(path, str) or not snapshot.has(path):
        v["file"] = "fail"
        v["details"]["file"] = f"스냅샷에 없는 파일: {path!r}"
        return _finish(rec, v)
    if rec.get("snapshot_id") not in (None, snapshot.snapshot_id):
        v["file"] = "fail"
        v["details"]["file"] = "다른 스냅샷을 가리킨다"
        return _finish(rec, v)
    want = rec.get("file_sha256")
    if want and want != snapshot.sha256(path):
        v["file"] = "fail"
        v["details"]["file"] = "파일 해시가 스냅샷과 다르다"
        return _finish(rec, v)
    v["file"] = "pass"

    piece = snapshot.slice(path, rec.get("start_line"), rec.get("end_line"))
    if piece is None:
        v["range"] = "fail"
        v["details"]["range"] = (f"{rec.get('start_line')}-{rec.get('end_line')} 는 "
                                 f"1..{len(snapshot.lines(path))} 밖이다")
        return _finish(rec, v)
    v["range"] = "pass"

    quote = rec.get("quote")
    if not isinstance(quote, str) or not quote.strip():
        v["quote"] = "not_checked"
        v["details"]["quote"] = "인용이 없다"
    elif normalize_newlines(quote) in normalize_newlines(piece):
        v["quote"] = "pass"
        v["quote_match_mode"] = "exact"
    elif _indent_only_match(quote, piece):
        # 줄머리 들여쓰기만 다르다. 줄 **안쪽** 공백은 그대로여야 하므로
        # 문자열 리터럴의 공백 차이는 여전히 걸린다. 지어낸 인용과 구별해서 적는다.
        # 줄이음보다 **먼저** 본다 — 줄이음 판정이 이 경우까지 삼키기 때문이다.
        v["quote"] = "fail"
        v["quote_match_mode"] = "indent_normalized"
        v["details"]["quote"] = "줄머리 들여쓰기만 다르다 (줄 안쪽 공백은 일치)"
    elif _line_joined_match(quote, piece):
        # 여러 줄을 한 줄로 이어 붙여 적었다. 줄바꿈과 그 뒤 들여쓰기만 공백 하나로 본다 —
        # 줄 **안쪽** 공백은 그대로이므로 문자열 리터럴의 차이는 여전히 걸린다.
        v["quote"] = "fail"
        v["quote_match_mode"] = "line_joined"
        v["details"]["quote"] = "여러 줄을 한 줄로 이어 적었다 (줄 안쪽 공백은 일치)"
    else:
        v["quote"] = "fail"
        v["quote_match_mode"] = "none"
        v["details"]["quote"] = "그 행 범위에 그 원문이 없다"
        # 통과시키지는 않는다. 다만 **지어낸 인용**과 **행 범위만 틀린 인용**은 검토자에게
        # 다른 일거리다. 파일 안 어디에 있는지 찾아 적어 준다.
        where = _find_in_file(snapshot, path, quote)
        if where == -1:
            v["details"]["quote_found_at"] = "같은 파일 안에 있다(행 미상) — 행 범위가 틀렸다"
        elif where:
            v["details"]["quote_found_at"] = f"같은 파일 {where}행에 있다 — 행 범위가 틀렸다"

    if rec.get("symbol"):
        st, why = symindex.check_symbol(snapshot, path, rec["symbol"])
        v["symbol"] = st
        v["details"]["symbol"] = why

    return _finish(rec, v)


def _indent_only_match(quote, piece):
    """줄머리 공백만 빼고 같은가.

    엄격 대조가 두 가지 실패를 한 덩어리로 묶는다 — **지어낸 인용**과 **다시 들여쓴 인용**이다.
    앞의 것은 근거가 아니고, 뒤의 것은 근거이되 옮겨 적는 방식이 다른 것이다. 둘을 갈라
    적어야 검토자가 무엇을 봐야 하는지 안다.

    줄 안쪽 공백은 건드리지 않는다 — 문자열 리터럴의 공백 차이는 계속 실패한다.
    """
    q = [ln.strip() for ln in normalize_newlines(quote).split("\n")]
    p = [ln.strip() for ln in normalize_newlines(piece).split("\n")]
    while q and not q[0]:
        q.pop(0)
    while q and not q[-1]:
        q.pop()
    if not q:
        return False
    for i in range(len(p) - len(q) + 1):
        if p[i:i + len(q)] == q:
            return True
    return False


_JOIN = re.compile(r"\n[ \t]*")


def _line_joined_match(quote, piece):
    """줄바꿈+뒤따르는 들여쓰기만 공백 하나로 눌러 비교한다.

    jn-T2c-1 에서 `public record CollectionSite(` 의 필드 선언 여러 줄을 한 줄로 이어 적은
    인용이 실패했다. 지어낸 것이 아니라 옮겨 적는 방식이 다른 것이다. 줄 안쪽 공백은
    건드리지 않으므로 리터럴의 공백 차이는 계속 실패한다.
    """
    q = _JOIN.sub(" ", normalize_newlines(quote)).strip()
    p = _JOIN.sub(" ", normalize_newlines(piece))
    return bool(q) and q in p


def _find_in_file(snapshot, path, quote):
    """인용이 파일 어딘가에 있는가. 있으면 **원문 기준** 시작 행. 판정은 바꾸지 않는다.

    줄이음 탐색은 파일 전체를 한 줄로 눌러 보므로 그 위치에서 행을 세면 언제나 1이 된다
    (lo-1 에서 "같은 파일 1행에 있다"가 그렇게 나왔다). 줄이음으로 찾았을 때는 **인용의
    첫 줄**을 원문 줄 목록에서 다시 찾아 행을 정한다. 그것도 못 찾으면 -1(행 미상)이다.
    """
    text = normalize_newlines(snapshot.text(path))
    q = normalize_newlines(quote).strip()
    if not q:
        return None
    if q in text:
        return text[:text.index(q)].count("\n") + 1
    qj = _JOIN.sub(" ", q)
    if qj in _JOIN.sub(" ", text):
        # 인용이 이미 한 줄로 이어져 있으면 "첫 줄"이 통째로 길다. 그러므로 원문 줄 중
        # **인용이 그 줄로 시작하는** 첫 줄을 찾는다.
        for n, ln in enumerate(text.split("\n"), 1):
            head = ln.strip()
            if len(head) > 3 and qj.startswith(head):
                return n
        return -1
    return None


def _finish(rec, v):
    out = dict(rec)
    results = [v["file"], v["range"], v["quote"], v["symbol"]]
    if "fail" in results:
        v["verdict"] = "fail"
    elif v["file"] == "pass" and v["range"] == "pass" and v["quote"] == "pass":
        v["verdict"] = "pass"
    else:
        v["verdict"] = "not_checked"
    out["verification"] = v
    return out


def verify_all(snapshot, doc):
    """산출물의 근거를 전부 검사해 제자리에 채운다. 실패해도 지우지 않는다."""
    doc["evidence"] = [verify_record(snapshot, r) for r in doc.get("evidence") or []]
    for r in doc["evidence"]:
        if r.get("file_sha256") is None and snapshot.has(r.get("file_path")):
            r["file_sha256"] = snapshot.sha256(r["file_path"])
            r["hash_origin"] = "snapshot_manifest"
    return doc


def verdicts(doc):
    return {r["id"]: (r.get("verification") or {}).get("verdict", "not_checked")
            for r in doc.get("evidence") or [] if isinstance(r, dict) and r.get("id")}


def supported_fields(doc, item_id):
    """이 항목의 어떤 필드가 통과한 근거로 지지되는가."""
    ok = set()
    for r in doc.get("evidence") or []:
        if (r.get("verification") or {}).get("verdict") != "pass":
            continue
        for s in r.get("supports") or []:
            if (s or {}).get("item_id") == item_id and s.get("field"):
                ok.add(s["field"])
    return ok
