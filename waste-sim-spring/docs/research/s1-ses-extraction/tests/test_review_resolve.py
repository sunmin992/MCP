# -*- coding: utf-8 -*-
"""보류를 닫는 경로.

검토가 `unresolved` 기록을 건드릴 방법이 없었다. 항목의 status 는 accept·reject 로
바꿀 수 있었지만, **item_ids 가 빈 보류**는 어느 판정으로도 닫히지 않았다. 후보가
항목이 되지 못한 채 보류된 경우가 그렇다 — q3-decl-2 의 보류 208건 중 56건, 그리고
`parent_undetermined` 10건 전부가 여기 해당한다.

닫아도 기록을 지우지 않는다. 조용한 삭제 금지는 검토에도 적용된다 — 무엇을 왜 닫았는지가
다음 리비전이 그 판단을 되짚을 자료다.
"""
from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import contract, review  # noqa: E402


def doc_with_holds():
    doc = contract.new_artifact("r1")
    doc["entities"].append({"id": "E1", "name": "수거지점", "kind": "stateful",
                            "scope": "simulation_target", "status": "proposed",
                            "evidence_ids": []})
    doc["unresolved"] = [
        {"id": "U1", "item_ids": [], "reason_code": "parent_undetermined",
         "explanation": "'TruckType' 축의 갈래 3개는 확인됐다"},
        {"id": "U2", "item_ids": ["E1"], "reason_code": "unknown_reference",
         "explanation": "끝점 미해결"},
    ]
    return doc


def apply(doc, *decisions):
    return review.apply_decisions(doc, list(decisions), "r1-rev1", reviewer="검토자")


class ClosingAHold(unittest.TestCase):
    def test_a_hold_can_be_rejected(self):
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "rejected", "reason": "축이 아니라 전송 구분이다"})
        rec = next(u for u in out["unresolved"] if u["id"] == "U1")
        self.assertEqual(rec["resolution"]["outcome"], "rejected")
        self.assertEqual(rec["resolution"]["reviewer"], "검토자")
        self.assertIn("전송 구분", rec["resolution"]["reason"])

    def test_the_record_is_not_deleted(self):
        """조용한 삭제 금지는 검토에도 적용된다."""
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "rejected", "reason": "축이 아니다"})
        self.assertEqual(len(out["unresolved"]), 2)
        self.assertEqual(out["unresolved"][0]["explanation"],
                         "'TruckType' 축의 갈래 3개는 확인됐다")

    def test_the_original_revision_is_untouched(self):
        doc = doc_with_holds()
        apply(doc, {"decision": "resolve", "unresolved_id": "U1",
                    "outcome": "rejected", "reason": "축이 아니다"})
        self.assertNotIn("resolution", doc["unresolved"][0])

    def test_closing_leaves_a_provenance_record(self):
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U2",
                     "outcome": "rejected", "reason": "끝점을 잘못 지목했다"})
        p = [x for x in out["provenance"] if x["origin_stage"] == "review"]
        self.assertEqual(len(p), 1)
        self.assertEqual(p[0]["decision"], "rejected")
        self.assertEqual(p[0]["input_item_ids"], ["E1"])
        self.assertIn("U2", p[0]["reason"])


class WhatIsRefused(unittest.TestCase):
    def refuses(self, decision, message):
        with self.assertRaises(ValueError) as cm:
            apply(doc_with_holds(), decision)
        self.assertIn(message, str(cm.exception))

    def test_an_unknown_hold_is_refused(self):
        self.refuses({"decision": "resolve", "unresolved_id": "U99",
                      "outcome": "rejected", "reason": "x"}, "보류가 없다")

    def test_an_unknown_outcome_is_refused(self):
        self.refuses({"decision": "resolve", "unresolved_id": "U1",
                      "outcome": "지워버림", "reason": "x"}, "해소 결과는")

    def test_closing_without_a_reason_is_refused(self):
        self.refuses({"decision": "resolve", "unresolved_id": "U1",
                      "outcome": "rejected", "reason": "   "}, "이유가 필요하다")

    def test_superseded_must_name_what_replaced_it(self):
        self.refuses({"decision": "resolve", "unresolved_id": "U1",
                      "outcome": "superseded", "reason": "다른 판정이 처리했다"},
                     "무엇이 대신했는지")

    def test_a_hold_cannot_be_closed_twice(self):
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "rejected", "reason": "축이 아니다"})
        with self.assertRaises(ValueError) as cm:
            review.apply_decisions(out, [{"decision": "resolve", "unresolved_id": "U1",
                                          "outcome": "deferred", "reason": "다시"}],
                                   "r1-rev2")
        self.assertIn("이미 해소됐다", str(cm.exception))


class WhichHoldsStillBlock(unittest.TestCase):
    def test_an_open_hold_counts(self):
        self.assertEqual([u["id"] for u in contract.open_holds(doc_with_holds())],
                         ["U1", "U2"])

    def test_a_rejected_hold_no_longer_counts(self):
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "rejected", "reason": "축이 아니다"})
        self.assertEqual([u["id"] for u in contract.open_holds(out)], ["U2"])

    def test_a_deferred_hold_still_counts(self):
        """미루는 것은 닫는 것이 아니다."""
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "deferred", "reason": "CP-4 를 더 봐야 한다"})
        self.assertEqual([u["id"] for u in contract.open_holds(out)], ["U1", "U2"])

    def test_the_caveat_counts_only_open_holds(self):
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "rejected", "reason": "축이 아니다"})
        self.assertTrue(any("1건" in c for c in review.caveats(out)))

    def test_closing_a_hold_does_not_accept_the_items_it_named(self):
        """보류를 닫는 것과 항목을 승인하는 것은 다르다."""
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U2",
                     "outcome": "rejected", "reason": "끝점을 잘못 지목했다"})
        self.assertEqual(out["entities"][0]["status"], "proposed")
        self.assertEqual(out["review_status"]["accepted_item_ids"], [])


class SchemaHolds(unittest.TestCase):
    def test_a_resolved_hold_still_validates(self):
        from sesx import schema
        out = apply(doc_with_holds(),
                    {"decision": "resolve", "unresolved_id": "U1",
                     "outcome": "superseded", "reason": "축을 붙였다", "by": "D-human-1"})
        self.assertEqual(schema.errors(out), [])
        self.assertEqual(contract.validate_artifact(out), [])




def doc_with_axis():
    """`axis.py` 가 낸 모양 그대로 — 갈래는 개체이고 축은 부모 미확정 보류다."""
    doc = contract.new_artifact("r1")
    doc["source_snapshot"]["snapshot_id"] = "snap-r1"
    site = {"file_path": "src/main/java/com/wastesim/model/TruckType.java",
            "start_line": 8, "end_line": 8,
            "quote": '    LARGE_5TON  ("5톤", 5000.0),', "symbol": "LARGE_5TON"}
    doc["entities"] = [
        {"id": "E1", "name": "수거차량", "kind": "stateful",
         "scope": "simulation_target", "status": "proposed", "evidence_ids": []},
        {"id": "E2", "name": "LARGE_5TON", "kind": "type", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": [], "origin": "derived_by_code"},
        {"id": "E3", "name": "SMALL_1TON", "kind": "type", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": [], "origin": "derived_by_code"},
    ]
    doc["unresolved"] = [{
        "id": "U1", "item_ids": ["E2", "E3"], "reason_code": "parent_undetermined",
        "explanation": "'TruckType' 축의 갈래 2개는 확인됐다. 부모가 정해지지 않았다",
        "origin_raw": {"axis_label": "TruckType", "kind": "SPEC",
                       "members": ["LARGE_5TON", "SMALL_1TON"],
                       "member_evidence": {"LARGE_5TON": [site],
                                           "SMALL_1TON": [dict(site, start_line=10,
                                                               end_line=10,
                                                               symbol="SMALL_1TON")]}}}]
    return doc


def attach(doc, **over):
    d = {"decision": "attach_axis", "unresolved_id": "U1", "parent_item_id": "E1",
         "label": "차종 축", "reason": "용량·기동성은 차량의 성질이다 (CP-4)"}
    d.update(over)
    return review.apply_decisions(doc, [d], "r1-rev1", reviewer="검토자")


class AttachingAnAxis(unittest.TestCase):
    def test_a_spec_is_created_under_the_named_parent(self):
        out = attach(doc_with_axis())
        self.assertEqual(len(out["decompositions"]), 1)
        d = out["decompositions"][0]
        self.assertEqual(d["kind"], "SPEC")
        self.assertEqual(d["parent_entity_id"], "E1")
        self.assertEqual([m["entity_id"] for m in d["members"]], ["E2", "E3"])
        self.assertEqual(d["label"], "차종 축")

    def test_the_code_found_member_evidence_is_carried_over(self):
        """이 경로의 유일한 자산이다. 버리면 붙일 이유가 없다."""
        out = attach(doc_with_axis())
        ev = {e["id"]: e for e in out["evidence"]}
        for m in out["decompositions"][0]["members"]:
            self.assertTrue(m["evidence_ids"])
            for eid in m["evidence_ids"]:
                self.assertIn("TruckType.java", ev[eid]["file_path"])
                self.assertEqual(ev[eid]["origin"], "derived_by_code")
                self.assertEqual(ev[eid]["supports"][0]["field"], "member")

    def test_the_origin_is_split_honestly(self):
        """부모는 사람이 골랐고 갈래 근거는 코드가 찾았다."""
        d = attach(doc_with_axis())["decompositions"][0]
        self.assertEqual(d["origin"], "human")
        self.assertEqual(d["members_origin"], "derived_by_code")

    def test_activation_is_unknown_not_invented(self):
        out = attach(doc_with_axis())
        act = {a["id"]: a for a in out["activation"]}[out["decompositions"][0]["activation_id"]]
        self.assertEqual(act["state"], "unknown")
        self.assertEqual(act["coverage"], "incomplete")

    def test_the_hold_is_closed_as_superseded_by_the_new_decomposition(self):
        out = attach(doc_with_axis())
        rec = out["unresolved"][0]
        self.assertEqual(rec["resolution"]["outcome"], "superseded")
        self.assertEqual(rec["resolution"]["by"], out["decompositions"][0]["id"])
        self.assertEqual(contract.open_holds(out), [])

    def test_the_record_still_says_what_it_was(self):
        rec = attach(doc_with_axis())["unresolved"][0]
        self.assertEqual(rec["origin_raw"]["axis_label"], "TruckType")

    def test_the_artifact_stays_valid(self):
        from sesx import schema
        out = attach(doc_with_axis())
        self.assertEqual(schema.errors(out), [])
        self.assertEqual(contract.validate_artifact(out), [])


class AttachRefusals(unittest.TestCase):
    def refuses(self, message, **over):
        with self.assertRaises(ValueError) as cm:
            attach(doc_with_axis(), **over)
        self.assertIn(message, str(cm.exception))

    def test_an_absent_parent_is_not_created(self):
        """PES 루트와 같은 규칙 — 있는 개체만 지목할 수 있다."""
        self.refuses("없는 부모를 만들지 않는다", parent_item_id="E999")

    def test_a_branch_cannot_be_its_own_parent(self):
        self.refuses("자기 갈래", parent_item_id="E2")

    def test_a_reason_is_required(self):
        self.refuses("이유가 필요하다", reason="  ")

    def test_only_a_parent_undetermined_hold_can_be_attached(self):
        doc = doc_with_axis()
        doc["unresolved"][0]["reason_code"] = "unknown_reference"
        with self.assertRaises(ValueError) as cm:
            attach(doc)
        self.assertIn("부모 미확정 보류가 아니다", str(cm.exception))

    def test_an_already_closed_hold_is_refused(self):
        out = attach(doc_with_axis())
        with self.assertRaises(ValueError) as cm:
            review.apply_decisions(out, [{"decision": "attach_axis", "unresolved_id": "U1",
                                          "parent_item_id": "E1", "reason": "다시"}],
                                   "r1-rev2")
        self.assertIn("이미 해소됐다", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
