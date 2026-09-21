# -*- coding: utf-8 -*-
"""SES 요소에서 **서브태스크 템플릿의 초안**을 만든다. 모델이 참여하지 않는다.

LLM 이 곧바로 완성된 질문을 만들게 하지 않는다. 코드가 대응 규칙으로 초안을 만들고,
LLM 은 그 초안을 사용자 요청과 대조해 실제 서브태스크를 만든다.

**요소 하나당 무조건 작업 하나가 아니다.** 서버가 이미 가진 값, 고정된 값, 다른 값에서
계산할 수 있는 값까지 사용자 질문으로 바꾸면 사용자 부담이 근거 없이 는다. 그래서 작업
후보마다 **처분**을 붙이고, 처분은 근거에서 나온다. 가리지 못하면 `unknown` 이다.

**확인하지 못한 슬롯은 빈칸이다.** 단위도 범위도 코드에서 못 봤으면 비운다. 그 빈칸이
그대로 제공자가 할 일이 된다(`gaps.py`).

붙이는 규칙 하나만 조심하면 된다 — **이름으로 붙이지 않는다.** SES 쪽 이름은 한글이고
설정 필드는 영문이라 건널 다리가 없다. 건너는 것은 코드 자리다.

다리가 둘이다. 기호(`selection.symbol`·`evidence.symbol`)가 있으면 그것이 가장 좁고,
없으면 근거의 **파일과 행**이 겹치는지 본다. 둘째가 필요한 이유는 실측이다 — q3-obs1 의
근거 116건이 **전부 symbol 이 없었다.** 기호만 다리로 두면 실제 추출물에서는 하나도
붙지 않는다.
"""
from __future__ import annotations

import re

from .binding import KINDS as BINDING_KINDS

#: SES 요소 -> 작업 후보. ASPECT 도 작업을 낸다 — 고를 것은 없지만 **준비 여부**는 묻는다.
#: (Java 쪽 DecisionPointExtractor 는 ASPECT 에서 아무것도 내지 않는다. 그쪽은 "사용자가
#: 답할 것"만 세고, 여기는 "제공자가 확인할 것"까지 세기 때문이다.)
TASK_OF = {"SPEC": "choose_alternative", "MULTI": "decide_count", "ASPECT": "check_parts_ready"}
ATTRIBUTE_TASK = "provide_value"
COUPLING_TASK = "confirm_link"
ACTIVATION_TASK = "check_needed"

#: 처분 여섯. 기본이 `unknown` 이다 — 가리지 못한 것을 사용자 질문으로 밀어 넣지 않는다.
#:
#: `server_looks_up` 은 이 경계 안에서 나오지 않는다. 리소스에서 값을 읽는 근거를 아직
#: 모으지 않기 때문이다. 어휘에는 두되 코드가 스스로 붙이지 않는다 — 제공자가 지정한다.
DISPOSITIONS = ("user_decides", "server_looks_up", "server_computes",
                "default_applies", "not_needed_now", "unknown")

#: 작업 후보의 슬롯. 브리핑 2절의 표 그대로다.
SLOTS = ("decides", "options", "answer_shape", "check", "applies_when",
         "after", "default_rule", "delivers_to", "asks_as")

#: 이보다 넓은 근거는 어느 필드의 것도 아니다. 클래스 전체를 인용한 근거가 그렇다 —
#: 붙이면 같은 파일의 필드가 전부 그 요소의 것이 된다.
MAX_OVERLAP_SPAN = 60

#: 자리 겹침을 볼 때 쓰는 근거. 선언·변환·쓰기만 본다 — 읽기나 의존은 그 값을 **쓰는**
#: 자리이지 그 값이 **사는** 자리가 아니다.
OVERLAP_KINDS = ("declaration", "conversion", "write_site")

#: `trafficMode == APPLY` 같은 한 항. 이보다 복잡한 식은 풀지 않는다.
_EQUALITY = re.compile(r"^\s*(\w+)\s*(==|!=)\s*([A-Za-z_]\w*)\s*$")


def binding_kinds():
    """근거 묶음의 종류. 시험이 최소형 묶음을 만들 때 쓴다."""
    return BINDING_KINDS


def disposition(b):
    """근거 묶음 하나에서 나오는 처분과 그 사유.

    순서가 뜻을 정한다. 변환 자리가 있으면 그 값은 사용자에게서 온다. 없는데 읽기만
    있으면 다른 코드가 정한다. 둘 다 아니고 기본값이 있으면 기본값이 적용된다.
    그 밖에는 **모른다** — 지어내지 않는다.
    """
    if b is None:
        return "unknown", "연결된 설정 필드가 없다"
    ev = b["evidence"]
    if ev["conversion"]["found"]:
        return "user_decides", f"변환 자리가 있다 — 사용자 입력 {b['answer_field']!r} 에서 온다"
    if ev["read_site"]["found"] and not ev["write_site"]["found"]:
        return "server_computes", "읽는 자리만 있고 설정에 넣는 자리가 없다"
    if b["default"]["value"] is not None:
        return "default_applies", f"선언이 기본값 {b['default']['value']!r} 을 준다"
    return "unknown", "변환·읽기·기본값 중 어느 근거도 확인하지 못했다"


def _index(artifact):
    ent = {e["id"]: e for e in artifact.get("entities") or []}
    ev = {e["id"]: e for e in artifact.get("evidence") or []}
    act = {a["id"]: a for a in artifact.get("activation") or []}
    by_target = {}
    for a in act.values():
        target = (a.get("applies_to") or {}).get("target_id")
        if target:
            by_target[target] = a
    return ent, ev, act, by_target


def _name(entities, entity_id):
    return (entities.get(entity_id) or {}).get("name") or entity_id


def _symbols(element, evidence):
    """이 요소의 근거가 가리키는 코드 기호들. 있으면 이것이 가장 좁은 다리다."""
    out = []
    for eid in element.get("evidence_ids") or []:
        symbol = (evidence.get(eid) or {}).get("symbol")
        if symbol:
            out.append(symbol)
    return out


def _ranges(element, evidence):
    """이 요소의 근거가 선 파일과 행 범위. **기호가 없을 때의 다리다.**

    실제 산출물을 열어 보니 근거에 기호가 하나도 없었다(q3-obs1, 116건 전부). 기호만
    다리로 두면 실제 추출물에서는 아무것도 붙지 않는다. 파일과 행도 코드 자리다.

    넓은 범위는 버린다 — 클래스 전체를 인용한 근거는 어느 필드의 것도 아니다.
    """
    out = []
    for eid in element.get("evidence_ids") or []:
        ev = evidence.get(eid) or {}
        path, lo = ev.get("file_path"), ev.get("start_line")
        hi = ev.get("end_line") or lo
        if not path or lo is None:
            continue
        if (hi - lo) >= MAX_OVERLAP_SPAN:
            continue
        out.append((path, lo, hi, hi - lo))
    return sorted(out, key=lambda r: r[3])


def _accessors(field):
    head = field[0].upper() + field[1:]
    return {field, "get" + head, "set" + head, "is" + head, "resolve" + head}


def _match(symbols, ranges, bindings):
    """붙는 설정 필드 하나와 그 규칙. **이름은 어디에도 쓰이지 않는다.**

    기호가 먼저다 — 있으면 그것이 가장 좁은 근거다. 없으면 자리가 겹치는지 본다.
    """
    for b in bindings:
        names = _accessors(b["config_field"])
        link = b["ses_link"].get("entity_candidate_name")
        enum_type = b["evidence"]["enum_values"].get("enum_type")
        for symbol in symbols or ():
            if symbol in names or (link and symbol == link) or (
                    enum_type and symbol == enum_type):
                return b, "symbol_match"

    for path, lo, hi, _ in ranges or ():
        for b in bindings:
            for kind in OVERLAP_KINDS:
                for site in b["evidence"][kind]["sites"]:
                    if site.get("file_path") == path and lo <= site.get("start_line", -1) <= hi:
                        return b, "site_overlap"
    return None, None


def _holds(clause, selection):
    """이 절이 주어진 선택에서 참인가. 못 읽는 식은 **판단하지 않는다**."""
    m = _EQUALITY.match(clause.get("expression") or "")
    if not m or selection is None:
        return None
    name, op, value = m.groups()
    if name not in selection:
        return None
    same = str(selection[name]) == value
    return same if op == "==" else not same


def _activation_slot(act):
    if not act or act.get("state") != "known":
        return None
    parts = [c.get("expression") for c in act.get("clauses") or [] if c.get("expression")]
    if not parts:
        return None
    joiner = " " + (act.get("combination") or "and") + " "
    return joiner.join(parts)


def _slots(element_slots, b):
    """근거가 채우는 슬롯. 못 채운 것은 `None` 으로 남는다."""
    out = {k: None for k in SLOTS}
    out.update(element_slots)
    if b is None:
        return out
    out["delivers_to"] = b["config_field"] if b["evidence"]["write_site"]["found"] else None
    out["asks_as"] = b["answer_field"]
    out["answer_shape"] = b["declared_type"] or None
    if out["options"] is None and b["evidence"]["enum_values"]["found"]:
        out["options"] = list(b["evidence"]["enum_values"]["values"])
    rng = b["range"]
    if rng["source"] or b["evidence"]["validation"]["found"]:
        out["check"] = {"min": rng["min"], "max": rng["max"], "unit": b["unit"],
                        "evidence_ids": (rng["evidence_ids"]
                                         or b["evidence"]["validation"]["evidence_ids"])}
    if b["default"]["value"] is not None:
        out["default_rule"] = {"value": b["default"]["value"],
                               "evidence_ids": b["evidence"]["declaration"]["evidence_ids"]}
    if b["depends_on"]:
        out["after"] = [d["config_field"] for d in b["depends_on"]]
    return out


def _task(point_id, kind, source, element_slots, b, rule, act, selection, evidence_ids):
    disp, why = disposition(b)
    applies = _activation_slot(act)
    if applies:
        element_slots = {**element_slots, "applies_when": applies}
        for clause in act.get("clauses") or []:
            if _holds(clause, selection) is False:
                disp, why = "not_needed_now", f"현재 선택에서 {clause['expression']} 이 거짓이다"
                break
    return {"point_id": point_id, "kind": kind, "source": source,
            "disposition": disp, "disposition_why": why,
            "binding_id": b["binding_id"] if b else None, "match_rule": rule,
            "slots": _slots(element_slots, b),
            "evidence_ids": list(evidence_ids or [])}


def draft(artifact, bindings=(), selection=None):
    """SES 산출물에서 작업 후보 목록. 붙지 않은 설정 필드도 함께 낸다(규칙 3)."""
    bindings = list(bindings)
    entities, evidence, _, act_of = _index(artifact)
    tasks, used = [], set()

    def add(point_id, kind, source, slots, match, act, evidence_ids):
        b, rule = match
        if b is not None:
            used.add(b["binding_id"])
        tasks.append(_task(point_id, kind, source, slots, b, rule, act, selection,
                           evidence_ids))

    for d in artifact.get("decompositions") or []:
        parent = _name(entities, d["parent_entity_id"])
        members = [_name(entities, m["entity_id"]) for m in d.get("members") or []]
        act = act_of.get(d["id"])
        symbol = (d.get("selection") or {}).get("symbol")
        if d["kind"] == "SPEC":
            point, slots = (f"spec:{parent}:{symbol or d['id']}",
                            {"decides": {"entity": parent, "axis": symbol},
                             "options": members})
        elif d["kind"] == "MULTI":
            mult = d.get("multiplicity") or {}
            point, slots = (f"multi:{parent}",
                            {"decides": {"entity": parent, "of": members},
                             "check": ({"min": mult.get("minimum"), "max": mult.get("maximum"),
                                        "unit": None, "evidence_ids": mult.get("evidence_ids") or []}
                                       if mult.get("minimum") is not None
                                       or mult.get("maximum") is not None else None)})
        else:
            point, slots = (f"aspect:{parent}:{d['id']}",
                            {"decides": {"entity": parent, "parts": members}})
        symbols = ([symbol] if symbol else []) + _symbols(d, evidence)
        add(point, TASK_OF[d["kind"]], {"element": "decomposition", "id": d["id"]}, slots,
            _match(symbols, _ranges(d, evidence), bindings), act, d.get("evidence_ids"))

    for a in artifact.get("attributes") or []:
        owner = _name(entities, a["entity_id"])
        add(f"attr:{owner}:{a['name']}", ATTRIBUTE_TASK, {"element": "attribute", "id": a["id"]},
            {"decides": {"entity": owner, "attribute": a["name"]}},
            _match(_symbols(a, evidence), _ranges(a, evidence), bindings),
            act_of.get(a["id"]), a.get("evidence_ids"))

    for c in artifact.get("couplings") or []:
        src = _name(entities, (c.get("source") or {}).get("entity_id"))
        dst = _name(entities, (c.get("target") or {}).get("entity_id"))
        add(f"coupling:{src}->{dst}", COUPLING_TASK, {"element": "coupling", "id": c["id"]},
            {"decides": {"from": src, "to": dst}},
            _match(_symbols(c, evidence), _ranges(c, evidence), bindings),
            act_of.get(c["id"]), c.get("evidence_ids"))

    for a in artifact.get("activation") or []:
        target = (a.get("applies_to") or {}).get("target_id")
        add(f"active:{target or a['id']}", ACTIVATION_TASK,
            {"element": "activation", "id": a["id"]},
            {"decides": {"target": target, "state": a.get("state")}}, (None, None), a,
            a.get("evidence_ids"))

    return {"tasks": tasks,
            "unmapped_bindings": [b for b in bindings if b["binding_id"] not in used],
            "counts": _counts(tasks), "bridge": bridge_report(artifact, bindings)}


def bridge_report(artifact, bindings):
    """붙지 못한 까닭을 재는 수. **"0건"만 내면 무엇을 고쳐야 하는지 알 수 없다.**

    대조기가 틀린 것과 재료가 없는 것은 다른 문제다. 실측에서 갈린 자리가 여기였다 —
    q3-obs1·smoke-v8·jn-T2-1 에서 붙은 것이 0이었는데, 원인은 근거 116건이 전부
    symbol 이 없고 **설정 파일을 하나도 가리키지 않는다**는 것이었다. 추출이 엔진 루프의
    상태까지만 닿고 구성 표면에 닿지 않는다는 뜻이고, 그것은 대조기로 고칠 수 없다.
    """
    evidence = artifact.get("evidence") or []
    cited = {e.get("file_path") for e in evidence if e.get("file_path")}
    lived = set()
    for b in bindings:
        for kind in OVERLAP_KINDS:
            for site in b["evidence"][kind]["sites"]:
                if site.get("file_path"):
                    lived.add(site["file_path"])
    return {
        "evidence": len(evidence),
        "with_symbol": sum(1 for e in evidence if e.get("symbol")),
        "evidence_in_binding_files": sum(1 for e in evidence
                                         if e.get("file_path") in lived),
        "files_cited": sorted(cited),
        "binding_files_never_cited": sorted(lived - cited),
    }


def _counts(tasks):
    out = {d: 0 for d in DISPOSITIONS}
    for t in tasks:
        out[t["disposition"]] = out.get(t["disposition"], 0) + 1
    return out
