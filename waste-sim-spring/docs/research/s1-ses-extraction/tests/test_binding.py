# -*- coding: utf-8 -*-
"""실행설정 연결 경로의 인수 시험.

가장 먼저 지키는 것은 **입력 경계**다. 연결 후보는 소비 지점을 봐야 하는데 그 파일이
정답(34문항 카탈로그·손으로 쓴 대응표)과 같은 패키지에 있다. 하나를 열면서 다른 하나를
막는 것이 이 경로의 첫 요구다.
"""
import os
import unittest

import extract
from sesx import input_policy

REPO = extract.REPO
RULES = os.path.join(extract.KIT, "sesx", "rules")

BUILDER = "src/main/java/com/wastesim/subtask/JangnyangScenarioBuilder.java"
CONFIG = "src/main/java/com/wastesim/model/SimulationConfig.java"
VALIDATOR = "src/main/java/com/wastesim/tool/SimulationConfigValidator.java"
TRUCK_TYPE = "src/main/java/com/wastesim/model/TruckType.java"

CATALOG = "src/main/java/com/wastesim/subtask/JangnyangSubtaskCatalog.java"
FIELD_MAPPING = "src/main/java/com/wastesim/ses/SesFieldMapping.java"
TREE = "src/main/java/com/wastesim/ses/JangnyangEntityStructure.java"


class BindingPolicy(unittest.TestCase):
    """P-binding 은 소비 지점을 열고 정답을 막는다."""

    @classmethod
    def setUpClass(cls):
        cls.policy = input_policy.load_policy(os.path.join(RULES, "P-binding.json"))
        cls.decision = input_policy.decide(REPO, cls.policy)

    def test_policy_is_approved(self):
        self.assertEqual([], self.decision["blocks"])
        self.assertTrue(self.decision["approved"])

    def test_consumption_point_is_included(self):
        """대응의 근거는 선언이 아니라 소비 지점이다. 그것이 없으면 이름으로 잇게 된다."""
        for path in (BUILDER, CONFIG, VALIDATOR, TRUCK_TYPE):
            self.assertIn(path, self.decision["included"], path)

    def test_answer_files_are_not_included(self):
        """같은 패키지라도 정답은 들어오지 않는다."""
        for path in (CATALOG, FIELD_MAPPING, TREE):
            self.assertNotIn(path, self.decision["included"], path)

    def test_widening_the_boundary_hits_the_evaluation_net(self):
        """포함 범위를 넓히면 정답이 조용히 들어오는 것이 아니라 차단으로 걸린다."""
        wide = dict(self.policy)
        wide["include_globs"] = list(self.policy["include_globs"]) + [
            "src/main/java/com/wastesim/subtask/**/*.java",
            "src/main/java/com/wastesim/ses/**/*.java"]
        decision = input_policy.decide(REPO, wide)
        blocked = {b["path"] for b in decision["blocks"]
                   if b["code"] == "EVALUATION_ASSET_IN_INPUT"}
        self.assertFalse(decision["approved"])
        for path in (CATALOG, FIELD_MAPPING, TREE):
            self.assertIn(path, blocked, path)

    def test_always_blocked_assets_still_apply(self):
        """experiment_kind 를 바꿔도 참조 SES 는 막힌다 — 그 목록은 코드에 있다."""
        wide = dict(self.policy)
        wide["inventory_globs"] = ["docs/research/s1-ses-extraction/*.json"]
        wide["include_globs"] = ["docs/research/s1-ses-extraction/*.json"]
        decision = input_policy.decide(REPO, wide)
        blocked = {b["path"] for b in decision["blocks"]
                   if b["code"] == "EVALUATION_ASSET_IN_INPUT"}
        self.assertIn("docs/research/s1-ses-extraction/reference-ses.json", blocked)
        self.assertIn("docs/research/s1-ses-extraction/eval-roles.json", blocked)



def _snapshot():
    """P-binding 경계로 실제 스냅샷 하나. 시범 세 필드를 진짜 코드에서 잰다."""
    import tempfile
    from sesx import snapshot as snap_mod
    policy = input_policy.load_policy(os.path.join(RULES, "P-binding.json"))
    decision = input_policy.decide(REPO, policy)
    if decision["blocks"]:
        raise AssertionError(decision["blocks"])
    out = os.path.join(tempfile.mkdtemp(prefix="bindsnap-"), "snapshot")
    snap_mod.materialize(REPO, decision, out, "snap-binding-test")
    return snap_mod.Snapshot(out)


class BindingEvidence(unittest.TestCase):
    """설정 필드마다 근거 묶음. 못 본 것과 없는 것을 가른다."""

    @classmethod
    def setUpClass(cls):
        from sesx import binding
        cls.binding = binding
        cls.snapshot = _snapshot()
        cls.bindings = binding.collect(cls.snapshot)
        cls.by_field = {b["config_field"]: b for b in cls.bindings}

    def test_the_three_pilot_fields_are_found(self):
        for field in ("truckType", "numTrucks", "dispatchIntervalMinutes"):
            self.assertIn(field, self.by_field, field)

    def test_every_evidence_kind_records_its_scan_scope(self):
        """못 찾은 근거도 자리를 지킨다. 없다고 적으면 근거가 아니라 주장이 된다."""
        b = self.by_field["truckType"]
        self.assertEqual(list(self.binding.KINDS), list(b["evidence"]))
        for kind, sig in b["evidence"].items():
            self.assertTrue(sig["scanned"], kind)
            if not sig["found"]:
                self.assertEqual([], sig["sites"], kind)

    def test_answer_field_comes_from_the_conversion_site_not_the_name(self):
        """numTrucks 의 답변 필드는 truckCount 다. 이름으로는 나오지 않는다."""
        b = self.by_field["numTrucks"]
        self.assertEqual("truckCount", b["answer_field"])
        self.assertTrue(b["evidence"]["conversion"]["found"])
        self.assertIn("truckCount", b["evidence"]["conversion"]["sites"][0]["quote"])

    def test_answer_field_is_none_without_a_conversion_site(self):
        """변환 자리가 없으면 설정 필드 이름을 베끼지 않는다. 모른다고 적는다.

        `returnFraction` 은 이 경계 안의 어느 파일도 사용자 입력에서 옮겨 주지 않는다.
        """
        b = self.by_field["returnFraction"]
        self.assertIsNone(b["answer_field"])
        self.assertFalse(b["evidence"]["conversion"]["found"])

    def test_the_name_is_wrong_for_two_of_the_forty_two_fields(self):
        """이름으로 이으면 42개 중 2개가 틀린다. 이 수가 설계의 근거다."""
        differ = {b["config_field"]: b["answer_field"] for b in self.bindings
                  if b["answer_field"] and b["answer_field"] != b["config_field"]}
        self.assertEqual({"numTrucks": "truckCount",
                          "collectionTimeMinutes": "collectionTime"}, differ)

    def test_enum_values_come_from_the_converting_type(self):
        """차종의 선언 유형은 String 이다. 허용값은 변환 코드가 가리키는 열거형에서 온다."""
        b = self.by_field["truckType"]
        self.assertEqual("String", b["declared_type"])
        sig = b["evidence"]["enum_values"]
        self.assertTrue(sig["found"])
        self.assertEqual(["LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"], sig["values"])
        self.assertTrue(sig["sites"])

    def test_range_comes_from_validation(self):
        """배차간격의 범위는 검증기의 비교에서 온다. 기호 상수도 스냅샷에서 푼다."""
        r = self.by_field["dispatchIntervalMinutes"]["range"]
        self.assertEqual(0, r["min"])
        self.assertEqual(1439, r["max"])
        self.assertTrue(r["evidence_ids"])

    def test_range_is_none_when_not_confirmed(self):
        """검증기가 안 보는 값의 범위는 빈칸이다. 지어내지 않는다."""
        r = self.by_field["zoneAssignmentRule"]["range"]
        self.assertIsNone(r["min"])
        self.assertIsNone(r["max"])
        self.assertIsNone(r["source"])

    def test_default_comes_from_the_declaration(self):
        d = self.by_field["numTrucks"]["default"]
        self.assertEqual("1", d["value"])
        self.assertIsNotNone(d["site"])

    def test_dependents_point_at_what_uses_the_value(self):
        """차종에 의존하는 것 — 용량 계산과 골목 진입 판정."""
        quotes = " ".join(s["quote"] for s
                          in self.by_field["truckType"]["evidence"]["dependents"]["sites"])
        self.assertIn("capacityKg", quotes)

    def test_binding_id_is_stable_from_the_anchor(self):
        again = self.binding.collect(self.snapshot)
        self.assertEqual([b["binding_id"] for b in self.bindings],
                         [b["binding_id"] for b in again])
        self.assertTrue(all(b["binding_id"].startswith("BD-") for b in self.bindings))


class BindingLink(unittest.TestCase):
    """연결은 앵커로만 생긴다. 이름은 보지 않는다."""

    @classmethod
    def setUpClass(cls):
        from sesx import binding, candidates
        cls.binding = binding
        snapshot = _snapshot()
        cls.cands = candidates.build(snapshot)
        cls.bindings = binding.link(binding.collect(snapshot), cls.cands)
        cls.by_field = {b["config_field"]: b for b in cls.bindings}

    def test_the_declared_type_is_not_the_bridge(self):
        """차종의 선언 유형은 String 이다. 이름만 보면 이을 곳이 없다."""
        self.assertEqual("String", self.by_field["truckType"]["declared_type"])

    def test_link_comes_from_the_converting_type(self):
        """변환 코드가 가리키는 열거형이 개체 후보의 기호와 같다 — 그것이 다리다."""
        link = self.by_field["truckType"]["ses_link"]
        self.assertEqual("proposed", link["state"])
        by_id = {c["cand_id"]: c for c in self.cands}
        self.assertEqual("TruckType", by_id[link["entity_candidate"]]["name"])
        self.assertEqual("type_match", link["rule"])

    def test_a_name_lookalike_with_a_different_anchor_does_not_link(self):
        """이름이 같아도 앵커가 다르면 잇지 않는다."""
        fake = [{"cand_id": "CD-fake", "name": "numTrucks", "kind": "type_declaration",
                 "anchor": {"file_path": "elsewhere/Other.java", "owner_type": None,
                            "symbol": "numTrucks"}, "sites": []}]
        from sesx import binding
        only = [b for b in binding.collect(_snapshot()) if b["config_field"] == "numTrucks"]
        self.assertEqual("unlinked", binding.link(only, fake)[0]["ses_link"]["state"])

    def test_unlinked_is_a_real_outcome_not_a_failure(self):
        """차량 대수와 배차간격은 이 경계 안에 유형 앵커가 없다. 빈칸으로 남고
        그것이 제공자에게 갈 일이다 — 억지로 잇지 않는다."""
        for field in ("numTrucks", "dispatchIntervalMinutes"):
            self.assertEqual("unlinked", self.by_field[field]["ses_link"]["state"], field)

    def test_link_never_claims_approved_on_its_own(self):
        """승인은 사람만 붙인다(규칙 4)."""
        self.assertNotIn("approved", {b["ses_link"]["state"] for b in self.bindings})


class BindingDependsOn(unittest.TestCase):
    """먼저 결정해야 하는 항목. 검증기가 다른 설정을 함께 읽는 자리에서 나온다."""

    @classmethod
    def setUpClass(cls):
        from sesx import binding
        cls.by_field = {b["config_field"]: b
                        for b in binding.collect(_snapshot())}

    def test_route_capacity_depends_on_truck_type(self):
        """경로 배정용량의 상한이 선택한 차종의 정격용량이다 — 차종이 먼저다."""
        dep = self.by_field["routeAvailableCapacityKg"]["depends_on"]
        self.assertIn("truckType", [d["config_field"] for d in dep])
        self.assertTrue(dep[0]["evidence_ids"])

    def test_a_field_does_not_depend_on_itself(self):
        for name, b in self.by_field.items():
            self.assertNotIn(name, [d["config_field"] for d in b["depends_on"]], name)

    def test_no_dependency_is_an_empty_list_not_a_guess(self):
        self.assertEqual([], self.by_field["returnFraction"]["depends_on"])


if __name__ == "__main__":
    unittest.main()
