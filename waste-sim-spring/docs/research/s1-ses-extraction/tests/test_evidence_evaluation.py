import copy
import unittest

from evaluate_evidence import projection, prf, compare, stability
from test_evidence_hardening import HardenedTests


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        fixture = HardenedTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.doc, _ = fixture.doc()
        self.reference = copy.deepcopy(self.doc)
        self.reference["review_status"]["status"] = "reviewed"

    def test_direction_and_payload_changes_are_penalized(self):
        self.doc["couplings"][0]["source"], self.doc["couplings"][0]["target"] = (
            self.doc["couplings"][0]["target"], self.doc["couplings"][0]["source"])
        self.assertEqual(compare(self.doc, self.reference)["metrics"]["couplings"]["matched"], 0)

    def test_condition_changes_are_penalized(self):
        self.doc["activation"][-1]["clauses"][0]["expression"] = "bin.fill == 0"
        self.assertEqual(compare(self.doc, self.reference)["metrics"]["activation"]["matched"], 1)

    def test_unknown_is_not_unconditional(self):
        self.doc["activation"][0]["state"] = "unknown"
        report = compare(self.doc, self.reference)
        self.assertEqual(report["unknown_activation_count"], 1)
        self.assertEqual(report["metrics"]["activation"]["matched"], 1)

    def test_empty_evaluation_is_not_perfect(self):
        self.assertIsNone(prf(set(), set())["f1"])

    def test_reference_must_be_reviewed_and_same_source(self):
        with self.assertRaises(ValueError):
            compare(self.doc, self.doc)
        self.reference["source_snapshot"]["files"][0]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            compare(self.doc, self.reference)

    def test_repeat_stability_separates_stateful_and_structure(self):
        rows = stability([self.doc, self.doc])
        self.assertEqual(rows[0]["jaccard"]["stateful_entities"], 1)
        self.assertEqual(rows[0]["jaccard"]["structural_nodes"], 1)


if __name__ == "__main__":
    unittest.main()
