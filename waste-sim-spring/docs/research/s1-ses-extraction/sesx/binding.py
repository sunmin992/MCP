# -*- coding: utf-8 -*-
"""설정 필드마다 **실행설정 연결 근거**를 모은다. 모델이 참여하지 않는다.

`flow.py` 와 같은 규칙이다(규칙 9) — 짝짓기는 코드가 한다.

왜 설정 필드에서 출발하는가. SES 지점에서 출발하면 지점 이름이 한글이라 코드에 손잡이가
없고, 결국 이름 유사도로 돌아간다. 설정 필드는 유한하고 각각 앵커를 갖는다.

**이름으로는 못 잇는다는 증거가 시범 대상 안에 있다.**

    c.setNumTrucks(f.intOr("truckCount", c.getNumTrucks()));   // 빌더 :212

사용자가 입력하는 이름은 `truckCount`, 설정 필드 이름은 `numTrucks` 다. 둘을 잇는 것은
이 한 줄, **변환 자리** 하나뿐이다. 그래서 `answer_field` 는 변환 자리에서만 나오고,
변환 자리를 못 찾으면 설정 필드 이름을 베끼지 않고 `None` 으로 남긴다.

`signals.py` 와 같이 근거마다 `scanned`(조사 범위)를 적는다. **"못 찾음"과 "없음"을
가른다.** 못 본 것을 없다고 적으면 그 순간 근거가 아니라 주장이 된다.
"""
from __future__ import annotations

import hashlib
import re

from .assemble import anchor_key
from .index import TYPE_DECL, code_lines

#: 근거 일곱. 하나가 역할을 정하지 않는다 — 묶어서 보는 것이다.
KINDS = ("declaration", "conversion", "write_site", "validation",
         "enum_values", "read_site", "dependents")

#: 설정을 담은 유형. 여기 필드가 곧 연결 후보다.
CONFIG_TYPE = "SimulationConfig"

#: 근거 하나가 지고 가는 자리의 최대 개수. 묶음이 커지면 읽을 수 없게 된다.
MAX_SITES = 4

# 클래스 본문의 필드 선언. `private int numTrucks = 1;` 처럼 초기값이 있을 수 있다.
_FIELD = re.compile(
    r"^\s*private\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*"
    r"([\w.]+(?:\s*<[^>]*>)?(?:\s*\[\s*\])?)\s+(\w+)\s*(?:=\s*(.+?))?\s*;")

# 이름을 통째로 가리키는 자리. `getNumTrucks` 안의 `numTrucks` 를 잡지 않는다.
def _word(name):
    return re.compile(r"(?<![\w.])" + re.escape(name) + r"(?![\w])")


# 변환 자리. 설정에 넣는 값이 **다른 이름의 문자열 열쇠**로 들어온다.
_READER = re.compile(r"\.\s*(\w+)\s*\(\s*\"([A-Za-z_]\w*)\"")

# 쓰기 자리의 인자가 이름 하나뿐일 때 그 이름. `c.setRouteSequence(route);`
_ARG = re.compile(r"\(\s*([A-Za-z_]\w*)\s*\)\s*;")

#: 지역 변수를 거친 변환을 거슬러 올라가는 줄 수. 같은 메서드 안을 벗어나지 않을 만큼만.
CONVERSION_WINDOW = 14

#: 검증 자리 둘레에서 다른 설정을 함께 읽는지 보는 창. 같은 검사 한 덩어리를 덮을 만큼만.
DEPENDS_WINDOW = 10

# 값을 열거형으로 옮기는 자리. `TruckType.fromName(c.getTruckType())`
_TO_ENUM = re.compile(r"(?<![\w.])([A-Z]\w*)\s*\.\s*(?:fromName|valueOf|from|of)\s*\(")

# 검증기가 필드를 가리키는 문자열 열쇠. `new ValidationError(..., "truckCount", ...)`
_ERROR_KEY = re.compile(r"\"([A-Za-z_]\w*)\"")

# 검증하는 줄임을 보이는 표지. 이것이 없으면 그냥 이름이 적힌 줄이다 — 변환 자리의
# `f.intOr("dispatchIntervalMinutes", ...)` 가 검증 근거로 잡히던 자리다.
_VALIDATION_CTX = re.compile(r"\bValidationError\b|\b(?:errs|warns|errors|issues)\s*\.\s*add\b")

# 열거 상수 한 줄. 색인의 것보다 좁게 본다 — 여기서는 값 목록을 만든다.
_ENUM_MEMBER = re.compile(r"^\s*([A-Z][A-Z0-9_]{1,})\s*(?:\(|,|;|$)")

# 비교로 드러나는 범위. `c.getDispatchIntervalMinutes() < 0`
_COMPARE = re.compile(r"(<=|>=|<|>)\s*(-?\d+(?:\.\d+)?|[A-Z][A-Z0-9_]{2,})")

# 스냅샷 안에서 풀 수 있는 정수 상수. `private static final int MAX_MINUTE_OF_DAY = 1439;`
_CONST = re.compile(r"\bstatic\s+final\s+\w+\s+([A-Z][A-Z0-9_]{2,})\s*=\s*(-?\d+(?:\.\d+)?)\s*;")

#: 이 역할의 파일에서만 검증 근거를 찾는다. 이름이 우연히 나오는 자리를 검증이라 부르지 않는다.
VALIDATION_ROLES = ("config_validation",)


def binding_id(anchor):
    """앵커에서 나오는 안정된 ID. 같은 자리면 언제나 같은 ID 다."""
    key = anchor_key(anchor)
    return "BD-" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:10] if key else None


def _site(path, n, line, symbol=None):
    return {"file_path": path, "start_line": n, "end_line": n,
            "quote": line.rstrip("\n"), "symbol": symbol}


def _scope(snapshot, note=""):
    return (f"스냅샷 {len(snapshot.by_path)}개 파일 · 주석 제외 · 정규식 색인"
            + (" · " + note if note else ""))


def _signal(found, sites, scanned, detail, **extra):
    """근거 하나. `found` 가 False 는 **이 범위에서 못 봤다**는 뜻이지 없다는 뜻이 아니다."""
    out = {"found": bool(found), "sites": list(sites) if found else [],
           "evidence_ids": [], "scanned": scanned, "detail": detail}
    out.update(extra)
    return out


def _scan(snapshot, predicate, paths=None, limit=MAX_SITES):
    """조건에 맞는 자리 몇 곳. 대조는 주석 지운 사본으로, 인용은 원문으로 한다."""
    hits = []
    for path in (paths if paths is not None else snapshot.by_path):
        for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
            if predicate(path, code):
                hits.append(_site(path, n, line))
                if len(hits) >= limit:
                    return hits
    return hits


def _roles(decision_report):
    """파일 경로 -> 역할. 정책 기록이 없으면 빈 사전이고, 그때 검증 탐색 범위는 전체다."""
    out = {}
    for rec in (decision_report or {}).get("decisions") or []:
        if rec.get("decision") == "included":
            out[rec["path"]] = rec.get("role")
    return out


def config_fields(snapshot, owner_type=CONFIG_TYPE):
    """설정 유형의 필드 선언 하나마다 후보 하나."""
    out = []
    for path in snapshot.by_path:
        depth, owner = 0, None
        for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
            t = TYPE_DECL.search(code)
            if t and depth == 0:
                owner = t.group(1)
            m = _FIELD.match(code)
            if m and depth == 1 and owner == owner_type:
                anchor = {"file_path": path, "owner_type": owner, "symbol": m.group(2)}
                out.append({
                    "binding_id": binding_id(anchor), "config_field": m.group(2),
                    "owner_type": owner, "declared_type": re.sub(r"\s+", "", m.group(1)),
                    "anchor": anchor,
                    "declaration_site": _site(path, n, line, m.group(2)),
                    "default_literal": (m.group(3) or "").strip() or None})
            depth += code.count("{") - code.count("}")
    return out


def _setter(field):
    return "set" + field[0].upper() + field[1:]


def _getter_names(field):
    base = field[0].upper() + field[1:]
    return ("get" + base, "is" + base, "resolve" + base)


def _conversion(snapshot, field, write_sites):
    """쓰기 자리에 닿은 **문자열 열쇠**가 답변 필드다.

    설정 필드 이름을 답변 필드로 베끼지 않는다 — `numTrucks` 가 그렇게 하면 틀린다.

    두 모양을 본다. 한 줄로 끝나는 것과,

        c.setNumTrucks(f.intOr("truckCount", c.getNumTrucks()));

    지역 변수를 거치는 것.

        List<String> route = f.list("routeSequence");
        ...
        c.setRouteSequence(route);

    뒤쪽은 근거가 두 자리다 — 열쇠를 읽는 줄과 설정에 넣는 줄. 둘 다 남긴다.
    """
    setter = _setter(field)
    for site in write_sites:
        for method, key in _READER.findall(site["quote"]):
            if method != setter:
                return key, [site]
    for site in write_sites:
        arg = _ARG.search(site["quote"].replace(" ", ""))
        if not arg:
            continue
        found = _assigned_from_key(snapshot, site, arg.group(1), setter)
        if found:
            key, read_site = found
            return key, [read_site, site]
    return None, []


def _assigned_from_key(snapshot, write_site, local, setter):
    """설정에 넣은 지역 변수가 **문자열 열쇠에서 왔는가**. 같은 파일의 위쪽만 본다."""
    lines = code_lines(snapshot, write_site["file_path"])
    top = max(0, write_site["start_line"] - 1 - CONVERSION_WINDOW)
    assign = re.compile(r"(?<![\w.])" + re.escape(local) + r"\s*=")
    for n in range(write_site["start_line"] - 1, top, -1):
        line, code = lines[n - 1]
        if not assign.search(code):
            continue
        for method, key in _READER.findall(code):
            if method != setter:
                return key, _site(write_site["file_path"], n, line)
        return None
    return None


def _depends_on(snapshot, field, validation_sites, getters_by_field):
    """이 값을 검사하면서 **함께 읽는 다른 설정**. 그것이 먼저 결정할 항목이다.

    근거는 검증 자리 둘레의 좁은 창이다. 창 밖은 보지 않는다 — 넓히면 같은 파일에 있다는
    것만으로 의존이라 부르게 된다. 못 찾으면 빈 목록이지 추측이 아니다.
    """
    out = {}
    for site in validation_sites:
        lines = code_lines(snapshot, site["file_path"])
        lo = max(1, site["start_line"] - DEPENDS_WINDOW)
        hi = min(len(lines), site["start_line"] + DEPENDS_WINDOW)
        for n in range(lo, hi + 1):
            line, code = lines[n - 1]
            flat = code.replace(" ", "")
            for other, getters in getters_by_field.items():
                if other == field or not any((g + "(") in flat for g in getters):
                    continue
                rec = out.setdefault(other, {"config_field": other, "sites": [],
                                             "evidence_ids": []})
                if len(rec["sites"]) < 2:
                    rec["sites"].append(_site(site["file_path"], n, line, other))
    return list(out.values())


def _enum_values(snapshot, field, declared_type, search_sites):
    """허용값. 선언 유형이 열거형이거나, 변환 코드가 열거형을 가리킬 때만 나온다."""
    names = []
    if re.fullmatch(r"[A-Z]\w*", declared_type or ""):
        names.append(declared_type)
    for site in search_sites:
        for m in _TO_ENUM.finditer(site["quote"]):
            if _word(field).search(site["quote"]) or any(
                    g in site["quote"] for g in _getter_names(field)):
                names.append(m.group(1))
    for name in names:
        values, sites = [], []
        for path in snapshot.by_path:
            inside = False
            for n, (line, code) in enumerate(code_lines(snapshot, path), start=1):
                t = TYPE_DECL.search(code)
                if t:
                    inside = t.group(1) == name and " enum " in " " + code
                    if inside:
                        sites.append(_site(path, n, line, name))
                    continue
                if not inside:
                    continue
                if "(" in code and ")" in code and "=" in code:
                    pass
                m = _ENUM_MEMBER.match(code)
                if m:
                    values.append(m.group(1))
                    if len(sites) < MAX_SITES:
                        sites.append(_site(path, n, line, m.group(1)))
                if ";" in code and values:
                    inside = False
        if values:
            return name, values, sites
    return None, [], []


def _constants(snapshot):
    out = {}
    for path in snapshot.by_path:
        for _, code in code_lines(snapshot, path):
            for name, value in _CONST.findall(code):
                out.setdefault(name, value)
    return out


def _number(token, constants):
    raw = constants.get(token, token)
    try:
        return int(raw)
    except ValueError:
        try:
            return float(raw)
        except ValueError:
            return None


def _range(sites, constants):
    """검증기의 비교에서 나오는 범위. 못 읽으면 빈칸으로 둔다.

    **검증기는 틀린 값을 적는다.** `x < 0` 은 0 미만이 오류라는 뜻이므로 하한이 0 이고,
    `x > 1439` 는 1439 초과가 오류라는 뜻이므로 상한이 1439 다. 부등호를 그대로 범위로
    읽으면 뒤집힌다.
    """
    low, high = None, None
    for site in sites:
        for op, token in _COMPARE.findall(site["quote"]):
            value = _number(token, constants)
            if value is None:
                continue
            if op in ("<", "<="):
                low = value if low is None else min(low, value)
            else:
                high = value if high is None else max(high, value)
    return low, high


def collect(snapshot, owner_type=CONFIG_TYPE, decision_report=None):
    """설정 필드마다 근거 묶음 일곱. 순서는 선언 순서다."""
    roles = _roles(decision_report)
    validation_paths = [p for p, r in roles.items() if r in VALIDATION_ROLES] or None
    constants = _constants(snapshot)
    fields = config_fields(snapshot, owner_type)
    getters_by_field = {f["config_field"]: _getter_names(f["config_field"]) for f in fields}
    out = []

    for field in fields:
        name = field["config_field"]
        word, setter = _word(name), _setter(name)
        getters = _getter_names(name)
        decl = field.pop("declaration_site")
        default_literal = field.pop("default_literal")

        writes = _scan(snapshot, lambda p, c, s=setter: (s + "(") in c.replace(" ", ""))
        answer_field, conv = _conversion(snapshot, name, writes)
        reads = _scan(snapshot, lambda p, c, g=getters: any((x + "(") in c.replace(" ", "")
                                                            for x in g))

        # 검증 근거는 두 모양이다 — 오류에 필드 이름을 적는 줄과, 그 위에서 값을 비교하는
        # 줄. 범위는 비교하는 줄에만 있으므로 둘 다 모으지 않으면 범위가 늘 빈칸이 된다.
        keys = {name} | ({answer_field} if answer_field else set())
        vsites = _scan(snapshot, lambda p, c, k=keys, g=getters: (
            (bool(_VALIDATION_CTX.search(c)) and any(x in _ERROR_KEY.findall(c) for x in k))
            or (any((x + "(") in c.replace(" ", "") for x in g) and bool(_COMPARE.search(c)))),
            paths=validation_paths)
        enum_type, values, esites = _enum_values(snapshot, name, field["declared_type"],
                                                 reads + vsites)
        deps = _scan(snapshot, lambda p, c, w=word: bool(w.search(c)) and bool(
            re.search(r"(?<![\w.])" + re.escape(name) + r"\s*\.\s*\w+", c)))

        low, high = _range(vsites, constants)
        scope = _scope(snapshot)
        field["answer_field"] = answer_field
        field["evidence"] = {
            "declaration": _signal(True, [decl], scope, "설정 클래스의 필드 선언"),
            "conversion": _signal(bool(conv), conv, scope,
                                  "사용자 입력 이름을 설정값으로 옮기는 자리"),
            "write_site": _signal(bool(writes), writes, scope, f"{setter} 를 부르는 자리"),
            "validation": _signal(bool(vsites), vsites,
                                  _scope(snapshot, "역할 config_validation 인 파일만")
                                  if validation_paths else scope,
                                  "허용 범위·허용값을 검사하는 자리"),
            "enum_values": _signal(bool(values), esites, scope,
                                   f"{enum_type} 의 열거 상수" if enum_type else "열거형을 찾지 못했다",
                                   values=values, enum_type=enum_type),
            "read_site": _signal(bool(reads), reads, scope, "값을 읽는 자리"),
            "dependents": _signal(bool(deps), deps, scope, "이 값을 받아 쓰는 자리"),
        }
        field["unit"] = None
        # 출처는 **범위의** 출처다. 검증이 있어도 수치 경계를 읽지 못했으면 빈칸이다 —
        # 열거값만 검사하는 자리가 그렇다.
        field["range"] = {"min": low, "max": high, "evidence_ids": [],
                          "source": "validation" if (low is not None or high is not None)
                          else None}
        field["default"] = {"value": default_literal, "site": decl if default_literal else None}
        field["depends_on"] = _depends_on(snapshot, name, vsites, getters_by_field)
        field["ses_link"] = {"state": "unlinked", "point_id": None, "entity_candidate": None,
                             "why": None, "origin": "derived"}
        out.append(number_sites(field))
    return out


def number_sites(binding):
    """근거마다 ID 를 붙인다. 사람도 모델도 이 ID 로만 근거를 가리킨다."""
    i = 0
    for kind in KINDS:
        sig = binding["evidence"][kind]
        for site in sig["sites"]:
            i += 1
            site.setdefault("evidence_id", f"{binding['binding_id']}-E{i:02d}")
        sig["evidence_ids"] = [s["evidence_id"] for s in sig["sites"]]
    for dep in binding["depends_on"]:
        for site in dep["sites"]:
            i += 1
            site.setdefault("evidence_id", f"{binding['binding_id']}-D{i:02d}")
        dep["evidence_ids"] = [s["evidence_id"] for s in dep["sites"]]
    if binding["range"]["source"] == "validation":  # 범위를 실제로 읽은 때만
        binding["range"]["evidence_ids"] = list(binding["evidence"]["validation"]["evidence_ids"])
    return binding


# ---------------------------------------------------------------- SES 연결

#: 연결 규칙. **이름은 어디에도 쓰이지 않는다.**
#:
#: 세 번째 규칙("읽는 자리가 개체 후보의 소유 유형 안에 있다")은 넣지 않았다. 실제 코드에
#: 대보니 근거가 되는 자리가 없었고 — 검증기는 `TruckType` 을 읽지만 선언하지 않는다 —
#: 넣으면 파일이 같다는 것만으로 잇게 된다. 규칙을 하나 줄이는 대신 잘못 잇지 않는다.
LINK_RULES = ("type_match", "dependent_symbol")


def _by_symbol(cands):
    """기호 -> 후보. 같은 기호가 둘이면 잇지 않는다 — 어느 쪽인지 코드가 말하지 못한다."""
    out, dup = {}, set()
    for c in cands:
        sym = (c.get("anchor") or {}).get("symbol")
        if not sym:
            continue
        if sym in out:
            dup.add(sym)
        out[sym] = c
    return {k: v for k, v in out.items() if k not in dup}


def link(bindings, cands):
    """설정 필드를 개체 후보에 잇는다. **앵커 기호만 본다.**

    이름은 건너지 못하는 다리다 — SES 쪽 이름은 한글이고 설정 필드는 영문이다. 건너는
    것은 코드 자리다. `truckType` 이 `수거차량` 으로 가는 길은 이름이 아니라, 변환 코드가
    가리키는 `TruckType` 이 개체 후보의 앵커 기호와 같다는 사실이다.

    못 이으면 `unlinked` 로 남긴다. 그것이 실패가 아니라 **제공자가 채울 빈칸**이다.
    """
    index = _by_symbol(cands)
    for b in bindings:
        hit, rule, why = None, None, None

        for name in (b["evidence"]["enum_values"].get("enum_type"), b["declared_type"]):
            if name and name in index:
                hit, rule = index[name], "type_match"
                why = f"값을 옮기는 유형 {name} 이 개체 후보의 앵커 기호와 같다"
                break

        if hit is None:
            for site in b["evidence"]["dependents"]["sites"]:
                for sym, cand in index.items():
                    if _word(sym).search(site["quote"]):
                        hit, rule = cand, "dependent_symbol"
                        why = f"이 값을 받아 쓰는 자리가 {sym} 을 가리킨다"
                        break
                if hit:
                    break

        b["ses_link"] = {
            "state": "proposed" if hit else "unlinked",
            "point_id": None,
            "entity_candidate": hit["cand_id"] if hit else None,
            "entity_candidate_name": hit["name"] if hit else None,
            "rule": rule, "why": why, "origin": "derived"}
    return bindings
