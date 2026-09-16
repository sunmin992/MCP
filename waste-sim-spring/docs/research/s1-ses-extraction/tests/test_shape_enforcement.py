# -*- coding: utf-8 -*-
"""조립기가 트리 모양 계약과 역할 근거를 집행하는가.

`stateful` 개체는 b2 의 상태 소유자에서, `boundary`·`set` 개체는 g 의 구성 후보에서
나온다. 고정물이 그 경로를 그대로 탄다.
"""
import unittest
from sesx import assemble, stages

DECL = [{"file_path": "SimulationEngine.java", "start_line": 254, "end_line": 254,
         "quote": "double[][] fill = new double[nB][nT];"}]
EV = [{"file_path": "SimulationEngine.java", "start_line": 300, "end_line": 300,
       "quote": "trips.add(trip);"}]
EV2 = [{"file_path": "SimulationEngine.java", "start_line": 301, "end_line": 301,
        "quote": "for (int i = 0; i < n; i++)"}]


def entity(name, kind="boundary"):
    """g 단계의 구성 후보. boundary/set/type 만 받는다."""
    return {"name": name, "kind": kind, "scope": "simulation_target",
            "identity_evidence": EV, "composition_evidence": EV2}


def subject(owner):
    """b2 단계의 상태 소유자. 여기서 stateful 개체가 나온다."""
    return {"state": f"{owner}_state", "classification": "entity_state",
            "owner_candidate": owner, "why": "상태 소유자",
            "state_evidence": EV, "identity_evidence": EV2,
            "linkage_evidence": DECL, "consumption_evidence": EV}


def decomp(parent, kind, members, member_ev=None, label="축"):
    d = {"parent": parent, "kind": kind, "members": members, "label": label,
         "evidence": EV,
         "member_evidence": member_ev or {m: EV2 for m in members}}
    if kind == "MULTI":
        d["multiplicity"] = {"count_expression": "n", "minimum": None, "maximum": None,
                             "evidence": EV2}
    if kind == "SPEC":
        d["selection"] = {"symbol": "type", "description": "유형을 고른다"}
    return d


def build(payload):
    return assemble.build(payload, "s", "a", {"strategy": "T2-flow"}, {},
                          no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)


def reasons(doc):
    return [u["reason_code"] for u in doc["unresolved"]]


class MemberEvidenceTests(unittest.TestCase):
    """자식이 이미 확정 개체여도 역할 근거를 요구한다.

    두 경로가 있다. 트리 모드(g 가 있을 때)는 composition.py 의 형식 검사가 먼저
    잡고, 그렇지 않을 때는 조립기가 잡는다. 어느 쪽이든 분해로 서지 못한다.
    """

    def test_트리_모드에서_보류한다(self):
        doc = build({"g": {"entities": [entity("수거장 적재 시스템"),
                                        entity("수거지점 집합", "set")]},
                     "d": {"decompositions": [
                         {"parent": "수거장 적재 시스템", "kind": "ASPECT",
                          "members": ["수거지점 집합"], "label": "적재 구성",
                          "evidence": EV}]}})
        self.assertTrue(doc["unresolved"])
        self.assertEqual(doc["decompositions"], [])

    def test_트리_모드가_아니어도_보류한다(self):
        doc = build({"b2": {"subjects": [subject("운행"), subject("트럭")]},
                     "d": {"decompositions": [
                         {"parent": "운행", "kind": "SPEC", "members": ["트럭"],
                          "label": "축", "evidence": EV,
                          "selection": {"symbol": "t", "description": "고른다"}}]}})
        self.assertIn("unknown_reference", reasons(doc))
        self.assertEqual(doc["decompositions"], [])


class ShapeTests(unittest.TestCase):
    def test_개체가_multi_부모면_보류한다(self):
        # 운행 ─MULTI→ 트럭. 운행은 set 이 아니므로 MULTI 를 만들 자리가 없다
        doc = build({"b2": {"subjects": [subject("운행"), subject("트럭")]},
                     "d": {"decompositions": [decomp("운행", "MULTI", ["트럭"])]}})
        self.assertIn("shape_violation", reasons(doc))
        self.assertEqual(doc["decompositions"], [])

    def test_multi_자식이_둘이면_보류한다(self):
        doc = build({"g": {"entities": [entity("거주민 집합", "set")]},
                     "b2": {"subjects": [subject("거주민"), subject("방문객")]},
                     "d": {"decompositions": [
                         decomp("거주민 집합", "MULTI", ["거주민", "방문객"])]}})
        self.assertIn("shape_violation", reasons(doc))

    def test_같은_선언_행을_가리키는_두_자식은_보류한다(self):
        doc = build({"g": {"entities": [entity("건물과 쓰레기 종류"),
                                        entity("건물", "set"),
                                        entity("쓰레기 종류", "set")]},
                     "d": {"decompositions": [
                         decomp("건물과 쓰레기 종류", "ASPECT", ["건물", "쓰레기 종류"],
                                {"건물": DECL, "쓰레기 종류": DECL})]}})
        self.assertIn("shape_violation", reasons(doc))
        self.assertEqual(doc["decompositions"], [])

    def test_합법한_분해는_통과하고_축_이름을_지킨다(self):
        doc = build({"g": {"entities": [entity("거주민 집합", "set")]},
                     "b2": {"subjects": [subject("거주민")]},
                     "d": {"decompositions": [
                         decomp("거주민 집합", "MULTI", ["거주민"], label="거주민 다중")]}})
        self.assertNotIn("shape_violation", reasons(doc))
        self.assertEqual(len(doc["decompositions"]), 1)
        self.assertEqual(doc["decompositions"][0]["label"], "거주민 다중")

    def test_보류해도_원문이_남는다(self):
        doc = build({"b2": {"subjects": [subject("운행"), subject("트럭")]},
                     "d": {"decompositions": [decomp("운행", "MULTI", ["트럭"])]}})
        held = [u for u in doc["unresolved"] if u["reason_code"] == "shape_violation"]
        self.assertTrue(held[0]["origin_raw"])


if __name__ == "__main__":
    unittest.main()
