# -*- coding: utf-8 -*-
"""템플릿 후보 생성기의 인수 시험.

지키는 것 둘. **요소 하나당 무조건 작업 하나가 아니다** — 처분이 근거에서 나온다.
그리고 **확인 못 한 슬롯은 빈칸이다** — 지어내지 않는다.
"""
import unittest

from sesx import template


def artifact():
    """합성 SES 하나. 시범 셋이 앉는 자리를 그대로 담았다."""
    return {
        "entities": [
            {"id": "E1", "name": "수거차량", "kind": "stateful"},
            {"id": "E2", "name": "수거차량 집합", "kind": "set"},
            {"id": "E3", "name": "5톤 차량", "kind": "type"},
            {"id": "E4", "name": "2.5톤 차량", "kind": "type"},
            {"id": "E5", "name": "교통 구역", "kind": "stateful"},
        ],
        "attributes": [
            {"id": "A1", "entity_id": "E1", "name": "배출간격_자리표시", "evidence_ids": []},
            {"id": "A2", "entity_id": "E1", "name": "배차간격", "evidence_ids": ["EV1"]},
        ],
        "decompositions": [
            {"id": "D1", "kind": "SPEC", "parent_entity_id": "E1",
             "members": [{"entity_id": "E3"}, {"entity_id": "E4"}],
             "selection": {"symbol": "TruckType", "description": "열거형에서 하나를 고른다"},
             "activation_id": None, "evidence_ids": ["EV2"]},
            {"id": "D2", "kind": "MULTI", "parent_entity_id": "E2",
             "members": [{"entity_id": "E1"}], "selection": None,
             "multiplicity": {"count_expression": "numTrucks", "minimum": 1, "maximum": 26},
             "activation_id": None, "evidence_ids": ["EV3"]},
            {"id": "D3", "kind": "ASPECT", "parent_entity_id": "E2",
             "members": [{"entity_id": "E1"}], "selection": None,
             "activation_id": None, "evidence_ids": ["EV4"]},
        ],
        "couplings": [
            {"id": "C1", "source": {"entity_id": "E5"}, "target": {"entity_id": "E1"},
             "activation_id": "AC1", "evidence_ids": ["EV5"]},
        ],
        "evidence": [
            {"id": "EV1", "file_path": "SimulationConfig.java", "start_line": 208,
             "end_line": 208, "symbol": "dispatchIntervalMinutes", "quote": "…"},
            {"id": "EV3", "file_path": "SimulationConfig.java", "start_line": 139,
             "end_line": 139, "symbol": "numTrucks", "quote": "…"},
        ],
        "activation": [
            {"id": "AC1", "state": "known", "coverage": "complete",
             "applies_to": {"kind": "coupling", "target_id": "C1"},
             "clauses": [{"expression": "trafficMode == APPLY", "symbol": "trafficMode"}],
             "evidence_ids": ["EV6"]},
        ],
    }


def binding(field, **over):
    """근거 묶음 하나의 최소형. 시험이 필요한 자리만 채운다."""
    base = {
        "binding_id": "BD-" + field, "config_field": field, "answer_field": None,
        "owner_type": "SimulationConfig", "declared_type": "int",
        "anchor": {"file_path": "C.java", "owner_type": "SimulationConfig", "symbol": field},
        "evidence": {k: {"found": False, "sites": [], "evidence_ids": [],
                         "scanned": "시험", "detail": ""} for k in template.binding_kinds()},
        "unit": None, "range": {"min": None, "max": None, "evidence_ids": [], "source": None},
        "default": {"value": None, "site": None}, "depends_on": [],
        "ses_link": {"state": "unlinked", "point_id": None, "entity_candidate": None,
                     "entity_candidate_name": None, "rule": None, "why": None,
                     "origin": "derived"},
    }
    base["evidence"]["enum_values"]["values"] = []
    base["evidence"]["enum_values"]["enum_type"] = None
    base.update(over)
    return base


def found(b, kind, **extra):
    b["evidence"][kind].update({"found": True, "sites": [{"quote": kind}],
                                "evidence_ids": [b["binding_id"] + "-" + kind]})
    b["evidence"][kind].update(extra)
    return b


class TaskKinds(unittest.TestCase):
    """SES 요소마다 작업 후보 하나. ASPECT 도 낸다 — 준비 여부를 확인하는 작업이다."""

    @classmethod
    def setUpClass(cls):
        cls.draft = template.draft(artifact())
        cls.by_point = {t["point_id"]: t for t in cls.draft["tasks"]}

    def test_spec_becomes_a_choice(self):
        t = self.by_point["spec:수거차량:TruckType"]
        self.assertEqual("choose_alternative", t["kind"])
        self.assertEqual(["5톤 차량", "2.5톤 차량"], t["slots"]["options"])

    def test_multi_becomes_a_count(self):
        self.assertEqual("decide_count", self.by_point["multi:수거차량 집합"]["kind"])

    def test_attribute_becomes_a_value(self):
        self.assertEqual("provide_value", self.by_point["attr:수거차량:배차간격"]["kind"])

    def test_aspect_becomes_a_readiness_check(self):
        self.assertEqual("check_parts_ready", self.by_point["aspect:수거차량 집합:D3"]["kind"])

    def test_coupling_becomes_a_link_check(self):
        self.assertEqual("confirm_link", self.by_point["coupling:교통 구역->수거차량"]["kind"])

    def test_activation_becomes_a_needed_check(self):
        t = self.by_point["active:C1"]
        self.assertEqual("check_needed", t["kind"])
        self.assertEqual("trafficMode == APPLY", t["slots"]["applies_when"])

    def test_the_options_of_a_spec_come_from_the_tree(self):
        """허용값은 트리의 자식 이름이다. 다른 데서 베껴 오지 않는다."""
        self.assertEqual(["5톤 차량", "2.5톤 차량"],
                         self.by_point["spec:수거차량:TruckType"]["slots"]["options"])


class Disposition(unittest.TestCase):
    """처분은 근거에서 나온다. 가리지 못하면 unknown 이다."""

    def _draft(self, bindings):
        d = template.draft(artifact(), bindings)
        return {t["point_id"]: t for t in d["tasks"]}

    def test_without_a_binding_the_disposition_is_unknown(self):
        t = self._draft([])["attr:수거차량:배차간격"]
        self.assertEqual("unknown", t["disposition"])

    def test_a_conversion_site_means_the_user_decides(self):
        b = found(binding("dispatchIntervalMinutes", answer_field="dispatchIntervalMinutes"),
                  "conversion")
        t = self._draft([b])["attr:수거차량:배차간격"]
        self.assertEqual("user_decides", t["disposition"])
        self.assertTrue(t["disposition_why"])

    def test_read_without_write_means_the_server_computes_it(self):
        b = found(found(binding("x"), "read_site"), "write_site")
        b["evidence"]["write_site"].update({"found": False, "sites": [], "evidence_ids": []})
        self.assertEqual("server_computes", template.disposition(b)[0])

    def test_a_default_without_a_conversion_applies_a_default(self):
        b = binding("x")
        b["default"] = {"value": "1", "site": {"quote": "int x = 1;"}}
        self.assertEqual("default_applies", template.disposition(b)[0])

    def test_the_selection_can_turn_a_task_off(self):
        """교통을 끄면 그 결합을 확인하는 작업은 지금 필요하지 않다."""
        tasks = {t["point_id"]: t for t in
                 template.draft(artifact(), [], selection={"trafficMode": "NONE"})["tasks"]}
        self.assertEqual("not_needed_now", tasks["coupling:교통 구역->수거차량"]["disposition"])

    def test_a_matching_selection_leaves_the_task_on(self):
        tasks = {t["point_id"]: t for t in
                 template.draft(artifact(), [], selection={"trafficMode": "APPLY"})["tasks"]}
        self.assertNotEqual("not_needed_now",
                            tasks["coupling:교통 구역->수거차량"]["disposition"])


class Slots(unittest.TestCase):
    """확인 못 한 슬롯은 빈칸이다. 그 빈칸이 제공자의 일이 된다."""

    def test_unconfirmed_slots_are_blank(self):
        t = {x["point_id"]: x for x in template.draft(artifact())["tasks"]}["attr:수거차량:배차간격"]
        for slot in ("answer_shape", "check", "default_rule", "delivers_to"):
            self.assertIsNone(t["slots"][slot], slot)

    def test_delivers_to_names_the_config_field_not_the_answer_field(self):
        """설정에 반영할 자리는 설정 필드다. 답변 필드와 다를 수 있다."""
        b = found(binding("numTrucks", answer_field="truckCount"), "write_site")
        tasks = {x["point_id"]: x for x in template.draft(artifact(), [b])["tasks"]}
        slots = tasks["multi:수거차량 집합"]["slots"]
        self.assertEqual("numTrucks", slots["delivers_to"])
        self.assertEqual("truckCount", slots["asks_as"])

    def test_the_check_slot_carries_the_validation_evidence(self):
        b = found(binding("dispatchIntervalMinutes"), "validation")  # 근거의 symbol 로 붙는다
        b["range"] = {"min": 0, "max": 1439, "evidence_ids": ["BD-x-validation"],
                      "source": "validation"}
        tasks = {x["point_id"]: x for x in template.draft(artifact(), [b])["tasks"]}
        check = tasks["attr:수거차량:배차간격"]["slots"]["check"]
        self.assertEqual(0, check["min"])
        self.assertEqual(1439, check["max"])
        self.assertTrue(check["evidence_ids"])

    def test_a_binding_matches_by_the_evidence_symbol_not_by_name(self):
        """SES 쪽 이름은 한글이다. 붙는 자리는 근거가 적은 코드 기호다."""
        b = found(binding("dispatchIntervalMinutes"), "conversion")
        tasks = {x["point_id"]: x for x in template.draft(artifact(), [b])["tasks"]}
        self.assertEqual("BD-dispatchIntervalMinutes",
                         tasks["attr:수거차량:배차간격"]["binding_id"])
        self.assertIsNone(tasks["attr:수거차량:배출간격_자리표시"]["binding_id"])

    def test_a_binding_that_matches_nothing_is_reported_not_dropped(self):
        """조용한 삭제가 없다(규칙 3)."""
        d = template.draft(artifact(), [binding("landlordThreshold")])
        self.assertIn("landlordThreshold",
                      [b["config_field"] for b in d["unmapped_bindings"]])


if __name__ == "__main__":
    unittest.main()
