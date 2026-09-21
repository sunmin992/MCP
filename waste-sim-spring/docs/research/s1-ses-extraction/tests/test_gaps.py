# -*- coding: utf-8 -*-
"""제공자 검토 목록의 인수 시험.

제공자가 처음부터 템플릿을 쓰는 대신 **근거가 있는 초안을 확인하고 부족한 부분만**
채우게 하는 것이 목적이다. 그래서 이 목록이 지켜야 할 것은 셋이다.

  · 채운 칸과 빈 칸을 가른다
  · 사람이 넣은 것을 추출한 것과 갈라 센다(규칙 11)
  · 근거 코드가 바뀌면 **지우지 않고** 재검토로 표시한다
"""
import unittest

from sesx import gaps, template

from tests.test_template import artifact, binding, found


def drafted(bindings=(), selection=None):
    return template.draft(artifact(), bindings, selection)


EVIDENCE_FILES = {
    "EV1": "SimulationConfig.java", "EV2": "TruckType.java",
    "EV3": "SimulationConfig.java", "EV4": "SimulationConfig.java",
    "EV5": "SimulationEngine.java", "EV6": "SimulationEngine.java",
    "BD-dispatchIntervalMinutes-validation": "SimulationConfigValidator.java",
}
DIGESTS = {"SimulationConfig.java": "aaa", "TruckType.java": "bbb",
           "SimulationEngine.java": "ccc", "SimulationConfigValidator.java": "ddd"}


class Rows(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        b = found(binding("dispatchIntervalMinutes", answer_field="dispatchIntervalMinutes"),
                  "conversion")
        found(b, "validation")
        found(b, "write_site")
        b["range"] = {"min": 0, "max": 1439, "source": "validation",
                      "evidence_ids": ["BD-dispatchIntervalMinutes-validation"]}
        cls.rows = gaps.rows(drafted([b]), EVIDENCE_FILES, DIGESTS)
        cls.at = {(r["point_id"], r["slot"]): r for r in cls.rows}

    def test_a_slot_the_code_filled_asks_only_for_meaning_confirmation(self):
        r = self.at[("attr:수거차량:배차간격", "check")]
        self.assertEqual("extracted", r["state"])
        self.assertEqual("의미 확인", r["provider_action"])
        self.assertTrue(r["evidence_ids"])

    def test_an_empty_slot_asks_the_provider_to_supply_it(self):
        r = self.at[("attr:수거차량:배차간격", "applies_when")]
        self.assertEqual("missing", r["state"])
        self.assertEqual("보충", r["provider_action"])

    def test_a_task_with_no_binding_asks_for_an_existing_function(self):
        """설정에 반영할 자리를 못 찾았다 — 기존 기능을 지정해 달라는 줄이다."""
        r = self.at[("coupling:교통 구역->수거차량", "delivers_to")]
        self.assertEqual("unlinked", r["state"])
        self.assertEqual("기존 기능 지정", r["provider_action"])

    def test_rows_only_cover_the_slots_the_task_kind_uses(self):
        """준비 여부를 확인하는 작업에 '허용값'을 물으면 빈칸이 늘기만 한다."""
        slots = {r["slot"] for r in self.rows if r["point_id"] == "aspect:수거차량 집합:D3"}
        self.assertNotIn("options", slots)
        self.assertNotIn("check", slots)

    def test_every_row_carries_the_digest_of_its_evidence(self):
        r = self.at[("attr:수거차량:배차간격", "check")]
        self.assertTrue(r["source_digest"])

    def test_a_blank_row_still_watches_the_code_behind_its_task(self):
        """빈칸도 해시를 진다. 그래야 제공자가 채운 뒤 그 코드가 바뀐 것을 알 수 있다."""
        blank = self.at[("attr:수거차량:배차간격", "applies_when")]
        self.assertIsNone(blank["value"])
        self.assertTrue(blank["source_digest"])

    def test_a_slots_own_evidence_wins_over_the_tasks(self):
        """칸에 근거가 붙어 있으면 그것을 본다 — 검증기가 바뀌면 그 칸만 재검토다."""
        check = self.at[("attr:수거차량:배차간격", "check")]
        self.assertEqual(["BD-dispatchIntervalMinutes-validation"], check["evidence_ids"])
        self.assertNotEqual(check["source_digest"],
                            self.at[("attr:수거차량:배차간격", "applies_when")]["source_digest"])


class ProposedLink(unittest.TestCase):

    def test_a_proposed_link_asks_for_approval_not_confirmation(self):
        b = found(binding("truckType"), "write_site")
        b["ses_link"] = {**b["ses_link"], "state": "proposed", "rule": "type_match",
                         "entity_candidate_name": "TruckType"}
        rows = gaps.rows(template.draft(artifact(), [b]), EVIDENCE_FILES, DIGESTS)
        r = {(x["point_id"], x["slot"]): x for x in rows}[
            ("spec:수거차량:TruckType", "delivers_to")]
        self.assertEqual("proposed", r["state"])
        self.assertEqual("대응 승인", r["provider_action"])


class HumanFills(unittest.TestCase):

    def setUp(self):
        self.rows = gaps.rows(drafted(), EVIDENCE_FILES, DIGESTS)

    def test_a_human_fill_is_stored_apart_from_what_the_code_extracted(self):
        filled = gaps.apply(self.rows, [{"point_id": "attr:수거차량:배차간격",
                                         "slot": "answer_shape", "value": "INTEGER",
                                         "why": "분 단위 정수다"}], reviewer="검토자")
        r = {(x["point_id"], x["slot"]): x for x in filled}[
            ("attr:수거차량:배차간격", "answer_shape")]
        self.assertEqual("human", r["origin"])
        self.assertEqual("INTEGER", r["value"])
        self.assertEqual("검토자", r["reviewer"])
        self.assertEqual("filled", r["state"])

    def test_filling_an_unknown_slot_is_refused_not_silently_added(self):
        with self.assertRaises(ValueError):
            gaps.apply(self.rows, [{"point_id": "attr:수거차량:배차간격",
                                    "slot": "없는슬롯", "value": "x"}], reviewer="검토자")

    def test_the_code_fill_is_not_overwritten_by_a_human_fill(self):
        """사람이 채운 것과 추출한 것을 갈라 센다 — 덮어쓰면 그 구분이 사라진다."""
        rows = gaps.rows(drafted(), EVIDENCE_FILES, DIGESTS)
        before = {(r["point_id"], r["slot"]): r["value"] for r in rows}
        filled = gaps.apply(rows, [{"point_id": "attr:수거차량:배차간격",
                                    "slot": "answer_shape", "value": "INTEGER"}],
                            reviewer="검토자")
        r = {(x["point_id"], x["slot"]): x for x in filled}[
            ("attr:수거차량:배차간격", "answer_shape")]
        self.assertIsNone(before[("attr:수거차량:배차간격", "answer_shape")])
        self.assertEqual("INTEGER", r["value"])


class Staleness(unittest.TestCase):

    def test_a_changed_source_marks_the_row_stale_without_erasing_the_fill(self):
        """사람의 보충을 지우지 않는다. 다시 보라고 표시만 한다."""
        rows = gaps.rows(drafted(), EVIDENCE_FILES, DIGESTS)
        filled = gaps.apply(rows, [{"point_id": "attr:수거차량:배차간격",
                                    "slot": "applies_when", "value": "언제나"}],
                            reviewer="검토자")
        moved = dict(DIGESTS, **{"SimulationConfig.java": "zzz"})
        again = gaps.restale(filled, gaps.rows(drafted(), EVIDENCE_FILES, moved))
        r = {(x["point_id"], x["slot"]): x for x in again}[
            ("attr:수거차량:배차간격", "applies_when")]
        self.assertEqual("언제나", r["value"])
        self.assertEqual("human", r["origin"])
        self.assertEqual("stale", r["state"])
        self.assertEqual("재검토", r["provider_action"])

    def test_an_unchanged_source_stays_filled(self):
        rows = gaps.rows(drafted(), EVIDENCE_FILES, DIGESTS)
        filled = gaps.apply(rows, [{"point_id": "attr:수거차량:배차간격",
                                    "slot": "applies_when", "value": "언제나"}],
                            reviewer="검토자")
        again = gaps.restale(filled, gaps.rows(drafted(), EVIDENCE_FILES, DIGESTS))
        r = {(x["point_id"], x["slot"]): x for x in again}[
            ("attr:수거차량:배차간격", "applies_when")]
        self.assertEqual("filled", r["state"])


class Summary(unittest.TestCase):

    def test_extracted_and_human_are_counted_apart(self):
        rows = gaps.rows(drafted(), EVIDENCE_FILES, DIGESTS)
        filled = gaps.apply(rows, [{"point_id": "attr:수거차량:배차간격",
                                    "slot": "answer_shape", "value": "INTEGER"}],
                            reviewer="검토자")
        s = gaps.summary(filled)
        self.assertEqual(1, s["by_origin"]["human"])
        self.assertEqual(len(rows) - 1, s["by_origin"]["extracted"] + s["by_origin"]["none"])


if __name__ == "__main__":
    unittest.main()
