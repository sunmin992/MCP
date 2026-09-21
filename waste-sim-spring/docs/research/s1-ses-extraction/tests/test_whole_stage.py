# -*- coding: utf-8 -*-
"""전체(whole) 후보 단계 `w`.

표적은 측정된 손실이다. q3-flow-2 에서 `unknown_reference` 29건 중 **24건**이
`entity = "전체 시뮬레이션"` 하나를 가리켰다. 확정 개체 8개에 상위 전체가 없어서
c 단계가 전체에 매단 속성이 통째로 보류된 것이다.

이 단계는 **루트를 고르지 않는다.** 후보를 낼 뿐이고, 루트는 PES 요청이 정한다.
"""
from __future__ import annotations

import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import assemble, composition, stages  # noqa: E402

ENGINE = "src/main/java/com/wastesim/simulation/SimulationEngine.java"


def ev(quote, line=100, path=ENGINE):
    return [{"file_path": path, "start_line": line, "end_line": line,
             "quote": quote, "symbol": None}]


def whole(name="장량동 수거 시뮬레이션", **over):
    e = {"name": name, "kind": "whole", "scope": "simulation_target",
         "why_whole": "설정·지점·차량이 여기서 함께 놓인다",
         "assembly_evidence": ev("engine = new SimulationEngine(cfg, sites, trucks);", 88),
         "evidence": ev("public class SimulationEngine {", 40)}
    e.update(over)
    return e


def assemble_payloads(payloads, strategy="T2-whole"):
    return assemble.build(payloads, "snap", "art",
                          {"strategy": strategy, "run_id": "r"}, {},
                          no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)


class WholeContract(unittest.TestCase):
    def test_assembly_evidence_is_required(self):
        e = whole(); del e["assembly_evidence"]
        self.assertIn("조립 자리", composition.whole_error(e))

    def test_support_software_cannot_be_a_whole(self):
        self.assertIn("실행 지원", composition.whole_error(
            whole("이벤트 우선순위 큐", scope="support_software")))

    def test_kind_must_say_whole(self):
        self.assertIn("kind", composition.whole_error(whole(kind="stateful")))

    def test_a_complete_candidate_passes(self):
        self.assertIsNone(composition.whole_error(whole()))


class WholeAssembly(unittest.TestCase):
    def test_a_whole_candidate_becomes_an_entity(self):
        doc = assemble_payloads({"w": {"entities": [whole()]}})
        self.assertEqual([e["name"] for e in doc["entities"]], ["장량동 수거 시뮬레이션"])
        self.assertEqual(doc["entities"][0]["kind"], "whole")

    def test_a_candidate_without_assembly_evidence_is_held_not_dropped(self):
        e = whole(); del e["assembly_evidence"]
        doc = assemble_payloads({"w": {"entities": [e]}})
        self.assertEqual(doc["entities"], [])
        self.assertEqual(len(doc["unresolved"]), 1)
        self.assertEqual(doc["unresolved"][0]["origin_raw"]["name"], e["name"])
        self.assertIn("조립 자리", doc["unresolved"][0]["explanation"])

    def test_an_event_queue_is_held_with_its_reason_preserved(self):
        doc = assemble_payloads({"w": {"entities": [
            whole("이벤트 우선순위 큐", scope="support_software")]}})
        self.assertEqual(doc["entities"], [])
        self.assertIn("실행 지원", doc["unresolved"][0]["explanation"])
        self.assertTrue(doc["provenance"])

    def test_assembly_evidence_travels_into_the_artifact(self):
        doc = assemble_payloads({"w": {"entities": [whole()]}})
        quotes = {e["quote"] for e in doc["evidence"]}
        self.assertIn("engine = new SimulationEngine(cfg, sites, trucks);", quotes)

    def test_several_wholes_are_all_kept(self):
        """후보를 하나로 줄이지 않는다 — 고르는 것은 PES 요청이다."""
        doc = assemble_payloads({"w": {"entities": [
            whole("장량동 수거 시뮬레이션"), whole("생활쓰레기 수거 모델")]}})
        self.assertEqual(len(doc["entities"]), 2)
        self.assertIsNone(doc["structure"]["selected_root_id"])


class WholeReachesLaterStages(unittest.TestCase):
    """표적 — 전체에 매단 속성이 보류되지 않고 붙는가."""

    def test_the_whole_is_offered_to_later_stages_by_name(self):
        names = stages.entity_names({"w": {"entities": [whole()]}})
        self.assertIn("장량동 수거 시뮬레이션", names)

    def test_a_malformed_whole_is_not_offered(self):
        e = whole(); del e["assembly_evidence"]
        self.assertEqual(stages.entity_names({"w": {"entities": [e]}}), [])

    def test_an_attribute_owned_by_the_whole_is_no_longer_held(self):
        payloads = {
            "w": {"entities": [whole("전체 시뮬레이션")]},
            "c": {"attributes": [{
                "entity": "전체 시뮬레이션", "name": "collectedWasteKg",
                "value_type": "double",
                "unit": {"value": "kg", "evidence": ev("double collectedWasteKg = 0.0;", 392)},
                "default": {"value": "0.0", "evidence": ev("double collectedWasteKg = 0.0;", 392)},
                "range": {"value": "unknown", "evidence": []},
                "evidence": ev("double collectedWasteKg = 0.0;", 392)}]},
        }
        doc = assemble_payloads(payloads)
        self.assertEqual(len(doc["attributes"]), 1, doc["unresolved"])
        owner = next(e for e in doc["entities"] if e["id"] == doc["attributes"][0]["entity_id"])
        self.assertEqual(owner["name"], "전체 시뮬레이션")
        self.assertEqual([u for u in doc["unresolved"]
                          if u["reason_code"] == "unknown_reference"], [])

    def test_without_the_whole_stage_the_same_attribute_is_held(self):
        """대조 — 이 시험이 실패하면 표적을 잘못 짚은 것이다."""
        payloads = {"c": {"attributes": [{
            "entity": "전체 시뮬레이션", "name": "collectedWasteKg",
            "value_type": "double",
            "unit": {"value": "kg", "evidence": []},
            "default": {"value": "0.0", "evidence": []},
            "range": {"value": "unknown", "evidence": []},
            "evidence": ev("double collectedWasteKg = 0.0;", 392)}]}}
        doc = assemble_payloads(payloads)
        self.assertEqual(doc["attributes"], [])
        self.assertEqual(doc["unresolved"][0]["reason_code"], "unknown_reference")


class PlanWiring(unittest.TestCase):
    def test_the_plan_puts_w_between_parts_and_relations(self):
        plan = stages.PLANS["T2-whole"]
        self.assertLess(plan.index("b2"), plan.index("w"))
        self.assertLess(plan.index("w"), plan.index("c"))
        self.assertLess(plan.index("w"), plan.index("d"))

    def test_w_is_not_a_root_stage(self):
        """루트 단계가 아니다. 루트를 고르는 경로가 이 단계에 없다."""
        self.assertNotIn("w", stages.ROOT_STAGES)

    def test_the_prompt_forbids_execution_machinery(self):
        text = stages._read(os.path.join(os.path.dirname(HERE), "sesx", "prompts",
                                         stages.PROMPT_FILE["w"]))
        for banned in ("이벤트 큐", "컨트롤러", "루트가 아닙니다"):
            self.assertIn(banned, text)




class StrictProfile(unittest.TestCase):
    """알 수 없는 출력 필드를 버리지 않는다 — q3-whole-2 에서 실제로 잃을 뻔했다."""

    def test_the_whole_plan_gets_the_strict_profile(self):
        self.assertIn("T2-whole", stages.EVIDENCE_PLANS)

    def test_every_evidence_plan_exists(self):
        for name in stages.EVIDENCE_PLANS:
            self.assertIn(name, stages.PLANS, name)

    def test_a_derailed_payload_is_held_with_its_text(self):
        """모델이 package.json 모양으로 답해도 흔적이 남아야 한다."""
        junk = {"name": "waste-sim", "dependencies": {"x": "1.0"}}
        doc = assemble.build({"b2": junk}, "snap", "art",
                             {"strategy": "T2-whole", "run_id": "r",
                              "validation_profile": "evidence-v1"}, {},
                             no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)
        self.assertEqual(doc["entities"], [])
        self.assertTrue(doc["unresolved"], "알 수 없는 필드가 조용히 사라졌다")
        kept = json.dumps(doc["unresolved"], ensure_ascii=False)
        self.assertIn("waste-sim", kept)

    def test_the_legacy_profile_is_what_lost_it(self):
        """대조 — 이 시험이 실패하면 원인을 잘못 짚은 것이다."""
        junk = {"name": "waste-sim", "dependencies": {"x": "1.0"}}
        doc = assemble.build({"b2": junk}, "snap", "art",
                             {"strategy": "T2-whole", "run_id": "r",
                              "validation_profile": "legacy"}, {},
                             no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)
        self.assertEqual(doc["unresolved"], [])




class ControlPlan(unittest.TestCase):
    """대조군은 w 하나만 달라야 한다. 프롬프트 계열까지 다르면 비교가 성립하지 않는다."""

    def test_the_control_differs_from_the_whole_plan_only_by_w(self):
        whole = list(stages.PLANS["T2-whole"])
        control = list(stages.PLANS["T2-whole-control"])
        self.assertEqual([s for s in whole if s != "w"], control)

    def test_both_use_the_same_prompt_family(self):
        for name in ("T2-whole", "T2-whole-control"):
            self.assertNotIn(name, stages.EVIDENCE_PROMPT_PLANS, name)

    def test_t2_evidence_is_not_a_valid_control(self):
        """왜 T2-evidence 를 쓰면 안 되는지를 고정한다 — 단계는 같고 프롬프트가 다르다."""
        self.assertEqual(list(stages.PLANS["T2-evidence"]),
                         list(stages.PLANS["T2-whole-control"]))
        self.assertIn("T2-evidence", stages.EVIDENCE_PROMPT_PLANS)
        self.assertNotIn("T2-whole", stages.EVIDENCE_PROMPT_PLANS)


if __name__ == "__main__":
    unittest.main()
