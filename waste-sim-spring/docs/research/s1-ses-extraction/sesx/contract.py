# -*- coding: utf-8 -*-
"""산출물 계약 v1과 그 검증기.

외부 의존성을 쓰지 않는다(jsonschema 없이 돈다). 검증기가 보는 것은 셋이다.

  구조  최상위 키·타입·열거값
  참조  ID가 실재하는 항목을 가리키는가
  상태  승인 항목이 거부·미승인 항목에 기대고 있지 않은가

의미가 맞는지는 여기서 판정하지 않는다. 그것은 전문가 검토의 몫이다.
"""
from __future__ import annotations

SCHEMA_VERSION = "ses-extraction/1.1"

ITEM_STATUS = ("proposed", "unresolved", "accepted", "rejected", "superseded")
ACTIVATION_STATE = ("known", "unconditional", "unknown")
COVERAGE = ("complete", "incomplete")
CHECK_RESULT = ("pass", "fail", "not_checked")
DECOMP_KIND = ("ASPECT", "SPEC", "MULTI")
# whole 은 전체(조립 자리를 가진 상위) 후보다. 루트라는 뜻이 아니다 — 루트는 PES 가 정한다.
# judgment 는 분기·집계·임계값 세 근거를 갖춘 판정이다.
ENTITY_KIND = ("stateful", "boundary", "set", "type", "whole", "judgment", "unknown")
ENTITY_SCOPE = ("simulation_target", "support_software", "unknown")
VALUE_STATUS = ("known", "unknown", "explicit_null")
REASON_CODES = (
    "no_evidence", "evidence_failed", "unknown_reference", "self_reference",
    "cycle", "multi_parent", "orphan", "duplicate_name", "multi_root",
    "kind_conflict", "unknown_activation", "schema_violation", "parse_error",
    "payload_unknown", "not_reviewed", "unproven_ownership", "not_an_entity",
    "shape_violation",
    # 분해 자식으로 온 이름이 이미 그 부모의 속성이다. 개체와 속성의 층위가 섞인 것이므로
    # 코드가 어느 쪽인지 정하지 않고 보류한다.
    "attribute_as_member",
    # 갈래는 근거와 함께 확인됐으나 그 축을 어느 개체 아래 둘지는 코드가 정하지 않는다.
    "parent_undetermined",
    # 같은 이름을 가진 개체가 둘 이상이다. 코드가 하나를 고르지 않는다.
    "ambiguous_reference",
    # 후보를 냈으나 판정을 받지 못했다. 묶음이 잘렸거나 답에 그 후보가 빠졌다.
    "unjudged",
)
#: 보류를 닫는 방법. **기록을 지우지 않는다** — 무엇을 왜 닫았는지가 검토 자료다.
#:   rejected   후보가 틀렸다고 판정했다
#:   superseded 다른 판정이 처리했다. 무엇이 처리했는지(`by`)를 적어야 한다
#:   deferred   아직 정하지 않았다고 명시했다. **승인을 계속 막는다**
RESOLUTIONS = ("rejected", "superseded", "deferred")

#: 승인을 막지 않는 해소. deferred 는 여기 없다 — 미루는 것은 닫는 것이 아니다.
CLOSING_RESOLUTIONS = ("rejected", "superseded")


def open_holds(doc):
    """아직 닫히지 않은 보류."""
    return [u for u in doc.get("unresolved") or []
            if (u.get("resolution") or {}).get("outcome") not in CLOSING_RESOLUTIONS]


DECISIONS = ("kept", "merged", "split", "held", "rejected", "superseded", "renamed")

# 관측값은 항목이되 **개체 소유자를 요구하지 않는다**. 결과 저장 구조라는 이유로 개체를
# 만들지 않기 위해서다. 대신 대상·범위·산출 근거를 스스로 갖는다.
OBSERVATION_SCOPE = ("whole", "per_category", "per_unit", "unknown")
ITEM_ARRAYS = ("entities", "attributes", "decompositions", "couplings", "activation",
               "observations")
TOP_KEYS = (
    "schema_version", "artifact_id", "parent_artifact_id",
    "extraction_run", "source_snapshot",
    "entities", "attributes", "decompositions", "couplings", "activation",
    "observations", "evidence", "unresolved", "provenance",
    "structure", "validation_results", "review_status",
)


def fail(code, detail, item_ids=None, layer="schema"):
    return {"code": code, "layer": layer, "detail": detail, "item_ids": list(item_ids or [])}


def new_artifact(artifact_id, extraction_run=None, source_snapshot=None,
                 parent_artifact_id=None):
    """빈 초안 하나. 모든 배열이 있고, 아무것도 검사되지 않은 상태다."""
    return {
        "schema_version": SCHEMA_VERSION,
        "artifact_id": artifact_id,
        "parent_artifact_id": parent_artifact_id,
        "extraction_run": extraction_run or {
            "run_id": None, "experiment_kind": "fresh_extraction", "strategy": None,
            "status": "not_started", "model": None, "model_revision": None,
            "settings": {}, "extractor_revision": None,
            "prompt_manifest_path": None, "stage_records_path": None,
            "request_response_path": None, "usage_path": None,
        },
        "source_snapshot": source_snapshot or {
            "snapshot_id": None, "status": "not_materialized",
            "selection_rule": {"rule_id": "pending", "approval_status": "pending"},
            "manifest_path": None, "files": [], "exclusion_report_path": None,
        },
        "entities": [], "attributes": [], "decompositions": [], "couplings": [],
        "activation": [], "observations": [], "evidence": [], "unresolved": [],
        "provenance": [],
        "structure": {"root_candidate_ids": [], "selected_root_id": None,
                      "status": "not_checked"},
        "validation_results": {"validator_version": None, "checks": [],
                               "approval_eligible": False},
        "review_status": {"status": "not_reviewed", "accepted_item_ids": [],
                          "decisions_path": None},
    }


# ---------------------------------------------------------------- 조회 도우미

def items_by_id(doc):
    """항목 ID -> (배열 이름, 항목). 근거·판정·보류는 따로 센다."""
    out = {}
    for arr in ITEM_ARRAYS:
        for it in doc.get(arr) or []:
            if isinstance(it, dict) and isinstance(it.get("id"), str):
                out[it["id"]] = (arr, it)
    return out


def evidence_by_id(doc):
    return {e["id"]: e for e in (doc.get("evidence") or [])
            if isinstance(e, dict) and isinstance(e.get("id"), str)}


def status_of(doc, item_id):
    hit = items_by_id(doc).get(item_id)
    return hit[1].get("status") if hit else None


# ---------------------------------------------------------------- 검증

def _check_ids_unique(doc, failures):
    seen = {}
    for arr in ITEM_ARRAYS + ("evidence", "unresolved", "provenance"):
        for it in doc.get(arr) or []:
            if not isinstance(it, dict):
                failures.append(fail("ITEM_NOT_OBJECT", f"{arr} 원소가 객체가 아니다"))
                continue
            i = it.get("id")
            if not isinstance(i, str) or not i:
                failures.append(fail("ID_MISSING", f"{arr} 항목에 id가 없다"))
                continue
            if i in seen:
                failures.append(fail("ID_DUPLICATE", f"{i} 가 {seen[i]}·{arr}에 중복", [i]))
            seen[i] = arr


def _check_enums(doc, failures):
    for arr in ITEM_ARRAYS:
        for it in doc.get(arr) or []:
            if not isinstance(it, dict):
                continue
            st = it.get("status")
            if st not in ITEM_STATUS:
                failures.append(fail("STATUS_INVALID", f"{arr} {it.get('id')} status={st!r}",
                                     [it.get("id")], "status"))
    for e in doc.get("entities") or []:
        if e.get("kind") not in ENTITY_KIND:
            failures.append(fail("ENTITY_KIND_INVALID", f"{e.get('id')} kind={e.get('kind')!r}",
                                 [e.get("id")]))
        if e.get("scope") not in ENTITY_SCOPE:
            failures.append(fail("ENTITY_SCOPE_INVALID", f"{e.get('id')} scope={e.get('scope')!r}",
                                 [e.get("id")]))
    for d in doc.get("decompositions") or []:
        if d.get("kind") not in DECOMP_KIND:
            failures.append(fail("DECOMP_KIND_INVALID", f"{d.get('id')} kind={d.get('kind')!r}",
                                 [d.get("id")]))
    for a in doc.get("activation") or []:
        if a.get("state") not in ACTIVATION_STATE:
            failures.append(fail("ACTIVATION_STATE_INVALID",
                                 f"{a.get('id')} state={a.get('state')!r}", [a.get("id")]))
        if a.get("coverage") not in COVERAGE:
            failures.append(fail("ACTIVATION_COVERAGE_INVALID",
                                 f"{a.get('id')} coverage={a.get('coverage')!r}", [a.get("id")]))
    for o in doc.get("observations") or []:
        if o.get("scope") not in OBSERVATION_SCOPE:
            failures.append(fail("OBSERVATION_SCOPE_INVALID",
                                 f"{o.get('id')} scope={o.get('scope')!r}", [o.get("id")]))
    for u in doc.get("unresolved") or []:
        if u.get("reason_code") not in REASON_CODES:
            failures.append(fail("REASON_CODE_INVALID",
                                 f"{u.get('id')} reason_code={u.get('reason_code')!r}",
                                 [u.get("id")]))
    for p in doc.get("provenance") or []:
        if p.get("decision") not in DECISIONS:
            failures.append(fail("DECISION_INVALID",
                                 f"{p.get('id')} decision={p.get('decision')!r}", [p.get("id")]))


def _check_refs(doc, failures):
    ids = items_by_id(doc)
    ev = evidence_by_id(doc)
    ent = {i for i, (arr, _) in ids.items() if arr == "entities"}
    att = {i for i, (arr, _) in ids.items() if arr == "attributes"}
    act = {i for i, (arr, _) in ids.items() if arr == "activation"}

    def need(ref, pool, code, owner, what):
        if ref is None:
            return
        if ref not in pool:
            failures.append(fail(code, f"{owner}의 {what} {ref!r} 가 없다", [owner], "reference"))

    for arr in ITEM_ARRAYS:
        for it in doc.get(arr) or []:
            if not isinstance(it, dict):
                continue
            for e_id in it.get("evidence_ids") or []:
                if e_id not in ev:
                    failures.append(fail("EVIDENCE_REF_BROKEN",
                                         f"{it.get('id')} -> {e_id}", [it.get("id")], "reference"))

    for a in doc.get("attributes") or []:
        need(a.get("entity_id"), ent, "ENTITY_REF_BROKEN", a.get("id"), "entity_id")
        for key in ("unit", "default", "range"):
            box = a.get(key)
            if isinstance(box, dict) and box.get("status") not in VALUE_STATUS:
                failures.append(fail("VALUE_STATUS_INVALID",
                                     f"{a.get('id')}.{key} status={box.get('status')!r}",
                                     [a.get("id")]))

    for d in doc.get("decompositions") or []:
        need(d.get("parent_entity_id"), ent, "ENTITY_REF_BROKEN", d.get("id"), "parent_entity_id")
        need(d.get("activation_id"), act, "ACTIVATION_REF_BROKEN", d.get("id"), "activation_id")
        for m in d.get("members") or []:
            need((m or {}).get("entity_id"), ent, "ENTITY_REF_BROKEN", d.get("id"), "member")
            if (m or {}).get("entity_id") == d.get("parent_entity_id"):
                failures.append(fail("SELF_REFERENCE", f"{d.get('id')} 부모가 자기 자식이다",
                                     [d.get("id")], "reference"))

    for c in doc.get("couplings") or []:
        for side in ("source", "target"):
            box = c.get(side) or {}
            need(box.get("entity_id"), ent, "ENTITY_REF_BROKEN", c.get("id"), f"{side}.entity_id")
            need(box.get("attribute_id"), att, "ATTRIBUTE_REF_BROKEN", c.get("id"),
                 f"{side}.attribute_id")
        se = (c.get("source") or {}).get("entity_id")
        te = (c.get("target") or {}).get("entity_id")
        if se and te and se == te:
            failures.append(fail("SELF_COUPLING",
                                 f"{c.get('id')} 출발과 도착이 같은 개체다",
                                 [c.get("id")], "reference"))
        need(c.get("activation_id"), act, "ACTIVATION_REF_BROKEN", c.get("id"), "activation_id")

    for o in doc.get("observations") or []:
        about = o.get("about") or {}
        if about.get("kind") == "entity":
            need(about.get("ref"), ent, "ENTITY_REF_BROKEN", o.get("id"), "about.ref")

    for u in doc.get("unresolved") or []:
        for i in u.get("item_ids") or []:
            if i not in ids:
                failures.append(fail("UNRESOLVED_REF_BROKEN", f"{u.get('id')} -> {i}",
                                     [u.get("id")], "reference"))

    for r in doc.get("review_status", {}).get("accepted_item_ids") or []:
        if r not in ids:
            failures.append(fail("ACCEPTED_REF_BROKEN", f"승인 목록의 {r} 가 없다", [r], "reference"))

    root = doc.get("structure", {}).get("selected_root_id")
    if root is not None and root not in ent:
        failures.append(fail("ROOT_REF_BROKEN", f"선택된 루트 {root} 가 없다", [root], "reference"))


def _check_activation_rules(doc, failures):
    """모르는 조건을 무조건 활성으로 바꾸지 않았는가."""
    for a in doc.get("activation") or []:
        state, clauses = a.get("state"), a.get("clauses") or []
        if state == "known" and not clauses:
            failures.append(fail("ACTIVATION_KNOWN_WITHOUT_CLAUSE",
                                 f"{a.get('id')} known 인데 절이 없다", [a.get("id")], "status"))
        if state == "unknown" and a.get("coverage") == "complete":
            failures.append(fail("ACTIVATION_UNKNOWN_COMPLETE",
                                 f"{a.get('id')} unknown 인데 coverage=complete", [a.get("id")],
                                 "status"))
        if state == "unconditional" and a.get("coverage") != "complete":
            failures.append(fail("ACTIVATION_UNCONDITIONAL_INCOMPLETE",
                                 f"{a.get('id')} unconditional 은 확인 범위가 complete 여야 한다",
                                 [a.get("id")], "status"))
        if len(clauses) > 1 and a.get("combination") is None and state == "known":
            failures.append(fail("ACTIVATION_COMBINATION_MISSING",
                                 f"{a.get('id')} 절이 여럿인데 결합(AND/OR)이 없다",
                                 [a.get("id")], "status"))


def _check_acceptance_consistency(doc, failures):
    """승인 항목이 미승인·거부 항목에 기대면 안 된다."""
    ids = items_by_id(doc)
    accepted = set(doc.get("review_status", {}).get("accepted_item_ids") or [])
    for i in accepted:
        hit = ids.get(i)
        if hit and hit[1].get("status") != "accepted":
            failures.append(fail("ACCEPTED_STATUS_MISMATCH",
                                 f"{i} 는 승인 목록에 있으나 status={hit[1].get('status')!r}",
                                 [i], "status"))
    for i, (arr, it) in ids.items():
        if it.get("status") != "accepted":
            continue
        if i not in accepted:
            failures.append(fail("ACCEPTED_NOT_IN_SCOPE",
                                 f"{i} status=accepted 인데 승인 목록에 없다", [i], "status"))
        for ref in _refs_of(arr, it):
            tgt = ids.get(ref)
            if tgt is None or tgt[1].get("status") != "accepted":
                got = tgt[1].get("status") if tgt else "없음"
                failures.append(fail("ACCEPTED_REFERENCES_NONACCEPTED",
                                     f"{i} 가 {ref}({got}) 에 기대고 있다", [i, ref], "status"))


def _refs_of(arr, it):
    out = []
    if arr == "attributes":
        out.append(it.get("entity_id"))
    elif arr == "decompositions":
        out.append(it.get("parent_entity_id"))
        out += [(m or {}).get("entity_id") for m in it.get("members") or []]
        out.append(it.get("activation_id"))
    elif arr == "couplings":
        for side in ("source", "target"):
            box = it.get(side) or {}
            out += [box.get("entity_id"), box.get("attribute_id")]
        out.append(it.get("activation_id"))
    return [r for r in out if isinstance(r, str)]


def validate_artifact(doc):
    """계약 위반 목록을 낸다. 빈 목록이면 구조·참조·상태가 성립한다."""
    failures = []
    if not isinstance(doc, dict):
        return [fail("NOT_OBJECT", "산출물이 객체가 아니다")]
    if doc.get("schema_version") != SCHEMA_VERSION:
        failures.append(fail("SCHEMA_VERSION_MISMATCH", repr(doc.get("schema_version"))))
    for k in TOP_KEYS:
        if k not in doc:
            failures.append(fail("TOP_KEY_MISSING", k))
    for arr in ITEM_ARRAYS + ("evidence", "unresolved", "provenance"):
        if arr in doc and not isinstance(doc[arr], list):
            failures.append(fail("ARRAY_EXPECTED", arr))
    if failures and any(f["code"] in ("NOT_OBJECT", "ARRAY_EXPECTED") for f in failures):
        return failures
    _check_ids_unique(doc, failures)
    _check_enums(doc, failures)
    _check_refs(doc, failures)
    _check_activation_rules(doc, failures)
    _check_acceptance_consistency(doc, failures)
    return failures
