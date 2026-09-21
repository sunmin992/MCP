# -*- coding: utf-8 -*-
"""열거형 축에서 SPEC 을 만든다. 모델이 참여하지 않는다.

왜 코드가 하는가. `d` 단계에 구성 관계를 부탁하는 길은 세 번 막혔다 — 확정 개체 이름
목록(q3-flow-2), 확정 속성 목록(q3-control-2), 클래스 목록 금지(q3-decl-1) 가 모두
무시당했다. 실행 10회 동안 분해 확정은 한 번도 0 을 넘지 못했다.

반면 `n` 단계가 낸 `siblings` 에는 정답지의 SPEC 축이 그대로 들어 있었고, 열거 상수는
스냅샷에서 결정적으로 찾을 수 있다. 그래서 의미 판단("이 열거형은 축이다")만 모델에게
남기고 구조는 코드가 만든다.
"""
from __future__ import annotations

import io
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import assemble, axis, stages  # noqa: E402

TRUCK = "src/main/java/com/wastesim/model/TruckType.java"
OCC = "src/main/java/com/wastesim/model/OccupationType.java"
CHAT = "src/main/java/com/wastesim/model/ChatMessage.java"

FILES = {
    # 전부 대문자 상수 — 흔한 모양
    TRUCK: """package com.wastesim.model;

public enum TruckType {
    LARGE_5TON  ("5톤", 5000.0),
    MEDIUM_2P5T ("2.5톤", 2500.0),
    SMALL_1TON  ("1톤", 1000.0);

    public final String labelKo;
    public final double capacityKg;
}
""",
    # CamelCase 상수 — 이 저장소의 직업 축이 이 모양이다
    OCC: """package com.wastesim.model;

public enum OccupationType {
    BlueCollar ("생산직", 442),
    Student    ("학생", 538),
    Housewife  ("전업주부", 840);

    public final String labelKo;

    public static OccupationType parse(String s) {
        return Student;
    }
}
""",
    # 한 줄짜리 열거형
    CHAT: """package com.wastesim.model;

public class ChatMessage {
    public enum MessageType { USER, BOT, SYSTEM }
    private MessageType type;
}
""",
}


class FakeSnapshot:
    def __init__(self, files):
        self.files = files

    def has(self, path):
        return path in self.files

    def lines(self, path):
        return self.files[path].split("\n")


SNAP = FakeSnapshot(FILES)


def decl(path, line, quote):
    return {"file_path": path, "start_line": line, "end_line": line, "quote": quote}


def candidate(name, path, line, quote, siblings, **over):
    e = {"name": name, "role": "type", "scope": "simulation_target",
         "siblings": list(siblings), "declaration_evidence": [decl(path, line, quote)]}
    e.update(over)
    return e


TRUCK_CAND = candidate("TruckType", TRUCK, 3, "public enum TruckType {",
                       ["LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"])
OCC_CAND = candidate("OccupationType", OCC, 3, "public enum OccupationType {",
                     ["BlueCollar", "Student", "Housewife"])
CHAT_CAND = candidate("MessageType", CHAT, 4,
                      "    public enum MessageType { USER, BOT, SYSTEM }",
                      ["USER", "BOT", "SYSTEM"])


class MemberLookup(unittest.TestCase):
    def test_screaming_case_members_are_found(self):
        site = axis.find_member(SNAP, TRUCK, "MEDIUM_2P5T", 3)
        self.assertEqual(site["start_line"], 5)
        self.assertIn("MEDIUM_2P5T", site["quote"])

    def test_camel_case_members_are_found(self):
        """`index.ENUM_MEMBER` 는 전부 대문자만 본다. 직업 축을 놓쳤다."""
        self.assertEqual(axis.find_member(SNAP, OCC, "BlueCollar", 3)["start_line"], 4)

    def test_members_on_one_line_are_found(self):
        self.assertEqual(axis.find_member(SNAP, CHAT, "BOT", 4)["start_line"], 4)

    def test_a_name_outside_the_constant_list_is_not_matched(self):
        """`parse` 본문의 `return Student;` 를 상수 선언으로 세지 않는다."""
        site = axis.find_member(SNAP, OCC, "Student", 3)
        self.assertEqual(site["start_line"], 5)

    def test_an_invented_member_is_not_found(self):
        self.assertIsNone(axis.find_member(SNAP, TRUCK, "ELECTRIC_3TON", 3))

    def test_a_field_name_is_not_a_member(self):
        self.assertIsNone(axis.find_member(SNAP, TRUCK, "capacityKg", 3))


class Derivation(unittest.TestCase):
    def payload(self, *cands):
        return {"entities": list(cands)}

    def test_the_axis_is_pending_not_a_decomposition(self):
        """코드가 부모를 고르지 않는다 — 루트를 고르지 않는 것과 같은 이유다."""
        out = axis.derive(self.payload(TRUCK_CAND), SNAP)
        self.assertEqual(out["decompositions"], [])
        self.assertEqual(len(out["pending_axes"]), 1)
        ax = out["pending_axes"][0]
        self.assertEqual(ax["axis_label"], "TruckType")
        self.assertEqual(ax["kind"], "SPEC")
        self.assertEqual(ax["members"], ["LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"])
        for name, ev in ax["member_evidence"].items():
            self.assertIn(name, ev[0]["quote"])

    def test_the_axis_itself_does_not_become_an_entity(self):
        """정답지에 `TruckType` 개체는 없다. 축은 분해의 이름이지 노드가 아니다."""
        out = axis.derive(self.payload(TRUCK_CAND), SNAP)
        self.assertEqual([e["name"] for e in out["entities"]],
                         ["LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"])
        self.assertTrue(all(e["kind"] == "type" for e in out["entities"]))
        self.assertTrue(all(e["origin"] == "derived_by_code" for e in out["entities"]))

    def test_an_invented_member_is_held_not_created(self):
        cand = candidate("TruckType", TRUCK, 3, "public enum TruckType {",
                         ["LARGE_5TON", "MEDIUM_2P5T", "ELECTRIC_3TON"])
        out = axis.derive(self.payload(cand), SNAP)
        self.assertEqual(out["pending_axes"][0]["members"],
                         ["LARGE_5TON", "MEDIUM_2P5T"])
        self.assertNotIn("ELECTRIC_3TON", [e["name"] for e in out["entities"]])
        self.assertEqual(out["unresolved_axes"][0]["names"], ["ELECTRIC_3TON"])

    def test_one_branch_is_not_a_choice(self):
        cand = candidate("TruckType", TRUCK, 3, "public enum TruckType {",
                         ["LARGE_5TON", "ELECTRIC_3TON"])
        out = axis.derive(self.payload(cand), SNAP)
        self.assertEqual(out["pending_axes"], [])
        self.assertTrue(any("갈래가" in u["reason"] for u in out["unresolved_axes"]))

    def test_a_non_enum_candidate_is_left_alone(self):
        cand = candidate("CollectionSite", TRUCK, 3, "public class CollectionSite {",
                         ["A", "B"])
        self.assertEqual(axis.derive(self.payload(cand), SNAP)["pending_axes"], [])

    def test_a_non_type_role_is_ignored(self):
        cand = dict(TRUCK_CAND, role="object")
        self.assertEqual(axis.derive(self.payload(cand), SNAP)["pending_axes"], [])

    def test_the_result_is_deterministic(self):
        a = axis.derive(self.payload(TRUCK_CAND, OCC_CAND), SNAP)
        b = axis.derive(self.payload(TRUCK_CAND, OCC_CAND), SNAP)
        self.assertEqual(a, b)


class ReachesTheArtifact(unittest.TestCase):
    """파생된 것이 실제로 분해로 조립되는가 — 10회 실행 동안 분해는 0 이었다."""

    def build(self, payloads):
        return assemble.build(payloads, "snap", "art",
                              {"strategy": "T2-decl", "run_id": "r",
                               "validation_profile": "evidence-v1"}, {},
                              no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)

    def test_the_branches_land_as_entities(self):
        p = {"axis": axis.derive({"entities": [TRUCK_CAND, OCC_CAND]}, SNAP)}
        doc = self.build(p)
        names = [e["name"] for e in doc["entities"]]
        for n in ("LARGE_5TON", "BlueCollar", "Student", "Housewife"):
            self.assertIn(n, names)
        self.assertNotIn("TruckType", names)

    def test_the_axis_lands_as_a_hold_with_its_branches(self):
        p = {"axis": axis.derive({"entities": [TRUCK_CAND, OCC_CAND]}, SNAP)}
        doc = self.build(p)
        held = [u for u in doc["unresolved"] if u["reason_code"] == "parent_undetermined"]
        self.assertEqual(len(held), 2)
        ids = {e["name"]: e["id"] for e in doc["entities"]}
        occ = next(u for u in held if "OccupationType" in u["explanation"])
        self.assertEqual(sorted(occ["item_ids"]),
                         sorted(ids[n] for n in ("BlueCollar", "Student", "Housewife")))
        self.assertIn("검토자가 정한다", occ["resolution_needed"])

    def test_no_decomposition_is_invented(self):
        p = {"axis": axis.derive({"entities": [TRUCK_CAND, OCC_CAND]}, SNAP)}
        self.assertEqual(self.build(p)["decompositions"], [])

    def test_no_branch_entity_is_minted_twice(self):
        p = {"axis": axis.derive({"entities": [TRUCK_CAND]}, SNAP)}
        doc = self.build(p)
        names = [e["name"] for e in doc["entities"]]
        self.assertEqual(len(names), len(set(names)))

    def test_the_derived_names_are_offered_to_later_stages(self):
        p = {"axis": axis.derive({"entities": [TRUCK_CAND]}, SNAP)}
        self.assertIn("LARGE_5TON", stages.entity_names(p))


class PlanWiring(unittest.TestCase):
    def test_axis_is_derived_right_after_the_declaration_stage(self):
        src = stages._read(os.path.join(os.path.dirname(HERE), "sesx", "stages.py"))
        self.assertIn('payloads["axis"] = axis.derive(', src)

    def test_the_model_is_not_asked_for_axes(self):
        """`axis` 는 프롬프트가 없는 단계다 — 모델이 참여하지 않는다."""
        self.assertNotIn("axis", stages.PROMPT_FILE)
        for plan in stages.PLANS.values():
            self.assertNotIn("axis", plan)




class RelationStagesDoNotInvent(unittest.TestCase):
    """개체를 만드는 단계와 관계만 맺는 단계를 가른다.

    q3-decl-2 에서 d 가 개체 69개를 지어냈다 — `Node_A`~`Node_Z` · `T1`~`T26` · `'0'`~`'11'`.
    근거 410건 중 실패는 6건뿐이었다. 존재하는 줄을 인용하는 것은 쉬우므로 근거 검사가
    막지 못한다. 막는 자리는 여기다.
    """

    EV = [{"file_path": "f.java", "start_line": 1, "end_line": 1, "quote": "q"}]

    def build(self, payloads):
        return assemble.build(payloads, "snap", "art",
                              {"strategy": "T2-decl", "run_id": "r",
                               "validation_profile": "evidence-v1"}, {},
                              no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)

    def invented(self):
        return {"axis": axis.derive({"entities": [TRUCK_CAND]}, SNAP),
                "d": {"decompositions": [{
                    "kind": "MULTI", "parent": "LARGE_5TON", "label": "지어낸 것",
                    "members": ["Node_A", "Node_B"],
                    "member_evidence": {"Node_A": self.EV, "Node_B": self.EV},
                    "evidence": self.EV}]}}

    def test_multi_children_are_never_minted(self):
        self.assertEqual(assemble.NO_MINT_KINDS, ("MULTI",))

    def test_spec_children_are_still_allowed(self):
        """정답지의 SPEC 자식(생산직·5톤 차량)은 그 자리에서 처음 개체가 된다."""
        self.assertNotIn("SPEC", assemble.NO_MINT_KINDS)

    def test_d_cannot_mint_a_member_entity(self):
        doc = self.build(self.invented())
        self.assertNotIn("Node_A", [e["name"] for e in doc["entities"]])

    def test_what_it_tried_to_make_is_kept_for_review(self):
        doc = self.build(self.invented())
        held = [u for u in doc["unresolved"] if u.get("origin_stage") == "d"]
        self.assertEqual(len(held), 1)
        self.assertIn("Node_A", str(held[0]["origin_raw"]))

    def test_the_derived_branches_survive_untouched(self):
        """axis 는 개체를 만드는 쪽이다. 이 변경에 영향받지 않아야 한다."""
        doc = self.build(self.invented())
        names = [e["name"] for e in doc["entities"]]
        for n in ("LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"):
            self.assertIn(n, names)

    def test_a_spec_child_is_still_minted(self):
        """대조 — MULTI 만 막는다. SPEC 까지 막으면 이 시험이 실패한다."""
        p = dict(self.invented())
        p["d"] = {"decompositions": [dict(p["d"]["decompositions"][0], kind="SPEC")]}
        doc = self.build(p)
        self.assertIn("Node_A", [e["name"] for e in doc["entities"]])


if __name__ == "__main__":
    unittest.main()
