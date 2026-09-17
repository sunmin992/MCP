# -*- coding: utf-8 -*-
import unittest
from sesx import assemble, contract, stages

EV = [{"file_path": "E.java", "start_line": 1, "end_line": 1, "quote": "q"}]
EV2 = [{"file_path": "E.java", "start_line": 2, "end_line": 2, "quote": "q2"}]


def subject(owner):
    return {"state": f"{owner}_state", "classification": "entity_state",
            "owner_candidate": owner, "why": "상태 소유자",
            "state_evidence": EV, "identity_evidence": EV2,
            "linkage_evidence": EV, "consumption_evidence": EV2}


FLOW = {"couplings": [{
            "source": {"entity": "배출", "attribute": None, "symbol": None},
            "target": {"entity": "넘침판정", "attribute": None, "symbol": None},
            "payload": {"kind": "shared_state", "code_expression": "fill[b][t]",
                        "meaning": None},
            "mechanism": "공유 상태 fill[b][t] 를 통해 전달된다. 기여한 행: 454, 470",
            "derived_from": ["e1:v0:w0", "e1:v0:r1"],
            "evidence": EV}],
        "internal_updates": [{"entity": "수거", "value": "fill[b][t]",
                              "write_site_id": "e1:v0:w1",
                              "read_site_id": "e1:v0:r0"}],
        "unresolved_values": [{"value": "없는값[x]", "why": "맞추지 못했다",
                               "sites": [{"file_path": "E.java", "start_line": 9}]}],
        "unresolved_pairs": [{"value": "fill[b][t]", "write_site_id": "e1:v0:w2",
                              "read_site_id": "e1:v0:r0",
                              "why": "주체 미해결: ['e1:v0:w2']"}]}


def build():
    return assemble.build(
        {"b2": {"subjects": [subject("배출"), subject("넘침판정"), subject("수거")]},
         "flow": FLOW},
        "s", "a", {"strategy": "T2-flow"}, {},
        no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)


class FlowAssemblyTests(unittest.TestCase):
    def test_결합이_조립된다(self):
        doc = build()
        self.assertEqual(len(doc["couplings"]), 1)

    def test_파생_출처가_남는다(self):
        doc = build()
        self.assertEqual(doc["couplings"][0]["derived_from"],
                         ["e1:v0:w0", "e1:v0:r1"])

    def test_payload_가_확정이라_보류되지_않는다(self):
        doc = build()
        self.assertNotIn("payload_unknown",
                         [u["reason_code"] for u in doc["unresolved"]])

    def test_상태_갱신은_판정_기록으로_남는다(self):
        doc = build()
        rec = [p for p in doc["provenance"]
               if (p.get("raw_id") or "").startswith("flow:internal_update")]
        self.assertEqual(len(rec), 1)
        self.assertEqual(rec[0]["decision"], "held")
        self.assertTrue(rec[0]["origin_raw"])

    def test_주체_미해결_쌍은_보류로_남는다(self):
        doc = build()
        held = [u for u in doc["unresolved"]
                if (u.get("raw_id") or "").startswith("flow:unresolved_pair")]
        self.assertEqual(len(held), 1)
        self.assertEqual(held[0]["reason_code"], "unknown_reference")
        self.assertTrue(held[0]["origin_raw"])

    def test_값_이름_미해결도_보류로_남는다(self):
        doc = build()
        held = [u for u in doc["unresolved"]
                if (u.get("raw_id") or "").startswith("flow:unresolved_value")]
        self.assertEqual(len(held), 1)
        self.assertTrue(held[0]["origin_raw"]["sites"])

    def test_조립_결과에_자기결합이_없다(self):
        doc = build()
        codes = [f["code"] for f in contract.validate_artifact(doc)]
        self.assertNotIn("SELF_COUPLING", codes)


if __name__ == "__main__":
    unittest.main()
