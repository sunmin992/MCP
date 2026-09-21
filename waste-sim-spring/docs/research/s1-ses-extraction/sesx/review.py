# -*- coding: utf-8 -*-
"""전문가 검토. 결정적 검사가 통과했다고 accepted 가 되지 않는다 — 사람만 승인한다.

검토 결과는 **새 리비전**으로 저장한다. 이전 리비전은 그대로 남는다.
승인 저장은 두 조건을 모두 만족할 때만 된다.

  1) 차단 검사에 실패가 없다
  2) 승인 범위가 일관된다 — 승인 항목이 미승인·거부 항목에 기대지 않는다

수정이 있으면 재검증을 다시 돌린다. 검토 후 검증을 건너뛰지 않는다.
"""
from __future__ import annotations

import copy
import json
import time

from . import contract, validate as validate_mod

DECISIONS = ("accept", "reject", "hold", "set_root", "root_designation",
             "attach_axis", "resolve", "note")


def apply_decisions(doc, decisions, new_artifact_id, reviewer=None, decisions_path=None):
    """판정을 적용한 새 리비전을 만든다. 원본 doc 은 바뀌지 않는다."""
    out = copy.deepcopy(doc)
    out["artifact_id"] = new_artifact_id
    out["parent_artifact_id"] = doc.get("artifact_id")
    index = {}
    for arr in ("entities", "attributes", "decompositions", "couplings", "activation"):
        for it in out.get(arr) or []:
            index[it["id"]] = it

    accepted = set(out["review_status"].get("accepted_item_ids") or [])
    log = []
    for d in decisions:
        kind = d.get("decision")
        if kind not in DECISIONS:
            raise ValueError(f"알 수 없는 판정: {kind!r}")
        item_id = d.get("item_id")
        if kind == "root_designation":
            # 코드가 루트를 고르지 않는다(README 규칙 2). 사람이 근거와 함께 넣고,
            # 그렇게 들어온 항목에는 origin: "human" 이 박힌다 — 보고서와 채점이
            # 모델이 뽑은 것과 사람이 넣은 것을 가르기 위해서다.
            root = d.get("root") or {}
            name = (root.get("name") or "").strip()
            if not name or not (root.get("evidence") or []):
                raise ValueError("루트 지정에는 이름과 근거가 필요하다")
            members = list(d.get("members") or [])
            missing = [m for m in members if m not in index]
            if missing:
                raise ValueError(f"루트 구성원이 없다: {missing}")
            eid = f"E-human-{len(out['entities']) + 1}"
            evidence_ids = []
            for i, raw in enumerate(root.get("evidence") or []):
                ev_id = f"EV-human-{eid}-{i}"
                out["evidence"].append({
                    "id": ev_id,
                    "snapshot_id": (out.get("source_snapshot") or {}).get("snapshot_id"),
                    "file_path": raw.get("file_path"),
                    "file_sha256": raw.get("file_sha256"),
                    "start_line": raw.get("start_line"),
                    "end_line": raw.get("end_line", raw.get("start_line")),
                    "symbol": raw.get("symbol"),
                    "quote": raw.get("quote"),
                    "origin": "human",
                    "supports": [{"item_id": eid, "field": "existence"}],
                    "verification": {"file": "not_checked", "range": "not_checked",
                                     "quote": "not_checked", "symbol": "not_checked",
                                     "verdict": "not_checked", "details": {}}})
                evidence_ids.append(ev_id)
            out["entities"].append({
                "id": eid, "name": name, "kind": "boundary",
                "scope": "simulation_target", "why_entity": root.get("why"),
                "alternate_names": [], "origin": "human",
                "status": "proposed", "evidence_ids": evidence_ids})
            aid = f"A-human-{len(out['activation']) + 1}"
            out["activation"].append({
                "id": aid, "state": "unknown", "clauses": [], "combination": None,
                "coverage": "incomplete", "origin": "human", "status": "proposed",
                "evidence_ids": []})
            did = f"D-human-{len(out['decompositions']) + 1}"
            out["decompositions"].append({
                "id": did, "kind": "ASPECT", "label": d.get("label"),
                "parent_entity_id": eid,
                "members": [{"entity_id": m, "evidence_ids": []} for m in members],
                "selection": None,
                "multiplicity": {"count_expression": None, "minimum": None,
                                 "maximum": None, "evidence_ids": []},
                "activation_id": aid, "origin": "human",
                "status": "proposed", "evidence_ids": evidence_ids})
            out.setdefault("structure", {})["reviewer_root"] = {
                "entity_id": eid, "reason": d.get("reason"),
                "reviewer": reviewer, "at": time.time()}
            out["provenance"].append({
                "id": f"P-review-root-{len(out['provenance']) + 1}",
                "origin_stage": "review", "raw_id": None, "origin_raw": d,
                "decision": "kept",
                "reason": "사람이 루트를 지정했다 — 코드에 근거가 없다",
                "input_item_ids": members, "output_item_ids": [eid, did]})
            log.append({"decision": kind, "item_id": eid, "reason": d.get("reason")})
            continue
        if kind == "set_root":
            out.setdefault("structure", {})["reviewer_root"] = {
                "entity_id": item_id, "reason": d.get("reason"),
                "reviewer": reviewer, "at": time.time()}
            log.append({"decision": kind, "item_id": item_id, "reason": d.get("reason")})
            continue
        if kind == "attach_axis":
            # `parent_undetermined` 보류를 닫으면서 SPEC 분해를 만든다.
            #
            # 나누는 자리가 분명하다. **갈래와 그 근거는 코드가 찾았고**(axis.py 가 스냅샷의
            # 열거 상수 선언을 확인했다), **이 축이 어느 개체의 성질인가는 사람이 정한다**
            # (정답지의 CP-4 가 바로 그 판정이다). 그래서 분해의 origin 은 human 이고
            # 멤버 근거의 origin 은 derived_by_code 다. 섞인 채로 정직하게 적는다.
            uid = d.get("unresolved_id")
            rec = next((u for u in out.get("unresolved") or [] if u.get("id") == uid), None)
            if rec is None:
                raise ValueError(f"보류가 없다: {uid!r}")
            if rec.get("reason_code") != "parent_undetermined":
                raise ValueError(f"{uid} 는 부모 미확정 보류가 아니다: {rec.get('reason_code')}")
            if rec.get("resolution"):
                raise ValueError(f"{uid} 는 이미 해소됐다: {rec['resolution']['outcome']}")
            reason = (d.get("reason") or "").strip()
            if not reason:
                raise ValueError("축을 붙이려면 이유가 필요하다")
            parent = d.get("parent_item_id")
            pit = index.get(parent)
            if pit is None or parent not in {e["id"] for e in out["entities"]}:
                # PES 루트와 같은 규칙 — 요청은 **있는 개체만** 지목할 수 있다.
                raise ValueError(f"부모 {parent!r} 가 개체로 없다. 없는 부모를 만들지 않는다")
            if pit.get("status") == "rejected":
                raise ValueError(f"거부된 개체를 부모로 삼을 수 없다: {parent}")
            members = list(rec.get("item_ids") or [])
            if not members:
                raise ValueError(f"{uid} 에 갈래가 없다")
            if parent in members:
                raise ValueError("부모가 자기 갈래 중 하나다")
            gone = [m for m in members if m not in index]
            if gone:
                raise ValueError(f"갈래가 없다: {gone}")

            raw = rec.get("origin_raw") or {}
            by_name = raw.get("member_evidence") or {}
            name_of = {index[m]["name"]: m for m in members if m in index}
            did = f"D-human-{len(out['decompositions']) + 1}"
            member_rows = []
            for m in members:
                ev_ids = []
                for j, site in enumerate(by_name.get(index[m]["name"]) or []):
                    ev_id = f"EV-axis-{did}-{m}-{j}"
                    out["evidence"].append({
                        "id": ev_id,
                        "snapshot_id": (out.get("source_snapshot") or {}).get("snapshot_id"),
                        "file_path": site.get("file_path"),
                        "file_sha256": site.get("file_sha256"),
                        "start_line": site.get("start_line"),
                        "end_line": site.get("end_line", site.get("start_line")),
                        "symbol": site.get("symbol"), "quote": site.get("quote"),
                        # 자리는 코드가 찾았다. 붙인 것만 사람이다.
                        "origin": "derived_by_code",
                        "supports": [{"item_id": did, "field": "member"}],
                        "verification": {"file": "not_checked", "range": "not_checked",
                                         "quote": "not_checked", "symbol": "not_checked",
                                         "verdict": "not_checked", "details": {}}})
                    ev_ids.append(ev_id)
                member_rows.append({"entity_id": m, "evidence_ids": ev_ids})

            aid = f"A-human-{len(out['activation']) + 1}"
            out["activation"].append({
                "id": aid, "state": "unknown", "clauses": [], "combination": None,
                "coverage": "incomplete", "origin": "human", "status": "proposed",
                "evidence_ids": []})
            out["decompositions"].append({
                "id": did, "kind": "SPEC",
                "label": d.get("label") or raw.get("axis_label"),
                "parent_entity_id": parent, "members": member_rows,
                "selection": {"symbol": raw.get("axis_label"),
                              "description": reason, "evidence_ids": []},
                "multiplicity": {"count_expression": None, "minimum": None,
                                 "maximum": None, "evidence_ids": []},
                "activation_id": aid,
                "origin": "human", "members_origin": "derived_by_code",
                "status": "proposed", "evidence_ids": []})
            index[did] = out["decompositions"][-1]
            rec["resolution"] = {"outcome": "superseded", "reason": reason, "by": did,
                                 "reviewer": reviewer, "at": time.time()}
            out["provenance"].append({
                "id": f"P-attach-{uid}-{len(out['provenance']) + 1}",
                "origin_stage": "review", "raw_id": None, "origin_raw": d,
                "decision": "kept",
                "reason": f"사람이 {raw.get('axis_label')!r} 축을 {parent} 아래 붙였다: {reason}",
                "input_item_ids": members + [parent], "output_item_ids": [did]})
            log.append({"decision": kind, "item_id": did, "reason": reason})
            continue
        if kind == "resolve":
            # 보류를 닫는다. **기록은 지우지 않는다** — 조용한 삭제 금지는 검토에도 적용된다.
            # 무엇을 왜 닫았는지가 남아야 다음 리비전이 그 판단을 되짚을 수 있다.
            uid = d.get("unresolved_id")
            rec = next((u for u in out.get("unresolved") or [] if u.get("id") == uid), None)
            if rec is None:
                raise ValueError(f"보류가 없다: {uid!r}")
            if rec.get("resolution"):
                raise ValueError(f"{uid} 는 이미 해소됐다: {rec['resolution']['outcome']}")
            outcome = d.get("outcome")
            if outcome not in contract.RESOLUTIONS:
                raise ValueError(f"해소 결과는 {contract.RESOLUTIONS} 중 하나여야 한다")
            reason = (d.get("reason") or "").strip()
            if not reason:
                raise ValueError("보류를 닫으려면 이유가 필요하다")
            by = d.get("by")
            if outcome == "superseded" and not by:
                raise ValueError("superseded 는 무엇이 대신했는지(by)를 적어야 한다")
            rec["resolution"] = {"outcome": outcome, "reason": reason, "by": by,
                                 "reviewer": reviewer, "at": time.time()}
            out["provenance"].append({
                "id": f"P-resolve-{uid}-{len(out['provenance']) + 1}",
                "origin_stage": "review", "raw_id": None, "origin_raw": d,
                "decision": {"rejected": "rejected", "superseded": "superseded",
                             "deferred": "held"}[outcome],
                "reason": f"보류 {uid} 를 {outcome} 로 닫았다: {reason}",
                "input_item_ids": list(rec.get("item_ids") or []),
                "output_item_ids": []})
            log.append({"decision": kind, "item_id": uid, "reason": reason})
            continue
        if kind == "note":
            log.append({"decision": kind, "item_id": item_id, "reason": d.get("reason")})
            continue
        it = index.get(item_id)
        if it is None:
            raise ValueError(f"판정 대상이 없다: {item_id}")
        if kind == "accept":
            it["status"] = "accepted"
            accepted.add(item_id)
        elif kind == "reject":
            it["status"] = "rejected"
            accepted.discard(item_id)
        elif kind == "hold":
            it["status"] = "unresolved"
            accepted.discard(item_id)
        it["review"] = {"decision": kind, "reason": d.get("reason"), "reviewer": reviewer,
                        "at": time.time()}
        out["provenance"].append({
            "id": f"P-review-{item_id}-{len(out['provenance']) + 1}",
            "origin_stage": "review", "raw_id": None, "origin_raw": None,
            "decision": {"accept": "kept", "reject": "rejected", "hold": "held"}[kind],
            "reason": d.get("reason") or "검토 판정",
            "input_item_ids": [item_id], "output_item_ids": [item_id]})
        log.append({"decision": kind, "item_id": item_id, "reason": d.get("reason")})

    out["review_status"] = {
        "status": "reviewed",
        "accepted_item_ids": sorted(accepted),
        "decisions_path": decisions_path,
        "reviewer": reviewer,
        "at": time.time(),
        "log": log,
    }
    return out


def approvable(doc):
    """승인 산출물을 낼 수 있는가. 이유를 함께 준다."""
    reasons = []
    vr = doc.get("validation_results") or {}
    if not vr.get("validator_version"):
        reasons.append("검증을 돌리지 않았다")
    if not vr.get("approval_eligible"):
        reasons.append(f"차단 실패: {vr.get('blocking_failure_ids')}")
    if (doc.get("review_status") or {}).get("status") != "reviewed":
        reasons.append("전문가 검토가 없다")
    if not (doc.get("review_status") or {}).get("accepted_item_ids"):
        reasons.append("승인된 항목이 없다")
    return (not reasons), reasons


def finalize(doc, snapshot=None, raw_ids=None):
    """검토 뒤 재검증까지 하고, 승인 가능 여부를 판정한다."""
    validate_mod.run(doc, snapshot=snapshot, raw_ids=raw_ids, apply_findings=False)
    ok, reasons = approvable(doc)
    doc["approval"] = {"approved": ok, "reasons": reasons,
                       "scope": (doc.get("review_status") or {}).get("accepted_item_ids", []),
                       "caveats": caveats(doc),
                       "at": time.time()}
    return doc


#: 루트를 SES 에서 정하지 않게 된 뒤로, 승인된 SES 가 곧 시뮬레이터라는 오해가 가능해졌다.
#: 승인 범위에 무엇이 **아직 정해지지 않았는지**를 같이 적는다.
ROOT_UNDETERMINED = "루트 미확정 — 이 SES 로는 시뮬레이터를 만들 수 없다. PES 요청이 필요하다"


def caveats(doc):
    out = []
    st = doc.get("structure") or {}
    if not st.get("selected_root_id"):
        n = len(st.get("root_candidate_ids") or [])
        out.append(f"{ROOT_UNDETERMINED} (루트 후보 {n}개)")
    still_open = contract.open_holds(doc)
    if still_open:
        out.append(f"닫히지 않은 보류 {len(still_open)}건이 남아 있다")
    return out


def load_decisions(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)
