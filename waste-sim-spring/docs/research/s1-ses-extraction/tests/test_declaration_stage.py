# -*- coding: utf-8 -*-
"""선언 기반 개체 단계 `n`.

왜 만들었나. q3-control-2 의 개체 5개는 `건물-쓰레기 종류 조합 · 운행 · 직업 · 요일 · 월`
— 전부 배열의 **색인 축**이었다. 지금 단계 구성이 상태 중심이기 때문이다(a 는 무엇이
달라지는가, b2 는 그 상태가 누구 것인가). 정답지의 유형·집합·특수화는 갱신되는 줄이
아니라 **선언된 줄**에 있고, 그것을 읽는 단계가 없었다.

무엇을 막나. "클래스 목록을 그대로 개체 목록으로 바꾸지 마라"가 제약이다. 프롬프트로는
막히지 않는 것이 두 번 확인됐으므로(확정 이름 목록도, 확정 속성 목록도 무시당했다)
**역할마다 선언의 모양을 코드가 요구한다.**
"""
from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import assemble, composition, stages  # noqa: E402


def ev(quote, path="src/main/java/com/wastesim/model/OccupationType.java", line=12):
    return [{"file_path": path, "start_line": line, "end_line": line,
             "quote": quote, "symbol": None}]


def kind_type(name="생산직", **over):
    e = {"name": name, "role": "type", "scope": "simulation_target",
         "why_in_world": "거주민의 직업 축의 한 값이다",
         "siblings": ["학생", "전업주부"],
         "declaration_evidence": ev('PRODUCTION_WORKER("생산직", 0.9),'),
         "evidence": ev('PRODUCTION_WORKER("생산직", 0.9),')}
    e.update(over)
    return e


def kind_set(name="수거지점 집합", **over):
    e = {"name": name, "role": "set", "scope": "simulation_target",
         "why_in_world": "수거지점 여럿을 담는다", "element_type": "CollectionSite",
         "declaration_evidence": ev("private final List<CollectionSite> sites;"),
         "evidence": ev("private final List<CollectionSite> sites;")}
    e.update(over)
    return e


def kind_object(name="수거지점", **over):
    e = {"name": name, "role": "object", "scope": "simulation_target",
         "why_in_world": "쓰레기가 쌓이는 자리다",
         "declaration_evidence": ev("public class CollectionSite {"),
         "instantiation_evidence": ev("sites.add(new CollectionSite(id, lat, lon));"),
         "evidence": ev("public class CollectionSite {")}
    e.update(over)
    return e


def build(payloads):
    return assemble.build(payloads, "snap", "art",
                          {"strategy": "T2-decl", "run_id": "r",
                           "validation_profile": "evidence-v1"}, {},
                          no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)


class Contract(unittest.TestCase):
    def test_the_three_roles_pass_when_complete(self):
        for cand in (kind_type(), kind_set(), kind_object()):
            self.assertIsNone(composition.declaration_error(cand), cand["role"])

    def test_an_update_line_cannot_declare_a_type(self):
        """갱신되는 줄을 인용해 유형을 주장할 수 없다 — 그게 상태 중심 편향의 자리다."""
        e = kind_type(declaration_evidence=ev("fill[b][t] += amount;"))
        self.assertIn("모양이 아니다", composition.declaration_error(e))

    def test_a_class_declaration_alone_is_not_an_object(self):
        """클래스 목록 복사를 막는 자리."""
        e = kind_object()
        del e["instantiation_evidence"]
        self.assertIn("생성·등록 근거", composition.declaration_error(e))

    def test_a_framework_class_without_construction_is_rejected(self):
        e = kind_object("교통자료 서비스",
                        declaration_evidence=ev("public class TrafficDataService {"),
                        instantiation_evidence=ev("private final ObjectMapper mapper;"))
        self.assertIn("생성·등록 모양이 아니다", composition.declaration_error(e))

    def test_a_type_without_siblings_is_not_an_axis(self):
        self.assertIn("형제", composition.declaration_error(kind_type(siblings=[])))

    def test_a_set_must_name_its_element_type(self):
        e = kind_set()
        del e["element_type"]
        self.assertIn("원소 유형", composition.declaration_error(e))

    def test_support_software_is_rejected(self):
        self.assertIn("실행 지원",
                      composition.declaration_error(kind_object(scope="support_software")))


class Assembly(unittest.TestCase):
    def test_each_role_lands_with_the_right_entity_kind(self):
        doc = build({"n": {"entities": [kind_type(), kind_set(), kind_object()]}})
        got = {e["name"]: e["kind"] for e in doc["entities"]}
        self.assertEqual(got, {"생산직": "type", "수거지점 집합": "set", "수거지점": "stateful"})

    def test_a_rejected_candidate_is_held_with_its_text(self):
        doc = build({"n": {"entities": [kind_type(declaration_evidence=ev("x = 1;"))]}})
        self.assertEqual(doc["entities"], [])
        self.assertEqual(len(doc["unresolved"]), 1)
        self.assertEqual(doc["unresolved"][0]["origin_raw"]["name"], "생산직")
        self.assertIn("모양이 아니다", doc["unresolved"][0]["explanation"])

    def test_declaration_and_instantiation_evidence_both_survive(self):
        doc = build({"n": {"entities": [kind_object()]}})
        quotes = {e["quote"] for e in doc["evidence"]}
        self.assertIn("public class CollectionSite {", quotes)
        self.assertIn("sites.add(new CollectionSite(id, lat, lon));", quotes)

    def test_a_declared_type_can_own_attributes_from_c(self):
        """선언 개체가 뒤 단계에 실제로 쓰이는가."""
        doc = build({
            "n": {"entities": [kind_object()]},
            "c": {"attributes": [{
                "entity": "수거지점", "name": "currentLoadKg", "value_type": "double",
                "unit": {"value": "kg", "evidence": ev("double currentLoadKg;")},
                "default": {"value": "0.0", "evidence": ev("double currentLoadKg;")},
                "range": {"value": "unknown", "evidence": []},
                "evidence": ev("double currentLoadKg;")}]}})
        self.assertEqual(len(doc["attributes"]), 1, doc["unresolved"])


class PlanWiring(unittest.TestCase):
    def test_n_sits_after_b2_and_before_the_relation_stages(self):
        plan = stages.PLANS["T2-decl"]
        self.assertLess(plan.index("b2"), plan.index("n"))
        self.assertLess(plan.index("n"), plan.index("c"))
        self.assertLess(plan.index("n"), plan.index("d"))

    def test_n_is_told_which_names_are_already_states(self):
        self.assertIn("n", stages.ATTRIBUTE_LIST_STAGES)

    def test_n_gets_the_strict_profile(self):
        self.assertIn("T2-decl", stages.EVIDENCE_PLANS)

    def test_the_prompt_forbids_copying_the_class_list(self):
        text = stages._read(os.path.join(os.path.dirname(HERE), "sesx", "prompts",
                                         stages.PROMPT_FILE["n"]))
        self.assertIn("클래스 목록을 옮기지 마세요", text)
        self.assertIn("클래스가 있다는 것만으로는 개체가 아닙니다", text)

    def test_the_control_plan_is_still_the_same_minus_the_new_stage(self):
        decl = [s for s in stages.PLANS["T2-decl"] if s != "n"]
        self.assertEqual(decl, list(stages.PLANS["T2-whole-control"]))


if __name__ == "__main__":
    unittest.main()
