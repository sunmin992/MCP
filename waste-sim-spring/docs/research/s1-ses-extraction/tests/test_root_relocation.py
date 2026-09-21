# -*- coding: utf-8 -*-
"""루트 검사의 이동 — SES 는 보고하고, PES 가 강제한다.

이 변경은 **승인 문턱을 낮춘다.** 루트 없는 SES 가 승인될 수 있게 된다. 그 대가로
두 가지를 강제한다. ① 승인 범위에 "루트 미확정"이 남는다. ② 단일 루트·도달 가능성은
사라지지 않고 PES 층의 차단 검사로 옮겨간다. 아래 시험이 그 둘을 지킨다.
"""
from __future__ import annotations

import copy
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

import refses  # noqa: E402
from sesx import pes, review, validate  # noqa: E402
from test_pes import complete_request, root_of  # noqa: E402


def forest(doc):
    """루트를 여럿으로 만든다 — 최상위 분해를 떼면 그 자식들이 각자 루트가 된다."""
    out = copy.deepcopy(doc)
    top = root_of(out)
    out["decompositions"] = [d for d in out["decompositions"]
                             if d["parent_entity_id"] != top]
    return out


def checks_of(doc):
    validate.run(doc, snapshot=None, raw_ids=None)
    return {c["check_id"]: c for c in doc["validation_results"]["checks"]}


class RootIsReportedNotEnforced(unittest.TestCase):
    def setUp(self):
        self.doc, _, _ = refses.load()

    def test_many_root_candidates_do_not_block_approval(self):
        by = checks_of(forest(self.doc))
        self.assertEqual(by["ROOT_CANDIDATES"]["result"], "fail")
        self.assertFalse(by["ROOT_CANDIDATES"]["blocking"])
        self.assertNotIn("ROOT_CANDIDATES",
                         self.doc.get("validation_results", {}).get("blocking_failure_ids", []))

    def test_root_candidates_are_listed_with_the_check(self):
        d = forest(self.doc)
        by = checks_of(d)
        self.assertGreater(len(by["ROOT_CANDIDATES"]["item_ids"]), 1)
        self.assertIn("PES", by["ROOT_CANDIDATES"]["details"])

    def test_single_candidate_still_passes(self):
        by = checks_of(copy.deepcopy(self.doc))
        self.assertEqual(by["ROOT_CANDIDATES"]["result"], "pass")

    def test_reachability_is_not_checked_without_a_root(self):
        """루트가 없으면 도달 가능성은 실패가 아니라 미검사다 — 모르는 것을 틀렸다고 하지 않는다."""
        by = checks_of(forest(self.doc))
        self.assertEqual(by["REACHABILITY"]["result"], "not_checked")


class ApprovalSaysWhatIsMissing(unittest.TestCase):
    def setUp(self):
        self.doc, _, _ = refses.load()

    def test_undetermined_root_is_named_in_the_caveats(self):
        c = review.caveats(forest(self.doc))
        self.assertTrue(any("루트 미확정" in x for x in c))
        self.assertTrue(any("PES" in x for x in c))

    def test_selected_root_drops_the_caveat(self):
        d = copy.deepcopy(self.doc)
        d["structure"]["selected_root_id"] = root_of(d)
        self.assertFalse(any("루트 미확정" in x for x in review.caveats(d)))

    def test_finalize_carries_the_caveat_into_the_approval(self):
        d = forest(self.doc)
        review.finalize(d, snapshot=None, raw_ids=None)
        self.assertTrue(any("루트 미확정" in x for x in d["approval"]["caveats"]))


class EnforcementMovedToPes(unittest.TestCase):
    """SES 에서 느슨해진 만큼 PES 에서 조인다."""

    def setUp(self):
        self.doc, _, _ = refses.load()

    def test_single_root_is_blocking_in_the_pes_layer(self):
        self.assertIn("PES_SINGLE_ROOT", pes.BLOCKING)
        self.assertIn("PES_REACHABLE", pes.BLOCKING)

    def test_a_forest_cannot_become_a_simulator_without_a_request(self):
        """루트 미확정 SES 는 승인될 수 있어도 PES 없이는 시뮬레이터가 아니다."""
        d = forest(self.doc)
        out = pes.build(d, complete_request(d), "PES-0001")
        # 요청이 루트를 지목하면 그 루트에서 닿는 것만 남는다 — 나머지는 사유와 함께 빠진다
        self.assertEqual(out["status"], "built")
        self.assertLess(len(out["entities"]), len(d["entities"]))
        self.assertTrue(out["dropped"])

    def test_pes_refuses_when_nobody_named_a_root(self):
        d = forest(self.doc)
        req = complete_request(d)
        req["root"] = {}
        out = pes.build(d, req, "PES-0001")
        self.assertEqual(out["status"], "refused")
        self.assertIn("PES_ROOT_EXISTS", out["validation_results"]["blocking_failure_ids"])


if __name__ == "__main__":
    unittest.main()
