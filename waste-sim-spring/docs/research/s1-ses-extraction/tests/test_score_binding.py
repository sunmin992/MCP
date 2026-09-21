# -*- coding: utf-8 -*-
"""연결 채점기의 인수 시험.

재는 것은 **제공자 부담**이다. 시스템이 근거와 함께 채운 칸이 몇이고, 제공자가 보충할
칸이 몇이며, 손으로 쓴 표와 어긋난 것이 몇인가.

**하나의 '정확도'로 합치지 않는다.** `score_roles.py` 가 이미 그 규칙을 쓴다.
"""
import unittest

import score_binding

PILOT = ("truckType", "truckCount", "dispatchIntervalMinutes")


class HandTable(unittest.TestCase):
    """손으로 쓴 대응표를 읽는다. 이것이 대조 상대다."""

    @classmethod
    def setUpClass(cls):
        cls.hand = score_binding.hand_written()

    def test_the_three_pilot_fields_are_in_the_hand_table(self):
        by_answer = {h["answer_field"]: h for h in self.hand}
        for field in PILOT:
            self.assertIn(field, by_answer, field)

    def test_the_hand_table_carries_the_point_id(self):
        by_answer = {h["answer_field"]: h for h in self.hand}
        self.assertEqual("spec:수거차량:차종 축", by_answer["truckType"]["point_id"])
        self.assertEqual("multi:수거차량 집합", by_answer["truckCount"]["point_id"])
        self.assertEqual("attr:수거차량:배차간격",
                         by_answer["dispatchIntervalMinutes"]["point_id"])


class Scoring(unittest.TestCase):

    def _draft(self):
        return {"tasks": [
            {"point_id": "attr:수거차량:배차간격", "kind": "provide_value",
             "disposition": "user_decides", "binding_id": "BD-1",
             "slots": {"asks_as": "dispatchIntervalMinutes",
                       "delivers_to": "dispatchIntervalMinutes",
                       "check": {"min": 0, "max": 1439}, "options": None,
                       "answer_shape": "int", "applies_when": None,
                       "after": None, "default_rule": None, "decides": {}},
             "evidence_ids": ["EV1"]},
            {"point_id": "spec:수거차량:TruckType", "kind": "choose_alternative",
             "disposition": "user_decides", "binding_id": "BD-2",
             "slots": {"asks_as": "truckType", "delivers_to": "truckType",
                       "options": ["LARGE_5TON"], "check": None, "answer_shape": "String",
                       "applies_when": None, "after": None, "default_rule": None,
                       "decides": {}},
             "evidence_ids": ["EV2"]}]}

    def test_a_field_the_chain_reached_counts_as_covered(self):
        s = score_binding.score(self._draft())
        self.assertEqual("covered", s["pilot"]["dispatchIntervalMinutes"]["answer_field"])

    def test_a_field_the_chain_never_reached_counts_as_missing(self):
        s = score_binding.score(self._draft())
        self.assertEqual("missing", s["pilot"]["truckCount"]["answer_field"])

    def test_a_different_point_id_is_reported_not_hidden(self):
        """축 이름이 코드 기호와 한글로 갈린다. 조용히 맞추지 않고 드러낸다."""
        s = score_binding.score(self._draft())
        row = s["pilot"]["truckType"]
        self.assertEqual("differs", row["point_id"])
        self.assertEqual("spec:수거차량:TruckType", row["ours"])
        self.assertEqual("spec:수거차량:차종 축", row["hand"])

    def test_filled_and_blank_are_counted_apart(self):
        s = score_binding.score(self._draft())
        self.assertNotIn("accuracy", s)
        self.assertGreater(s["slots"]["filled_with_evidence"], 0)
        self.assertGreater(s["slots"]["blank"], 0)
        self.assertEqual(s["slots"]["total"],
                         s["slots"]["filled_with_evidence"] + s["slots"]["blank"])


if __name__ == "__main__":
    unittest.main()
