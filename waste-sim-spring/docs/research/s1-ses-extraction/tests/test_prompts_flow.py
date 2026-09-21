# -*- coding: utf-8 -*-
import os
import sys
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
KIT = HERE

from sesx import stages  # noqa: E402
PROMPTS = os.path.join(HERE, "sesx", "prompts", "T2")


def read(name):
    with open(os.path.join(PROMPTS, name), encoding="utf-8") as f:
        return f.read()


class PromptTests(unittest.TestCase):
    def test_e1_이_있고_개체_이름을_금한다(self):
        t = read("e1.md")
        self.assertIn("개체 이름을 적지 않습니다", t)
        self.assertIn("value_sites", t)
        self.assertIn("corrections", t)

    def test_e2_가_확정_개체만_고르게_한다(self):
        t = read("e2.md")
        self.assertIn("site_actors", t)
        self.assertIn("확정 개체 목록에서만", t)
        self.assertIn("별개의 자리", t)

    def test_d_가_축_이름을_묻는다(self):
        self.assertIn("label", read("d.md"))

    def test_d_가_배열_차원_규칙을_적는다(self):
        self.assertIn("같은 선언", read("d.md"))


if __name__ == "__main__":
    unittest.main()


class AttributeListInjection(unittest.TestCase):
    """d 가 속성 이름을 자식으로 적지 않도록, 확정된 상태 목록을 준다.

    q3-control-1 에서 `건물 → [fill, peak]` 이 나왔다. fill 은 b2 가 이미 건물의 상태로
    이어 둔 것이다. d 는 확정 개체 이름 목록만 받고 속성 목록은 못 받았다.
    """

    EV = [{"file_path": "f.java", "start_line": 1, "end_line": 1, "quote": "q"}]

    def payloads(self, **over):
        s = {"state": "fill", "owner_candidate": "건물", "classification": "entity_state",
             "state_evidence": self.EV, "identity_evidence": self.EV,
             "linkage_evidence": self.EV, "consumption_evidence": self.EV}
        s.update(over)
        return {"b2": {"subjects": [s]}}

    def test_a_confirmed_state_is_listed_with_its_owner(self):
        self.assertEqual(stages.attribute_names(self.payloads()), [("fill", "건물")])

    def test_a_state_missing_one_evidence_is_not_listed(self):
        """조립기가 속성으로 받지 않을 것을 '쓰지 말라'고 하면 거짓말이 된다."""
        p = self.payloads()
        del p["b2"]["subjects"][0]["consumption_evidence"]
        self.assertEqual(stages.attribute_names(p), [])

    def test_a_value_is_not_listed(self):
        self.assertEqual(stages.attribute_names(self.payloads(classification="value")), [])

    def test_the_d_stage_head_carries_the_list(self):
        head, _ = stages._context_block("d", self.payloads(), 120000)
        self.assertIn("fill → 건물 의 상태", head)
        self.assertIn("members", head)

    def test_stages_that_do_not_compose_are_left_alone(self):
        head, _ = stages._context_block("c", self.payloads(), 120000)
        self.assertNotIn("이미 어떤 개체의", head)

    def test_the_prompt_states_the_level_rule(self):
        text = stages._read(os.path.join(KIT, "sesx", "prompts", stages.PROMPT_FILE["d"]))
        self.assertIn("자식은 개체여야 합니다", text)
        self.assertIn("다시 자기 상태를 가질 수 있는가", text)
