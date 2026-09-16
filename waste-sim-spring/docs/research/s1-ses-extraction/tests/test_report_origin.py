# -*- coding: utf-8 -*-
import unittest
from sesx import contract, report


def doc_with_human():
    doc = contract.new_artifact("a1")
    doc["entities"] = [
        {"id": "E1", "name": "수거지점", "kind": "stateful",
         "scope": "simulation_target", "status": "proposed", "evidence_ids": []},
        {"id": "E-human-1", "name": "장량동 생활쓰레기 수거 시뮬레이터",
         "kind": "boundary", "scope": "simulation_target", "origin": "human",
         "status": "proposed", "evidence_ids": []},
    ]
    doc["decompositions"] = [
        {"id": "D-human-1", "parent_entity_id": "E-human-1", "kind": "ASPECT",
         "label": "시스템 구성", "members": [{"entity_id": "E1"}],
         "activation_id": None, "origin": "human", "status": "proposed",
         "evidence_ids": []}]
    return doc


class OriginTests(unittest.TestCase):
    def test_사람이_넣은_것을_따로_센다(self):
        text = report.render(doc_with_human())
        self.assertIn("사람이 넣은 것", text)
        self.assertIn("장량동 생활쓰레기 수거 시뮬레이터", text)

    def test_추출과_사람_수가_갈린다(self):
        text = report.render(doc_with_human())
        self.assertIn("추출 1", text)
        self.assertIn("사람 1", text)

    def test_사람이_넣은_것이_없으면_절이_없다(self):
        doc = doc_with_human()
        doc["entities"] = [doc["entities"][0]]
        doc["decompositions"] = []
        self.assertNotIn("사람이 넣은 것", report.render(doc))


if __name__ == "__main__":
    unittest.main()
