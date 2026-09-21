# -*- coding: utf-8 -*-
"""두 출처가 같은 자리에 다른 말을 하는가.

`axis.py` 는 부모를 고르지 않고 `parent_undetermined` 로 보류한다 — 축이 어느 개체의
성질인가가 의미 판단이기 때문이다. 그런데 갈래가 개체로 서고 나면 `d` 단계가 거기에
부모를 붙인다. q3-decl-2 에서 실제로 그랬다:

    axis.py  'TruckType' 축의 갈래 3개 — 부모 미확정          (보류)
    d 단계   SPEC SimulationConfig → [LARGE_5TON, ...]        (확정)

분해와 보류는 서로 다른 배열이라 `DECOMP_SINGLE_PARENT` 에 걸리지 않는다. 조용히
공존한다. 이 검사는 **어느 쪽이 맞는지 판정하지 않는다** — 둘이 다르다는 사실만 낸다.
"""
from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import contract, review, validate  # noqa: E402


def doc_with(parent_of_branches=None, reason_code="parent_undetermined"):
    doc = contract.new_artifact("r1")
    doc["source_snapshot"]["snapshot_id"] = "snap-r1"
    doc["entities"] = [
        {"id": "E1", "name": "설정", "kind": "stateful", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": []},
        {"id": "E2", "name": "LARGE_5TON", "kind": "type", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": []},
        {"id": "E3", "name": "SMALL_1TON", "kind": "type", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": []},
    ]
    doc["activation"] = [{"id": "AC1", "state": "unknown", "coverage": "incomplete",
                          "combination": None, "clauses": [], "status": "proposed",
                          "evidence_ids": []}]
    doc["unresolved"] = [{
        "id": "U1", "item_ids": ["E2", "E3"], "reason_code": reason_code,
        "explanation": "'TruckType' 축의 갈래 2개는 확인됐다",
        "origin_raw": {"axis_label": "TruckType"}}]
    if parent_of_branches:
        doc["decompositions"] = [{
            "id": "D1", "kind": "SPEC", "parent_entity_id": parent_of_branches,
            "members": [{"entity_id": "E2", "evidence_ids": []},
                        {"entity_id": "E3", "evidence_ids": []}],
            "activation_id": "AC1", "status": "proposed", "evidence_ids": []}]
    return doc


def result(doc):
    validate.run(doc, snapshot=None, raw_ids=None)
    return next(c for c in doc["validation_results"]["checks"]
                if c["check_id"] == "PARENT_SOURCE_CONFLICT")


class Detection(unittest.TestCase):
    def test_a_held_axis_and_a_confirmed_parent_conflict(self):
        c = result(doc_with(parent_of_branches="E1"))
        self.assertEqual(c["result"], "fail")
        self.assertEqual(len(c["item_ids"]), 2)
        self.assertIn("TruckType", c["item_ids"][0])
        self.assertIn("D1", c["item_ids"][0])

    def test_a_held_axis_alone_is_no_conflict(self):
        self.assertEqual(result(doc_with())["result"], "pass")

    def test_the_check_only_reports_it_does_not_block(self):
        """어느 쪽이 맞는지는 검토가 정한다. 승인을 막지 않는다."""
        doc = doc_with(parent_of_branches="E1")
        c = result(doc)
        self.assertFalse(c["blocking"])
        self.assertNotIn("PARENT_SOURCE_CONFLICT",
                         doc["validation_results"]["blocking_failure_ids"])

    def test_other_hold_kinds_are_not_conflicts(self):
        c = result(doc_with(parent_of_branches="E1", reason_code="unknown_reference"))
        self.assertEqual(c["result"], "pass")

    def test_a_rejected_decomposition_is_not_a_conflict(self):
        doc = doc_with(parent_of_branches="E1")
        doc["decompositions"][0]["status"] = "rejected"
        self.assertEqual(result(doc)["result"], "pass")


class AfterReview(unittest.TestCase):
    """검토가 정하면 불일치가 사라진다."""

    def test_attaching_the_axis_clears_it(self):
        doc = doc_with()
        out = review.apply_decisions(doc, [{
            "decision": "attach_axis", "unresolved_id": "U1", "parent_item_id": "E1",
            "reason": "설정이 차종을 고른다"}], "rev1", reviewer="검토자")
        self.assertEqual(result(out)["result"], "pass")

    def test_rejecting_the_held_axis_clears_it(self):
        doc = doc_with(parent_of_branches="E1")
        out = review.apply_decisions(doc, [{
            "decision": "resolve", "unresolved_id": "U1", "outcome": "superseded",
            "reason": "d 가 낸 부모를 받아들인다", "by": "D1"}], "rev1")
        self.assertEqual(result(out)["result"], "pass")

    def test_deferring_keeps_the_conflict_visible(self):
        doc = doc_with(parent_of_branches="E1")
        out = review.apply_decisions(doc, [{
            "decision": "resolve", "unresolved_id": "U1", "outcome": "deferred",
            "reason": "더 봐야 한다"}], "rev1")
        self.assertEqual(result(out)["result"], "fail")


if __name__ == "__main__":
    unittest.main()
