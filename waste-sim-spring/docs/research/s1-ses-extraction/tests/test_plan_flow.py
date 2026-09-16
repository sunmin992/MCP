# -*- coding: utf-8 -*-
import unittest
from sesx import stages

EVW = [{"file_path": "E.java", "start_line": 454, "end_line": 454, "quote": "w"}]
EVR = [{"file_path": "E.java", "start_line": 414, "end_line": 414, "quote": "r"}]
EVI = [{"file_path": "E.java", "start_line": 254, "end_line": 254, "quote": "decl"}]

B2 = {"subjects": [{"state": "fill[b][t]", "classification": "entity_state",
                    "owner_candidate": "수거지점", "why": "x",
                    "state_evidence": EVW, "identity_evidence": EVI,
                    "linkage_evidence": EVI, "consumption_evidence": EVR}]}


class PlanTests(unittest.TestCase):
    def test_계획이_있다(self):
        self.assertEqual(stages.PLANS["T2-flow"],
                         ("a", "b2", "g", "c", "d", "e1", "e2", "f", "b3"))

    def test_루트_단계가_없다(self):
        self.assertNotIn("r", stages.PLANS["T2-flow"])
        self.assertNotIn("r2", stages.PLANS["T2-flow"])

    def test_기존_계획을_건드리지_않았다(self):
        self.assertEqual(stages.PLANS["T2-tree"],
                         ("a", "b2", "g", "c", "d", "r2", "e", "f", "b3"))

    def test_프롬프트가_이어진다(self):
        self.assertTrue(stages.system_prompt("e1").strip())
        self.assertTrue(stages.system_prompt("e2").strip())

    def test_e2_는_확정_이름_목록을_받는다(self):
        self.assertIn("e2", stages.NAME_LIST_STAGES)
        self.assertNotIn("e1", stages.NAME_LIST_STAGES)

    def test_e1_문맥에_값_목록이_들어간다(self):
        block, _ = stages._context_block("e1", {"b2": B2}, 100000)
        self.assertIn("fill[b][t]", block)

    def test_e2_문맥에_자리_목록이_들어간다(self):
        block, _ = stages._context_block("e2", {"b2": B2, "e1": {"value_sites": []}},
                                         100000)
        self.assertIn("e1:v0:w0", block)
        self.assertIn("e1:v0:r0", block)


class FlowPayloadTests(unittest.TestCase):
    def test_payload_를_만든다(self):
        e2 = {"site_actors": [{"site_id": "e1:v0:w0", "entity": "배출"},
                              {"site_id": "e1:v0:r0", "entity": "수거판정"}]}
        out = stages.flow_payload({"b2": B2, "e1": {"value_sites": []}, "e2": e2})
        self.assertEqual(len(out["couplings"]), 1)
        self.assertEqual(out["couplings"][0]["source"]["entity"], "배출")


if __name__ == "__main__":
    unittest.main()
