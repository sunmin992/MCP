# -*- coding: utf-8 -*-
"""층별 검증. 결과는 pass | fail | not_checked 다.

중요한 구분 하나 — 여기서 나는 fail 은 **승인 산출물 생성**을 막는다.
검토자가 초안·후보·원문을 여는 것은 막지 않는다. 보고서는 실패 상태에서도 만들어진다.

근거가 있다는 사실은 의미가 맞다는 뜻이 아니다. 그래서 어떤 검사도 '의미 승인'을 대신하지
않는다. 의미는 review.py 의 사람 판정으로만 accepted 가 된다.
"""
from __future__ import annotations

from . import contract, evidence as ev_mod, structure as struct_mod, strict, schema

VALIDATOR_VERSION = "sesx-validate/2.0"

BLOCKING = (
    "EXTRACTION_NONEMPTY", "SOURCE_READY", "SCHEMA_VALID", "REF_INTEGRITY", "EVIDENCE_LOCATION",
    "EVIDENCE_REQUIRED", "DECOMP_ACYCLIC", "DECOMP_SINGLE_PARENT",
    "TREE_COMPLETE", "REACHABILITY", "ACTIVATION_RESOLVED", "PROVENANCE_COMPLETE", "REVIEW_SCOPE_CONSISTENT",
)


def _check(cid, layer, result, item_ids=None, details=""):
    return {"check_id": cid, "layer": layer, "result": result,
            "item_ids": list(item_ids or []), "details": details,
            "blocking": cid in BLOCKING}


def run(doc, snapshot=None, raw_ids=None, apply_findings=True):
    """검증을 돌리고 doc['validation_results'] 를 채운다. 항목은 지우지 않는다."""
    checks = []
    if strict.enabled(doc):
        try:
            errors = schema.errors(doc)
        except ImportError:
            errors = [{"path": "", "message": "jsonschema dependency is unavailable"}]
        if errors:
            doc["validation_results"] = {"validator_version": VALIDATOR_VERSION,
                "checks": [strict.check("JSON_SCHEMA", [doc.get("artifact_id", "invalid")], str(errors), "schema")],
                "blocking_failure_ids": ["JSON_SCHEMA"], "approval_eligible": False}
            return doc
        checks.append(strict.check("JSON_SCHEMA", [], "Draft 2020-12 schema passed", "schema"))
        if snapshot is not None:
            try:
                for path in snapshot.by_path:
                    snapshot.text(path)
            except (OSError, ValueError) as err:
                doc["validation_results"] = {"validator_version": VALIDATOR_VERSION,
                    "checks": [strict.check("SNAPSHOT_INTEGRITY", [doc["artifact_id"]], str(err))],
                    "blocking_failure_ids": ["SNAPSHOT_INTEGRITY"], "approval_eligible": False}
                return doc

    # 0. 아무것도 없는 산출물이 '통과'로 보이면 안 된다.
    #    jn-S2-1 에서 출력이 잘려 빈 초안이 나왔는데 검사 11개가 pass 로 찍혔다 —
    #    검사할 것이 없었기 때문이다. 빈 것은 통과가 아니라 실패다.
    n_items = sum(len(doc.get(k) or []) for k in contract.ITEM_ARRAYS)
    empty = n_items == 0
    checks.append(_check("EXTRACTION_NONEMPTY", "schema", "fail" if empty else "pass", [],
                         "추출된 항목이 없다" if empty else f"항목 {n_items}개"))

    # 0. 스냅샷 준비
    snap = doc.get("source_snapshot") or {}
    if snapshot is None or snap.get("status") != "materialized" or not snap.get("files"):
        checks.append(_check("SOURCE_READY", "evidence", "fail", [],
                             "스냅샷이 준비되지 않았다. 근거 검사를 할 수 없다"))
    else:
        checks.append(_check("SOURCE_READY", "evidence", "pass", [],
                             f"{len(snap['files'])}개 파일"))

    # 1. 근거 위치
    if snapshot is not None:
        ev_mod.verify_all(snapshot, doc)
        bad = [e["id"] for e in doc["evidence"]
               if (e.get("verification") or {}).get("verdict") == "fail"]
        n_ev = len(doc["evidence"])
        checks.append(_check("EVIDENCE_LOCATION", "evidence",
                             "fail" if bad else ("pass" if n_ev else "not_checked"), bad,
                             f"{len(bad)}건 실패 / {n_ev}건" if n_ev else "근거가 하나도 없다"))
        if apply_findings and bad:
            _downgrade_by_evidence(doc, set(bad))
    else:
        checks.append(_check("EVIDENCE_LOCATION", "evidence", "not_checked", [],
                             "스냅샷 없음"))

    # 1-2. 인용이 어떻게 맞았는가 — 정확 일치와 옮겨적기 보정을 갈라 센다.
    #      통과율 하나로 뭉치면 "글자 그대로 옮긴 근거"가 얼마나 되는지 알 수 없다.
    modes = {}
    for e in doc.get("evidence") or []:
        m = (e.get("verification") or {}).get("quote_match_mode")
        if m:
            modes[m] = modes.get(m, 0) + 1
    exact = modes.get("exact", 0)
    relaxed = modes.get("indent_normalized", 0) + modes.get("line_joined", 0)
    checks.append(_check("EVIDENCE_QUOTE_MODES", "evidence",
                         "pass" if (exact or relaxed) else "not_checked", [],
                         f"정확 {exact} · 들여쓰기보정 {modes.get('indent_normalized', 0)} "
                         f"· 줄이음보정 {modes.get('line_joined', 0)} · 불일치 {modes.get('none', 0)}"))

    # 2. 근거 요구 — proposed/accepted 항목은 통과한 근거를 하나 이상 가져야 한다
    need = []
    for arr in ("entities", "attributes", "decompositions", "couplings"):
        for it in doc.get(arr) or []:
            if it.get("status") not in ("proposed", "accepted"):
                continue
            ok = any((e.get("verification") or {}).get("verdict") == "pass"
                     for e in doc.get("evidence") or []
                     if e["id"] in (it.get("evidence_ids") or []))
            if not ok:
                need.append(it["id"])
    n_checkable = sum(len(doc.get(k) or []) for k in
                      ("entities", "attributes", "decompositions", "couplings"))
    checks.append(_check("EVIDENCE_REQUIRED", "evidence",
                         "fail" if need else ("pass" if n_checkable else "not_checked"), need,
                         f"통과한 근거가 없는 항목 {len(need)}개" if n_checkable else "항목이 없다"))

    # 1-3. 식별 근거가 어떤 모양의 코드를 가리키는가 — **기록만 한다.**
    #      선언·생성·등록을 인용했다는 사실이 식별의 입증은 아니고(실행 지원 클래스일 수
    #      있다), 배열 인덱스 대응처럼 그 모양에 맞지 않는 정당한 근거도 있다. 충분조건도
    #      필요조건도 아니므로 차단하지 않는다. 의미 판정은 사람이 한다.
    from .shapes import identity_shape, DIRECT_IDENTITY
    shapes, weak = {}, []
    for ent in doc.get("entities") or []:
        got = []
        for r in doc.get("evidence") or []:
            if not any((s or {}).get("item_id") == ent["id"] and s.get("field") == "identity"
                       for s in r.get("supports") or []):
                continue
            sh = identity_shape(r.get("quote"))
            got.append(sh)
            shapes[sh] = shapes.get(sh, 0) + 1
        if got and not (set(DIRECT_IDENTITY) & set(got)):
            weak.append(f"{ent['id']}({'/'.join(sorted(set(got)))})")
    if shapes:
        checks.append(_check("IDENTITY_EVIDENCE_SHAPE", "semantics",
                             "fail" if weak else "pass", weak,
                             f"모양 {shapes}" + (f" · 선언·생성·등록이 하나도 없는 개체: {weak}"
                                                if weak else "")))

    # 1-5. 소비 근거가 갱신·선언과 **다른 자리**인가 — 기록만 한다.
    #      "다시 읽혀 다음 판정에 쓰인다" 는 요구를 형식적으로만 채우면, 선언 줄이나 갱신
    #      줄을 그대로 다시 인용하게 된다(q3-obs1 에서 byOcc·byDay·wasteByMonth 가 그랬다).
    #      같은 줄이면 소비가 아니다. 다만 자기 자신을 읽어 갱신하는 정당한 경우도 있으므로
    #      차단하지 않는다.
    from .shapes import identity_shape as _shape
    same_line, decl_like = [], []
    for at in doc.get("attributes") or []:
        ids = set(at.get("evidence_ids") or [])
        upd = {(r.get("file_path"), r.get("start_line")) for r in doc.get("evidence") or []
               if r["id"] in ids and (r.get("supports") or [{}])[0].get("field") == "state_change"}
        for r in doc.get("evidence") or []:
            if r["id"] not in ids:
                continue
            if (r.get("supports") or [{}])[0].get("field") != "consumption":
                continue
            if (r.get("file_path"), r.get("start_line")) in upd:
                same_line.append(at["id"])
            elif _shape(r.get("quote")) in ("array_alloc", "field_declaration", "type_declaration"):
                decl_like.append(at["id"])
    bad_con = sorted(set(same_line) | set(decl_like))
    if any((r.get("supports") or [{}])[0].get("field") == "consumption"
           for r in doc.get("evidence") or []):
        checks.append(_check("CONSUMPTION_IS_DISTINCT", "semantics",
                             "fail" if bad_con else "pass", bad_con,
                             f"소비 근거가 갱신과 같은 줄 {len(set(same_line))}건 · 선언 모양 "
                             f"{len(set(decl_like))}건 — 결과로만 나가는 집계일 수 있다"
                             if bad_con else "소비 근거가 모두 갱신·선언과 다른 자리"))

    # 1-4. MULTI 의 자식이 여럿인가 — **기록만 한다.**
    #      MULTI 는 동종 개체의 복제이므로 자식은 한 종류다. 서로 다른 둘을 넣었다면 구성
    #      (ASPECT)일 가능성이 크다. 다만 같은 유형의 두 이름일 수도 있으므로 차단하지 않는다.
    multi_many = [d2["id"] for d2 in doc.get("decompositions") or []
                  if d2.get("kind") == "MULTI"
                  and len({(m or {}).get("entity_id") for m in d2.get("members") or []}) > 1]
    if any(d2.get("kind") == "MULTI" for d2 in doc.get("decompositions") or []):
        checks.append(_check("MULTI_SINGLE_MEMBER", "semantics",
                             "fail" if multi_many else "pass", multi_many,
                             f"자식이 여럿인 MULTI {len(multi_many)}건 — 복제가 아니라 구성일 수 있다"
                             if multi_many else "MULTI 자식이 모두 한 종류"))

    # 2-2. 같은 개체 안에서 이름이 겹치는 속성 — 한쪽이 다른 쪽에 설명을 붙인 것일 수 있다.
    #      jn-T2c-2 에서 b2 가 `fill[building][type] (쓰레기 수거장 적재량)` 로, c 가
    #      `fill[building][type]` 로 내어 같은 상태가 둘이 됐다. **자동으로 합치지 않는다** —
    #      괄호로만 다른 두 상태가 실제로 다를 수 있다. 검토자에게 보이기만 한다.
    from .assemble import norm as _norm
    overlaps = []
    by_ent = {}
    for at in doc.get("attributes") or []:
        by_ent.setdefault(at.get("entity_id"), []).append(at)
    for ent_id, ats in by_ent.items():
        for i in range(len(ats)):
            for j in range(i + 1, len(ats)):
                x, y = _norm(ats[i].get("name")), _norm(ats[j].get("name"))
                if x and y and x != y and (x in y or y in x):
                    overlaps.append(f"{ats[i]['id']}~{ats[j]['id']}")
    checks.append(_check("ATTR_NAME_OVERLAP", "semantics",
                         "fail" if overlaps else "pass", overlaps,
                         f"이름이 겹치는 속성 쌍 {len(overlaps)}건 — 같은 상태를 둘로 적었을 수 있다"
                         if overlaps else "겹치는 속성 이름 없음"))

    # 두 출처가 같은 자리에 다른 말을 하는가.
    #
    # `axis.py` 는 부모를 고르지 않고 `parent_undetermined` 로 보류한다 — 축이 어느 개체의
    # 성질인가가 의미 판단이기 때문이다(정답지의 CP-4 가 그 판정이다). 그런데 갈래가 개체로
    # 서고 나면 `d` 단계가 거기에 부모를 붙여 버린다. 한 아티팩트에 "부모를 모른다"와
    # "부모는 이것이다"가 함께 들어앉는데, 분해와 보류는 서로 다른 배열이라 구조 검사에는
    # 걸리지 않는다. **조용히 공존한다.** 검토자가 우연히 발견해야 하는 상태를 없앤다.
    #
    # 어느 쪽이 맞는지는 판정하지 않는다. 둘이 다르다는 사실만 보고한다.
    conflicts = []
    parented = {}
    for d in doc.get("decompositions") or []:
        if d.get("status") == "rejected":
            continue
        for m in d.get("members") or []:
            parented.setdefault((m or {}).get("entity_id"), []).append(d)
    for u in contract.open_holds(doc):
        if u.get("reason_code") != "parent_undetermined":
            continue
        for item in u.get("item_ids") or []:
            for d in parented.get(item, []):
                label = (u.get("origin_raw") or {}).get("axis_label") or u.get("id")
                conflicts.append(f"{u['id']}({label}) vs {d['id']}"
                                 f"(부모 {d.get('parent_entity_id')}) 공통 {item}")
    conflicts = sorted(set(conflicts))
    checks.append(_check("PARENT_SOURCE_CONFLICT", "semantics",
                         "fail" if conflicts else "pass", conflicts,
                         f"같은 갈래를 두고 부모 미확정 보류와 확정 분해가 함께 있다 "
                         f"{len(conflicts)}건 — 어느 쪽이 맞는지는 검토가 정한다: "
                         + "; ".join(conflicts[:3])
                         if conflicts else "부모를 두고 어긋나는 출처 없음"))

    # 3. 구조
    struct_mod.apply(doc)
    a = doc["structure"]["analysis"]
    cyc = ["->".join(c) for c in a["decomposition_cycles"]]
    checks.append(_check("DECOMP_ACYCLIC", "structure", "fail" if cyc else "pass",
                         [], "; ".join(cyc) or "순환 없음"))
    checks.append(_check("DECOMP_SINGLE_PARENT", "structure",
                         "fail" if a["multi_parent_entity_ids"] else "pass",
                         a["multi_parent_entity_ids"],
                         "부모가 둘 이상인 개체" if a["multi_parent_entity_ids"] else "단일 부모"))
    roots = a["root_candidate_ids"]
    reviewer_root = (doc["structure"] or {}).get("reviewer_root")
    # 루트는 SES 에서 정하지 않는다. 요청한 시뮬레이터가 PES 로 정한다(sesx/pes.py).
    # 그래서 이 검사는 **막지 않고 보고한다**. 단일 루트·도달 가능성의 강제는 PES 층에 있다.
    if len(roots) == 1:
        checks.append(_check("ROOT_CANDIDATES", "structure", "pass", roots,
                             f"루트 후보 하나 {roots[0]} — 확정은 PES 요청이 한다"))
    elif reviewer_root and reviewer_root.get("entity_id") in roots and reviewer_root.get("reason"):
        checks.append(_check("ROOT_CANDIDATES", "structure", "pass", roots,
                             f"검토자가 근거와 함께 {reviewer_root['entity_id']} 를 골랐다: "
                             f"{reviewer_root['reason']}"))
    else:
        checks.append(_check("ROOT_CANDIDATES", "structure", "fail", roots,
                             f"루트 후보 {len(roots)}개 — 코드가 고르지 않는다. "
                             f"승인은 막지 않는다. 시뮬레이터를 만들려면 PES 요청이 필요하다"))
    # 루트가 추출기 자신이 만든 개체이면 ROOT_CANDIDATES 는 **만들어서 통과한 것**이다.
    # 기계는 그 개체가 정당한지 판정할 수 없다. 검토자에게 보이기만 한다(차단하지 않는다).
    made = [p.get("output_item_ids") for p in doc.get("provenance") or []
            if p.get("origin_stage") in ("r", "r2", "g") and p.get("decision") == "kept"]
    made_ids = {i for lst in made for i in (lst or [])}
    root_made = [r for r in ([a["selected_root_id"]] if a["selected_root_id"] else [])
                 if r in made_ids]
    checks.append(_check("ROOT_INTRODUCED_BY_EXTRACTOR", "structure",
                         "fail" if root_made else "pass", root_made,
                         "루트가 루트 단계에서 새로 만들어진 개체다 — 근거가 정당한지 사람이 봐야 한다"
                         if root_made else "루트가 추출기가 만든 개체는 아니다"))
    checks.append(_check("REACHABILITY", "structure",
                         "fail" if a["unreachable_entity_ids"] else
                         ("not_checked" if a["selected_root_id"] is None else "pass"),
                         a["unreachable_entity_ids"],
                         "루트에서 닿지 않는 개체" if a["unreachable_entity_ids"]
                         else "루트 미확정" if a["selected_root_id"] is None else "전부 도달"))
    checks.append(_check("COUPLING_FEEDBACK", "structure", "pass", [],
                         f"결합 되먹임 {len(a['coupling_cycles'])}건 — 오류가 아니다"))

    # 4. 계약(구조·참조·상태)
    failures = contract.validate_artifact(doc)
    ref = [f for f in failures if f["layer"] == "reference"]
    sch = [f for f in failures if f["layer"] == "schema"]
    sta = [f for f in failures if f["layer"] == "status"]
    checks.append(_check("SCHEMA_VALID", "schema", "fail" if sch else "pass",
                         [i for f in sch for i in f["item_ids"]],
                         "; ".join(f["code"] for f in sch[:5]) or "계약 통과"))
    checks.append(_check("REF_INTEGRITY", "schema", "fail" if ref else "pass",
                         [i for f in ref for i in f["item_ids"]],
                         "; ".join(f["detail"] for f in ref[:5]) or "참조 정상"))
    checks.append(_check("REVIEW_SCOPE_CONSISTENT", "status", "fail" if sta else "pass",
                         [i for f in sta for i in f["item_ids"]],
                         "; ".join(f["detail"] for f in sta[:5]) or "승인 범위 일관"))

    # 5. 활성 조건 — unknown 이 남아 있으면 승인 대상이 아니다
    unknown = [a2["id"] for a2 in doc.get("activation") or []
               if a2.get("state") == "unknown" and a2.get("status") != "superseded"]
    n_act = len(doc.get("activation") or [])
    checks.append(_check("ACTIVATION_RESOLVED", "activation",
                         "fail" if unknown else ("pass" if n_act else "not_checked"), unknown,
                         f"미확정 활성 조건 {len(unknown)}건 — 항상 활성으로 바꾸지 않는다"))

    # 6. 역추적
    if raw_ids is not None:
        from . import assemble as asm
        missing = asm.traceable(doc, raw_ids)
        checks.append(_check("PROVENANCE_COMPLETE", "provenance",
                             "fail" if missing else "pass", missing,
                             f"판정 기록이 없는 원시 후보 {len(missing)}개"))
    else:
        checks.append(_check("PROVENANCE_COMPLETE", "provenance", "not_checked", [],
                             "원시 후보 목록이 주어지지 않았다"))

    if (doc.get("extraction_run") or {}).get("strategy") == "T2-tree":
        tree = struct_mod.analyze(doc)
        complete = (len(tree["root_candidate_ids"]) == 1
                    and not tree["decomposition_cycles"]
                    and not tree["multi_parent_entity_ids"]
                    and not tree["unreachable_entity_ids"])
        checks.append(_check("TREE_COMPLETE", "structure", "pass" if complete else "fail",
                             tree["root_candidate_ids"],
                             "단일 루트·단일 부모·비순환·전체 도달 (의미 승인은 별도)"))

    if strict.enabled(doc):
        checks.extend(strict.run(doc, snapshot, raw_ids, apply_findings))
    blocking_failures = [c for c in checks if c["blocking"] and c["result"] == "fail"]
    doc["validation_results"] = {
        "validator_version": VALIDATOR_VERSION,
        "checks": checks,
        "blocking_failure_ids": [c["check_id"] for c in blocking_failures],
        "approval_eligible": not blocking_failures,
        "note": "실패는 승인 산출물 생성을 막는다. 검토자의 열람을 막지 않는다.",
    }
    return doc


def _downgrade_by_evidence(doc, bad_evidence_ids):
    """근거가 틀린 항목은 확정하지 않는다. 지우지도 않는다 — 보류로 내린다."""
    for arr in ("entities", "attributes", "decompositions", "couplings", "activation"):
        for it in doc.get(arr) or []:
            if it.get("status") not in ("proposed", "accepted"):
                continue
            hit = sorted(set(it.get("evidence_ids") or []) & bad_evidence_ids)
            if not hit:
                continue
            it["status"] = "unresolved"
            doc["unresolved"].append({
                "id": f"U-evid-{it['id']}",
                "item_ids": [it["id"]], "origin_stage": "validate", "raw_id": None,
                "origin_raw": None, "reason_code": "evidence_failed",
                "explanation": f"근거 {hit} 가 스냅샷과 맞지 않는다",
                "resolution_needed": "근거를 고쳐 다시 내거나 항목을 거부한다.",
            })
