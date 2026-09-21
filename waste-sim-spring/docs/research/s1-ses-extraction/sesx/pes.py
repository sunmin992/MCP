# -*- coding: utf-8 -*-
"""PES — 요청한 시뮬레이터가 루트를 정한다.

SES 는 여러 시뮬레이터를 담는 족이다. 하나의 시뮬레이터는 가지치기의 결과다.
**코드도 모델도 루트를 고르지 않는다.** 사람이 "어느 시뮬레이터를 원하는가"를
요청으로 선언하고, 여기서는 그 요청이 SES 위에서 성립하는지만 결정적으로 검사한다.

지키는 것:

1. 요청이 불완전하면 **PES 를 만들지 않는다.** 빠진 선택을 채워 넣지 않는다.
2. 빠진 것마다 사유가 있다. `dropped` 는 조용한 삭제의 반대말이다.
3. MULTI 는 개수만 적는다. 인스턴스 개체를 지어내지 않는다.
4. 모르는 활성 조건은 `activation_unknown` 에 남는다. 무조건 활성으로 바꾸지 않는다.
5. 근거 없는 반복 횟수는 `requested` 라고 적어야 한다 — 코드에서 읽은 척할 수 없다.

이 모듈은 정답지·별칭 사전을 읽지 않는다. 오염 경계 바깥이다.
"""
from __future__ import annotations

import io
import json
import os

from .snapshot import digest as _digest

REQUEST_VERSION = "1"

#: 통과하지 못하면 PES 가 나오지 않는 검사들.
BLOCKING = (
    "PES_REQUEST_SHAPE", "PES_SES_IDENTITY", "PES_ROOT_EXISTS", "PES_ACYCLIC",
    "PES_SPEC_RESOLVED", "PES_MULTI_RESOLVED", "PES_SINGLE_ROOT", "PES_REACHABLE",
    "PES_DROP_RECORDED", "PES_ACTIVATION_NOT_ASSUMED",
)

#: 근거 없이 쓸 수 있는 유일한 반복 근거. 나머지는 코드에서 읽었다는 뜻이므로 근거가 필요하다.
BASIS_WITHOUT_EVIDENCE = "requested"
MULTIPLICITY_BASES = ("requested", "config_count", "array_length")

_STR = {"type": "string", "minLength": 1}
_IDS = {"type": "array", "items": _STR, "uniqueItems": True}

REQUEST_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "urn:sesx:pes-request:1",
    "type": "object",
    "properties": {
        "pes_request_version": {"const": REQUEST_VERSION},
        "request_id": _STR,
        "simulator": _STR,
        "requested_by": {"type": ["string", "null"]},
        "requested_at": {"type": ["string", "null"]},
        "ses_artifact_id": _STR,
        "ses_artifact_sha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "root": {
            "type": "object",
            "properties": {"entity_id": _STR, "reason": _STR,
                           "basis": {"enum": ["requested_simulator"]}},
            "required": ["entity_id", "reason", "basis"],
        },
        "selections": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"decomposition_id": _STR, "chosen_member_id": _STR,
                               "reason": _STR},
                "required": ["decomposition_id", "chosen_member_id", "reason"],
            },
        },
        "multiplicities": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"decomposition_id": _STR,
                               "count": {"type": "integer", "minimum": 1},
                               "basis": {"enum": list(MULTIPLICITY_BASES)},
                               "evidence_ids": _IDS},
                "required": ["decomposition_id", "count", "basis", "evidence_ids"],
            },
        },
        "aspects_included": {"const": "all"},
    },
    "required": ["pes_request_version", "request_id", "simulator", "ses_artifact_id",
                 "ses_artifact_sha256", "root", "selections", "multiplicities",
                 "aspects_included"],
}


def load_request(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def digest(pes):
    """검증 결과를 뺀 구조만의 해시. 같은 요청은 같은 PES 를 낸다."""
    return _digest({k: v for k, v in pes.items() if k != "validation_results"})


def _check(cid, result, item_ids, details):
    return {"check_id": cid, "layer": "pes", "result": result,
            "item_ids": sorted(set(item_ids)), "details": details,
            "blocking": cid in BLOCKING}


def _entities(doc):
    return {e["id"]: e for e in doc.get("entities") or []
            if isinstance(e, dict) and e.get("status") != "rejected"}


def _decomps(doc):
    return [d for d in doc.get("decompositions") or []
            if isinstance(d, dict) and d.get("status") != "rejected"]


def _members(d):
    return [m.get("entity_id") for m in d.get("members") or []
            if isinstance(m, dict) and m.get("entity_id")]


def _by_parent(decomps):
    out = {}
    for d in decomps:
        out.setdefault(d.get("parent_entity_id"), []).append(d)
    return out


def _cycles(decomps):
    """분해 그래프의 순환. 있으면 이름을 돌려준다 — 순회하다 멈추지 않는다."""
    edges = _by_parent(decomps)
    state, found = {}, []

    def walk(node, path):
        state[node] = 1
        for d in edges.get(node, []):
            for m in _members(d):
                if state.get(m) == 1:
                    found.append(" -> ".join(path + [node, m]))
                elif state.get(m) is None:
                    walk(m, path + [node])
        state[node] = 2

    for n in list(edges):
        if state.get(n) is None:
            walk(n, [])
    return found


# ------------------------------------------------------------------ 요청 검사

def _request_checks(doc, request):
    checks = []
    req = request if isinstance(request, dict) else {}

    shape = []
    if req.get("pes_request_version") != REQUEST_VERSION:
        shape.append(f"요청 판 {req.get('pes_request_version')!r} 을 읽지 못한다")
    if not str(req.get("simulator") or "").strip():
        shape.append("어느 시뮬레이터를 원하는지 적혀 있지 않다")
    if not str(req.get("request_id") or "").strip():
        shape.append("request_id 가 없다")
    if req.get("aspects_included") not in (None, "all"):
        shape.append("ASPECT 는 선택 대상이 아니다")
    checks.append(_check("PES_REQUEST_SHAPE", "fail" if shape else "pass", [],
                         "; ".join(shape) or "요청 모양 정상"))

    bad = []
    if req.get("ses_artifact_id") != doc.get("artifact_id"):
        bad.append(f"요청이 지목한 SES {req.get('ses_artifact_id')!r} 가 이 아티팩트가 아니다")
    if req.get("ses_artifact_sha256") != _digest(doc):
        bad.append("SES 해시가 다르다 — 그 사이 리비전이 바뀌었다")
    checks.append(_check("PES_SES_IDENTITY", "fail" if bad else "pass", [],
                         "; ".join(bad) or "요청이 이 SES 리비전을 가리킨다"))

    ents = _entities(doc)
    root = (req.get("root") or {}).get("entity_id")
    if not root:
        checks.append(_check("PES_ROOT_EXISTS", "fail", [],
                             "요청이 루트를 지목하지 않았다 — 코드가 고르지 않는다"))
    elif root not in ents:
        checks.append(_check("PES_ROOT_EXISTS", "fail", [root],
                             f"루트 {root} 가 SES 에 없다 — 요청은 있는 개체만 지목할 수 있다"))
    else:
        checks.append(_check("PES_ROOT_EXISTS", "pass", [root],
                             f"루트 {root} ({ents[root].get('name')})"))

    cyc = _cycles(_decomps(doc))
    checks.append(_check("PES_ACYCLIC", "fail" if cyc else "pass", [],
                         "; ".join(cyc[:3]) if cyc else "순환 없음"))
    return checks, root


def _selection_checks(doc, request, reached_decomps):
    """도달 범위 안의 SPEC·MULTI 가 요청으로 전부 풀렸는가."""
    req = request if isinstance(request, dict) else {}
    chosen, dup = {}, []
    for s in req.get("selections") or []:
        did = (s or {}).get("decomposition_id")
        if did in chosen:
            dup.append(did)
        chosen[did] = (s or {}).get("chosen_member_id")

    counts, bad_multi = {}, []
    for m in req.get("multiplicities") or []:
        did = (m or {}).get("decomposition_id")
        counts[did] = m
        basis = m.get("basis")
        if basis not in MULTIPLICITY_BASES:
            bad_multi.append(f"{did}: 알 수 없는 근거 {basis!r}")
        elif basis != BASIS_WITHOUT_EVIDENCE and not (m.get("evidence_ids") or []):
            bad_multi.append(f"{did}: {basis} 라고 적었으면 코드 근거가 있어야 한다")
        if not isinstance(m.get("count"), int) or isinstance(m.get("count"), bool) \
                or m.get("count") < 1:
            bad_multi.append(f"{did}: 반복 횟수 {m.get('count')!r} 가 1 이상이 아니다")

    spec_bad, multi_bad = list(dup and [f"{d}: 같은 축에 선택이 둘이다" for d in dup]), bad_multi
    spec_ids, multi_ids = [], []
    for d in reached_decomps:
        did = d.get("id")
        if d.get("kind") == "SPEC":
            spec_ids.append(did)
            if did not in chosen:
                spec_bad.append(f"{did}: 선택이 없다 — 코드가 고르지 않는다")
            elif chosen[did] not in _members(d):
                spec_bad.append(f"{did}: {chosen[did]!r} 는 이 축의 자식이 아니다")
        elif d.get("kind") == "MULTI":
            multi_ids.append(did)
            if did not in counts:
                multi_bad.append(f"{did}: 반복 횟수가 없다 — 코드가 정하지 않는다")

    return ([_check("PES_SPEC_RESOLVED", "fail" if spec_bad else "pass",
                    [s.split(":")[0] for s in spec_bad],
                    "; ".join(spec_bad) or f"SPEC 축 {len(spec_ids)}개 전부 선택됨"),
             _check("PES_MULTI_RESOLVED", "fail" if multi_bad else "pass",
                    [s.split(":")[0] for s in multi_bad],
                    "; ".join(multi_bad) or f"MULTI {len(multi_ids)}개 전부 개수 있음")],
            chosen, counts)


# ------------------------------------------------------------------ 가지치기

def _walk(doc, root, chosen):
    """루트에서 요청대로 내려간다. SPEC 은 고른 가지만, ASPECT 는 전부, MULTI 는 유형만."""
    by_parent = _by_parent(_decomps(doc))
    ents = _entities(doc)
    kept, order, out, seen_d = {root}, [root], [], set()
    stack = [root]
    while stack:
        node = stack.pop(0)
        for d in by_parent.get(node, []):
            did = d.get("id")
            if did in seen_d:
                continue
            seen_d.add(did)
            members = [m for m in _members(d) if m in ents]
            if d.get("kind") == "SPEC":
                pick = chosen.get(did)
                members = [pick] if pick in members else []
            rec = {"id": did, "kind": d.get("kind"), "parent_entity_id": node,
                   "members": members, "activation_id": d.get("activation_id")}
            out.append(rec)
            for m in members:
                if m not in kept:
                    kept.add(m)
                    order.append(m)
                    stack.append(m)
    return order, out


def _prune(doc, request, pes_id, chosen, counts, root):
    order, decomps = _walk(doc, root, chosen)
    kept = set(order)
    dropped = []

    for d in _decomps(doc):
        if d.get("kind") != "SPEC":
            continue
        pick = chosen.get(d.get("id"))
        for m in _members(d):
            if m != pick and m not in kept:
                dropped.append({"kind": "spec_branch", "decomposition_id": d.get("id"),
                                "entity_id": m,
                                "reason": f"선택이 {pick} 를 골랐다 — 이 가지는 이 시뮬레이터에 없다"})
    named = {r["entity_id"] for r in dropped}
    for e in doc.get("entities") or []:
        if e.get("id") not in kept and e.get("id") not in named:
            dropped.append({"kind": "entity", "entity_id": e.get("id"),
                            "reason": "요청한 루트에서 닿지 않는다"})

    for rec in decomps:
        if rec["kind"] == "MULTI":
            m = counts.get(rec["id"]) or {}
            rec["count"] = m.get("count")
            rec["count_basis"] = m.get("basis")
            rec["count_evidence_ids"] = list(m.get("evidence_ids") or [])

    couplings = []
    for c in doc.get("couplings") or []:
        if c.get("status") == "rejected":
            continue
        s = (c.get("source") or {}).get("entity_id")
        t = (c.get("target") or {}).get("entity_id")
        if s in kept and t in kept:
            couplings.append({"id": c.get("id"), "source": c.get("source"),
                              "target": c.get("target"), "payload": c.get("payload"),
                              "activation_id": c.get("activation_id")})
        else:
            outside = s if s not in kept else t
            dropped.append({"kind": "coupling", "coupling_id": c.get("id"),
                            "reason": f"끝점 {outside} 이 이 시뮬레이터에 없다"})

    attrs = [a["id"] for a in doc.get("attributes") or []
             if a.get("entity_id") in kept and a.get("status") != "rejected"]

    item_scope = kept | set(attrs) | {r["id"] for r in decomps} \
        | {c["id"] for c in couplings}
    unresolved = sorted(u.get("id") for u in doc.get("unresolved") or []
                        if not (u.get("item_ids") or [])
                        or any(i in item_scope for i in u.get("item_ids") or []))

    return {
        "pes_version": "1",
        "pes_id": pes_id,
        "status": "built",
        "request_id": (request or {}).get("request_id"),
        "simulator": (request or {}).get("simulator"),
        "request_sha256": _digest(request),
        "ses_artifact_id": doc.get("artifact_id"),
        "ses_artifact_sha256": _digest(doc),
        "root_entity_id": root,
        "entities": order,
        "attributes": attrs,
        "decompositions": decomps,
        "couplings": couplings,
        "dropped": dropped,
        "activation_unknown": _unknown_activation(doc, decomps, couplings),
        "unresolved_in_scope": unresolved,
    }


def _activation_by_id(doc):
    return {a["id"]: a for a in doc.get("activation") or [] if isinstance(a, dict)}


def _unknown_activation(doc, decomps, couplings):
    """활성 조건을 모르는 채로 들어간 항목. 무조건 활성으로 바꾸지 않았다는 증거다."""
    acts = _activation_by_id(doc)
    out = []
    for kind, items in (("decomposition", decomps), ("coupling", couplings)):
        for it in items:
            a = acts.get(it.get("activation_id"))
            if a is None:
                out.append({"item_kind": kind, "item_id": it["id"],
                            "activation_id": it.get("activation_id"), "state": "unknown",
                            "note": "활성 조건 항목이 없다"})
            elif a.get("state") == "unknown" or a.get("coverage") != "complete":
                out.append({"item_kind": kind, "item_id": it["id"],
                            "activation_id": a.get("id"), "state": a.get("state"),
                            "coverage": a.get("coverage")})
    return out


# ------------------------------------------------------------------ 산출물 검사

def verify(doc, pes):
    """만들어진 PES 가 스스로 말하는 것을 지키는가. 위조본을 잡는 검사다."""
    checks = []
    kept = list(pes.get("entities") or [])
    decomps = pes.get("decompositions") or []
    root = pes.get("root_entity_id")

    child = [m for d in decomps for m in d.get("members") or []]
    bad = []
    if root in child:
        bad.append("루트가 누군가의 자식이다")
    dupes = sorted({c for c in child if child.count(c) > 1})
    if dupes:
        bad.append(f"부모가 둘 이상인 개체 {dupes[:5]}")
    if set(kept) != ({root} if root else set()) | set(child):
        bad.append("개체 목록과 분해 자식 목록이 어긋난다")
    checks.append(_check("PES_SINGLE_ROOT", "fail" if bad else "pass", dupes,
                         "; ".join(bad) or f"단일 루트 · 개체 {len(kept)}개"))

    edges = {}
    for d in decomps:
        edges.setdefault(d.get("parent_entity_id"), []).extend(d.get("members") or [])
    seen, stack = set(), [root] if root else []
    while stack:
        n = stack.pop()
        if n in seen:
            continue
        seen.add(n)
        stack.extend(edges.get(n, []))
    unreachable = sorted(set(kept) - seen)
    checks.append(_check("PES_REACHABLE", "fail" if unreachable else "pass", unreachable,
                         f"닿지 않는 개체 {unreachable[:5]}" if unreachable else "전부 도달"))

    all_ids = {e["id"] for e in doc.get("entities") or []
               if e.get("status") != "rejected"}
    dropped_e = {d["entity_id"] for d in pes.get("dropped") or [] if d.get("entity_id")}
    missing = sorted(all_ids - set(kept) - dropped_e)
    noreason = [d.get("entity_id") or d.get("coupling_id")
                for d in pes.get("dropped") or [] if not str(d.get("reason") or "").strip()]
    gone_c = {c["id"] for c in doc.get("couplings") or []
              if c.get("status") != "rejected"} - {c["id"] for c in pes.get("couplings") or []}
    unrecorded_c = sorted(gone_c - {d.get("coupling_id") for d in pes.get("dropped") or []})
    drop_bad = missing + noreason + unrecorded_c
    checks.append(_check("PES_DROP_RECORDED", "fail" if drop_bad else "pass", drop_bad,
                         f"사유 없이 사라진 항목 {drop_bad[:5]}" if drop_bad
                         else f"빠진 {len(pes.get('dropped') or [])}건 전부 사유 있음"))

    expected = {(r["item_kind"], r["item_id"])
                for r in _unknown_activation(doc, decomps, pes.get("couplings") or [])}
    reported = {(r.get("item_kind"), r.get("item_id"))
                for r in pes.get("activation_unknown") or []}
    lost = sorted(f"{k}:{i}" for k, i in expected - reported)
    checks.append(_check("PES_ACTIVATION_NOT_ASSUMED", "fail" if lost else "pass", lost,
                         f"모르는 활성 조건인데 보고되지 않은 항목 {lost[:5]}" if lost
                         else f"활성 조건 미상 {len(expected)}건을 그대로 남겼다"))

    pending = list(pes.get("unresolved_in_scope") or [])
    checks.append(_check("PES_UNRESOLVED_IN_SCOPE", "pass", pending,
                         f"이 범위 안의 보류 {len(pending)}건 — 잘라내도 사라지지 않는다"))
    return checks


def _refused(doc, request, pes_id, checks):
    return {
        "pes_version": "1", "pes_id": pes_id, "status": "refused",
        "request_id": (request or {}).get("request_id"),
        "simulator": (request or {}).get("simulator"),
        "request_sha256": _digest(request),
        "ses_artifact_id": doc.get("artifact_id"),
        "ses_artifact_sha256": _digest(doc),
        "root_entity_id": (request or {}).get("root", {}).get("entity_id")
        if isinstance(request, dict) else None,
        "entities": [], "attributes": [], "decompositions": [], "couplings": [],
        "dropped": [], "activation_unknown": [], "unresolved_in_scope": [],
    }


def _results(checks):
    blocking = [c["check_id"] for c in checks if c["blocking"] and c["result"] == "fail"]
    return {"checks": checks, "blocking_failure_ids": blocking,
            "approval_eligible": not blocking}


def build(doc, request, pes_id):
    """요청을 SES 에 대고 검사하고, 통과하면 자른다. **불완전하면 만들지 않는다.**"""
    checks, root = _request_checks(doc, request)
    if any(c["blocking"] and c["result"] == "fail" for c in checks):
        for cid in ("PES_SPEC_RESOLVED", "PES_MULTI_RESOLVED", "PES_SINGLE_ROOT",
                    "PES_REACHABLE", "PES_DROP_RECORDED", "PES_ACTIVATION_NOT_ASSUMED",
                    "PES_UNRESOLVED_IN_SCOPE"):
            checks.append(_check(cid, "not_checked", [], "앞선 검사가 막았다"))
        out = _refused(doc, request, pes_id, checks)
        out["validation_results"] = _results(checks)
        return out

    # 선택 검사는 **도달 범위 안의** 축만 본다. 잘려 나갈 축의 선택을 요구하지 않는다.
    _, reached = _walk(doc, root, {s.get("decomposition_id"): s.get("chosen_member_id")
                                   for s in (request.get("selections") or [])})
    by_id = {d["id"]: d for d in _decomps(doc)}
    sel_checks, chosen, counts = _selection_checks(
        doc, request, [by_id[r["id"]] for r in reached if r["id"] in by_id])
    checks.extend(sel_checks)
    if any(c["blocking"] and c["result"] == "fail" for c in sel_checks):
        for cid in ("PES_SINGLE_ROOT", "PES_REACHABLE", "PES_DROP_RECORDED",
                    "PES_ACTIVATION_NOT_ASSUMED", "PES_UNRESOLVED_IN_SCOPE"):
            checks.append(_check(cid, "not_checked", [], "요청이 불완전하다"))
        out = _refused(doc, request, pes_id, checks)
        out["validation_results"] = _results(checks)
        return out

    out = _prune(doc, request, pes_id, chosen, counts, root)
    checks.extend(verify(doc, out))
    out["validation_results"] = _results(checks)
    if out["validation_results"]["blocking_failure_ids"]:
        out["status"] = "refused"
    return out


if __name__ == "__main__":
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "pes-request.schema.json")
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(REQUEST_SCHEMA, ensure_ascii=False, indent=2) + "\n")
