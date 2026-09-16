# -*- coding: utf-8 -*-
import unittest
from sesx import contract


def doc_with_coupling(source_eid, target_eid):
    """개체 하나짜리 최소 산출물에 결합 하나를 단다."""
    doc = contract.new_artifact("a1")
    doc["entities"] = [
        {"id": "E1", "name": "수거", "kind": "stateful", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": []},
        {"id": "E2", "name": "넘침판정", "kind": "stateful", "scope": "simulation_target",
         "status": "proposed", "evidence_ids": []},
    ]
    doc["activation"] = [{"id": "A1", "state": "unknown", "clauses": [],
                          "combination": None, "coverage": "incomplete",
                          "status": "proposed", "evidence_ids": []}]
    doc["couplings"] = [{
        "id": "C1",
        "source": {"entity_id": source_eid, "attribute_id": None, "symbol": None},
        "target": {"entity_id": target_eid, "attribute_id": None, "symbol": None},
        "payload": {"kind": "shared_state", "code_expression": "fill[b][t]",
                    "meaning": None},
        "mechanism": None, "activation_id": "A1",
        "derived_from": [], "evidence_ids": [], "status": "proposed"}]
    return doc


class SelfCouplingTests(unittest.TestCase):
    def codes(self, doc):
        return [f["code"] for f in contract.validate_artifact(doc)]

    def test_자기결합은_불합격이다(self):
        doc = doc_with_coupling("E1", "E1")
        self.assertIn("SELF_COUPLING", self.codes(doc))

    def test_서로_다른_끝점은_통과한다(self):
        doc = doc_with_coupling("E1", "E2")
        self.assertNotIn("SELF_COUPLING", self.codes(doc))


class SchemaTests(unittest.TestCase):
    def test_스키마_판이_올랐다(self):
        self.assertEqual(contract.SCHEMA_VERSION, "ses-extraction/1.1")

    def test_모양_위반_사유가_있다(self):
        self.assertIn("shape_violation", contract.REASON_CODES)


if __name__ == "__main__":
    unittest.main()
