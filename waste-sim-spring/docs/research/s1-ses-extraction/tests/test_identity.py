# -*- coding: utf-8 -*-
"""개체 동일성은 이름이 아니라 코드 자리다.

이름으로 모으면 다른 클래스의 동명 필드가 한 개체로 합쳐진다. 실제 소스를 세어 보면
`byId` 가 세 클래스, `values` 와 `residualByWasteType` 이 각각 두 클래스에 있다. 열거
상수도 같다 — `PAPER_BASELINE` 이 `DischargeTimeMode` 와 `ScenarioScale` 양쪽에 있고,
지금 산출물에 그 병합이 실제로 들어 있었다.

모델이 낸 후보에는 앵커가 없다. 그쪽은 이름으로 모으는 것이 맞다 — 여러 단계가 같은
개체를 같은 한국어 이름으로 부르면 하나로 합쳐져야 한다. 그래서 규칙은 하나다:
**앵커가 있으면 앵커, 없으면 이름.**
"""
from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import assemble, axis, collection, stages  # noqa: E402

EV = [{"file_path": "f.java", "start_line": 1, "end_line": 1, "quote": "q"}]


def ent(name, anchor=None, **over):
    e = {"name": name, "kind": "set", "scope": "simulation_target", "evidence": EV}
    if anchor:
        e["anchor"] = anchor
    e.update(over)
    return e


def build(payloads):
    return assemble.build(payloads, "snap", "art", {"strategy": "T2-decl", "run_id": "r"}, {},
                          no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)


class AnchorKey(unittest.TestCase):
    def test_the_owning_type_separates_same_named_fields(self):
        a = assemble.anchor_key({"file_path": "A.java", "owner_type": "A", "symbol": "byId"})
        b = assemble.anchor_key({"file_path": "B.java", "owner_type": "B", "symbol": "byId"})
        self.assertNotEqual(a, b)

    def test_the_same_site_is_the_same_key(self):
        k = {"file_path": "A.java", "owner_type": "A", "symbol": "byId"}
        self.assertEqual(assemble.anchor_key(k), assemble.anchor_key(dict(k)))

    def test_no_anchor_is_no_key(self):
        self.assertIsNone(assemble.anchor_key(None))
        self.assertIsNone(assemble.anchor_key({}))


class SameNameDifferentPlace(unittest.TestCase):
    def three_byid(self):
        return {"collection": {"entities": [
            ent("byId", {"file_path": "A.java", "owner_type": "A", "symbol": "byId"}),
            ent("byId", {"file_path": "B.java", "owner_type": "B", "symbol": "byId"}),
            ent("byId", {"file_path": "C.java", "owner_type": "C", "symbol": "byId"})]}}

    def test_three_classes_give_three_entities(self):
        doc = build(self.three_byid())
        self.assertEqual(len(doc["entities"]), 3)

    def test_without_anchors_they_would_merge(self):
        """대조 — 이 시험이 실패하면 막은 자리를 잘못 짚은 것이다."""
        doc = build({"collection": {"entities": [ent("byId"), ent("byId"), ent("byId")]}})
        self.assertEqual(len(doc["entities"]), 1)

    def test_a_name_that_no_longer_picks_one_entity_is_held(self):
        """코드가 하나를 고르지 않는다. 이름이 모호하면 보류다."""
        p = self.three_byid()
        p["c"] = {"attributes": [{"entity": "byId", "name": "size", "value_type": "int",
                                  "unit": {"value": None, "evidence": []},
                                  "default": {"value": None, "evidence": []},
                                  "range": {"value": "unknown", "evidence": []},
                                  "evidence": EV}]}
        doc = build(p)
        self.assertEqual(doc["attributes"], [])
        held = [u for u in doc["unresolved"] if u["reason_code"] == "ambiguous_reference"]
        self.assertEqual(len(held), 1)
        self.assertIn("둘 이상", held[0]["explanation"])

    def test_an_unambiguous_name_still_resolves(self):
        p = {"collection": {"entities": [
            ent("sites", {"file_path": "A.java", "owner_type": "A", "symbol": "sites"})]},
            "c": {"attributes": [{"entity": "sites", "name": "size", "value_type": "int",
                                  "unit": {"value": None, "evidence": []},
                                  "default": {"value": None, "evidence": []},
                                  "range": {"value": "unknown", "evidence": []},
                                  "evidence": EV}]}}
        self.assertEqual(len(build(p)["attributes"]), 1)


class ModelCandidatesStillMergeByName(unittest.TestCase):
    """앵커가 없는 후보는 예전처럼 이름으로 모인다. 그게 b2 가 기대하는 동작이다."""

    def test_two_stages_naming_the_same_owner_give_one_entity(self):
        doc = build({"b2": {"subjects": [
            {"state": "fill", "owner_candidate": "건물", "classification": "entity_state",
             "state_evidence": EV, "identity_evidence": EV,
             "linkage_evidence": EV, "consumption_evidence": EV},
            {"state": "peak", "owner_candidate": "건물", "classification": "entity_state",
             "state_evidence": EV, "identity_evidence": EV,
             "linkage_evidence": EV, "consumption_evidence": EV}]}})
        self.assertEqual([e["name"] for e in doc["entities"]], ["건물"])
        self.assertEqual(len(doc["attributes"]), 2)

    def test_a_model_name_can_still_point_at_an_anchored_entity(self):
        p = {"collection": {"entities": [
            ent("WasteType", {"file_path": "WasteType.java", "owner_type": None,
                              "symbol": "WasteType"}, kind="stateful")]},
            "d": {"decompositions": [{"kind": "SPEC", "parent": "WasteType",
                                      "label": "x", "members": ["일반"],
                                      "member_evidence": {"일반": EV}, "evidence": EV}]}}
        doc = build(p)
        self.assertEqual(len(doc["decompositions"]), 1)


class DerivedCandidatesCarryAnchors(unittest.TestCase):
    """파생기는 앵커를 달아야 한다. exp/ 는 커밋되지 않으므로 합성 스냅샷으로 잰다."""

    FILES = {
        # 같은 `byId` 필드가 두 클래스에 있다. 실제 소스에는 세 클래스에 있다.
        "a/AZone.java": "public class AZone {\n    private Map<String, WasteType> byId = Map.of();\n}\n",
        "a/BZone.java": "public class BZone {\n    private Map<String, WasteType> byId = Map.of();\n}\n",
        "a/WasteType.java": "public class WasteType {\n}\n",
        # 같은 상수 이름이 두 열거형에 있다.
        "a/ModeA.java": "public enum ModeA {\n    PAPER_BASELINE,\n    OTHER_A;\n}\n",
        "a/ModeB.java": "public enum ModeB {\n    PAPER_BASELINE,\n    OTHER_B;\n}\n",
    }

    class Snap:
        def __init__(self, files):
            self.by_path = files

        def has(self, path):
            return path in self.by_path

        def lines(self, path):
            return self.by_path[path].split("\n")

    def snapshot(self):
        return self.Snap(self.FILES)

    def axis_payload(self):
        def cand(name, path):
            return {"name": name, "role": "type", "scope": "simulation_target",
                    "siblings": ["PAPER_BASELINE", "OTHER_" + name[-1]],
                    "declaration_evidence": [{"file_path": path, "start_line": 1,
                                              "end_line": 1,
                                              "quote": "public enum " + name + " {"}]}
        return {"entities": [cand("ModeA", "a/ModeA.java"), cand("ModeB", "a/ModeB.java")]}

    def test_collection_anchors_include_the_owning_type(self):
        out = collection.derive(self.snapshot())
        sets = [e for e in out["entities"] if e["kind"] == "set"]
        self.assertEqual([e["name"] for e in sets], ["byId", "byId"])
        self.assertEqual({e["anchor"]["owner_type"] for e in sets}, {"AZone", "BZone"})

    def test_two_same_named_fields_stay_two_entities(self):
        doc = build({"collection": collection.derive(self.snapshot())})
        self.assertEqual([e["name"] for e in doc["entities"]].count("byId"), 2)

    def test_axis_branches_are_keyed_by_their_enum(self):
        """`PAPER_BASELINE` 이 두 열거형에 있다. 열거형이 열쇠에 들어가야 한다."""
        out = axis.derive(self.axis_payload(), self.snapshot())
        names = [e["name"] for e in out["entities"]]
        self.assertEqual(names.count("PAPER_BASELINE"), 2)
        keys = [assemble.anchor_key(e["anchor"]) for e in out["entities"]]
        self.assertEqual(len(keys), len(set(keys)), "갈래 앵커가 겹친다")

    def test_the_two_paper_baselines_do_not_merge(self):
        doc = build({"axis": axis.derive(self.axis_payload(), self.snapshot())})
        self.assertEqual([e["name"] for e in doc["entities"]].count("PAPER_BASELINE"), 2)


if __name__ == "__main__":
    unittest.main()
