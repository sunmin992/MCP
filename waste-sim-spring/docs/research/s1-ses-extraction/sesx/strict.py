"""Evidence-v1 checks. These establish traceability, never semantic truth."""
from __future__ import annotations

from . import contract, evidence, schema

PROFILE = "evidence-v1"
INACTIVE = ("rejected", "superseded")


def enabled(doc):
    return (doc.get("extraction_run") or {}).get("validation_profile") == PROFILE


def check(cid, bad, detail, layer="evidence"):
    return {"check_id": cid, "layer": layer, "result": "fail" if bad else "pass",
            "item_ids": sorted(set(bad)), "details": detail, "blocking": True}


def run(doc, snapshot, raw_ids, apply_findings):
    out, missing, reasons = [], {}, {}
    items = contract.items_by_id(doc)
    ev = contract.evidence_by_id(doc)

    def require(it, refs, field):
        # A quote about another item or another field is not evidence for this claim.
        ok = bool(refs) and all(
            r in ev and ev[r].get("verification", {}).get("verdict") == "pass"
            and any(s.get("item_id") == it["id"] and s.get("field") == field
                    for s in ev[r].get("supports", [])) for r in refs)
        if not ok:
            missing.setdefault(it["id"], []).append(field)

    for arr in contract.ITEM_ARRAYS:
        for it in doc[arr]:
            if it["status"] in INACTIVE:
                continue
            fields = evidence.supported_fields(doc, it["id"])
            if not fields:
                missing.setdefault(it["id"], []).append("any_evidence")
            if arr == "entities":
                if it["scope"] != "simulation_target" or it["kind"] == "unknown":
                    reasons[it["id"]] = "entity scope/kind unresolved"
                if it["kind"] == "stateful":
                    owned = [a for a in doc["attributes"] if a["entity_id"] == it["id"]
                             and a["status"] not in INACTIVE]
                    state = "state_change" in fields or any(
                        {"state_change", "ownership"} <= evidence.supported_fields(doc, a["id"])
                        for a in owned)
                    if not state or "identity" not in fields:
                        missing.setdefault(it["id"], []).append("identity_and_owned_state_change")
                elif it["kind"] in ("boundary", "set", "type"):
                    # Structural nodes are explicit, separately counted candidates.
                    # Their supporting quote still needs human semantic review.
                    if not it.get("why_entity"):
                        reasons[it["id"]] = "structural node needs a scope/composition rationale"
            elif arr == "attributes":
                for field in ("unit", "default", "range"):
                    box = it[field]
                    if box["status"] in ("known", "explicit_null"):
                        require(it, box["evidence_ids"], field)
                    elif box.get("value") is not None:
                        reasons[it["id"]] = f"unknown {field} contains a claimed value"
            elif arr == "decompositions":
                role = {"ASPECT": "composition", "SPEC": "subtype", "MULTI": "instance"}[it["kind"]]
                for member in it["members"]:
                    require(it, member["evidence_ids"], role)
                if it["kind"] == "MULTI":
                    if len(it["members"]) != 1 or not it.get("multiplicity", {}).get("count_expression"):
                        reasons[it["id"]] = "MULTI needs one type and a repetition expression"
                    require(it, it.get("multiplicity", {}).get("evidence_ids"), "multiplicity")
                if it["kind"] == "SPEC":
                    sel = it.get("selection") or {}
                    if not sel.get("symbol") and not sel.get("code_expression"):
                        reasons[it["id"]] = "SPEC needs a selection expression or symbol"
                    require(it, sel.get("evidence_ids"), "selection")
            elif arr == "couplings":
                for role in ("source", "target", "payload"):
                    require(it, it[role].get("evidence_ids"), role)
                if it["payload"]["kind"] == "unknown" or not it["payload"].get("code_expression"):
                    reasons[it["id"]] = "coupling payload unresolved"
                for side in ("source", "target"):
                    endpoint = it[side]
                    attr = items.get(endpoint.get("attribute_id"))
                    if attr and attr[1].get("entity_id") != endpoint["entity_id"]:
                        reasons[it["id"]] = "endpoint attribute belongs to another entity"
            elif arr == "activation":
                if it["state"] in ("known", "unconditional"):
                    require(it, it["evidence_ids"], "activation")
                for clause in it["clauses"]:
                    require(it, clause["evidence_ids"], "clause")
                    if not any(clause["expression"] in ev[r].get("quote", "")
                               for r in clause["evidence_ids"] if r in ev):
                        missing.setdefault(it["id"], []).append("clause_expression_in_quote")

    # Every support edge and nested evidence link must resolve, even those not used above.
    broken = []
    def walk(value, owner):
        if isinstance(value, dict):
            for k, v in value.items():
                if k == "evidence_ids" and isinstance(v, list):
                    broken.extend(owner for r in v if r not in ev)
                elif k not in ("origin_raw", "verification"):
                    walk(v, owner)
        elif isinstance(value, list):
            for v in value:
                walk(v, owner)
    for i, (_, it) in items.items():
        walk(it, i)
    for record in ev.values():
        broken.extend(record["id"] for s in record.get("supports", []) if s.get("item_id") not in items)
    out.append(check("FIELD_EVIDENCE", list(missing), str(missing)))
    out.append(check("CLAIM_CONSISTENCY", list(reasons), str(reasons), "semantics"))
    out.append(check("NESTED_REFERENCES", broken, "nested evidence/support references", "reference"))
    run = doc["extraction_run"]
    # 실행 종료와 범위 충족을 가른다. 하나가 죽어도 나머지가 도는 구조가 되었으므로
    # "끝났다"가 "다 뽑았다"를 뜻하지 않는다. 부분 산출물을 완성으로 읽히게 두지 않는다.
    expected = set(run.get("expected_stages") or [])
    # `missing` 을 다시 쓰지 않는다 — 그 이름은 위에서 FIELD_EVIDENCE 의 **항목 ID** 다.
    # 덮어쓰면 아래 상태 갱신 루프가 단계 이름을 항목 ID 로 알고 돌아 KeyError 를 낸다.
    missing_stages = (sorted(expected - set(run.get("stages_completed") or []))
                      if expected else [])
    out.append(check("EXTRACTION_SCOPE_MET",
                     [doc["artifact_id"]] if (missing_stages or not expected) else [],
                     f"계획한 단계가 모두 산출물을 냈는가 — 빠짐 {missing_stages}"
                     if missing_stages else "계획한 단계가 모두 산출물을 냈다", "execution"))
    stage_errors = [e for e in (run.get("errors") or []) if e.get("stage")]
    unclean = stage_errors or run.get("status") != "completed"
    out.append(check("EXTRACTION_RUN_CLEAN", [doc["artifact_id"]] if unclean else [],
                     f"실행 상태 {run.get('status')!r} · 단계 실패·차단 {len(stage_errors)}건 "
                     f"{[(e.get('stage'), e.get('kind')) for e in stage_errors[:4]]}"
                     if unclean else "단계 실패 없이 끝났다", "execution"))
    out.append(check("RAW_MANIFEST_REQUIRED", [doc["artifact_id"]] if raw_ids is None else [],
                     "raw candidate manifest must be available", "provenance"))
    source_bad = snapshot is None
    if snapshot is not None:
        snap = doc["source_snapshot"]
        source_bad |= snap.get("snapshot_id") != snapshot.snapshot_id
        source_bad |= snap.get("files") != snapshot.manifest["files"]
        source_bad |= snap.get("manifest_sha256") != snapshot.manifest.get("manifest_sha256")
        source_bad |= not bool(snapshot.manifest.get("manifest_sha256"))
    out.append(check("SOURCE_IDENTITY", [doc["artifact_id"]] if source_bad else [],
                     "artifact source identity must equal frozen snapshot"))

    for iid in sorted(set(missing) | set(reasons)):
        if apply_findings and items[iid][1]["status"] not in INACTIVE:
            items[iid][1]["status"] = "unresolved"
            uid = f"U-strict-{iid}"
            if not any(u["id"] == uid for u in doc["unresolved"]):
                doc["unresolved"].append({"id": uid, "item_ids": [iid], "origin_raw": None,
                    "reason_code": "no_evidence", "explanation": str(missing.get(iid) or reasons.get(iid)),
                    "resolution_needed": "Supply field-specific code evidence or reject in review."})
    pending = [i for i, (_, it) in items.items() if it["status"] == "unresolved"]
    out.append(check("UNRESOLVED_ITEMS", pending,
                     "unresolved candidates stay reviewable; full approval is blocked", "status"))
    return out
