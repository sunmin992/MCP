# -*- coding: utf-8 -*-
"""'어느 계획이 결정 표면에 닿는가' 측정기의 인수 시험.

이 측정이 쉽게 틀리는 자리 둘을 지킨다.

  · **닿을 수 없었던 실행을 못 닿은 것으로 세지 않는다.** 스냅샷에 설정 파일이 없으면
    그것은 계획의 성질이 아니라 경계의 성질이다
  · **계획이 다른 것만 비교하지 않는다.** 모델·정책이 계획과 같이 움직이므로, 그 둘이
    같은 실행끼리만 나란히 놓는다
"""
import io
import json
import os
import shutil
import tempfile
import unittest

import score_reach


def manifest(*names):
    return {"files": [{"path": "src/main/java/com/wastesim/model/" + n} for n in names]}


def draft(tasks):
    return {"tasks": tasks, "bridge": {"evidence": 10, "evidence_in_binding_files": 4},
            "counts": {}}


def task(point, binding_id=None, delivers=None, disposition="unknown"):
    return {"point_id": point, "kind": "provide_value", "binding_id": binding_id,
            "disposition": disposition, "match_rule": None, "evidence_ids": [],
            "slots": {"delivers_to": delivers, "asks_as": None}}


class Eligibility(unittest.TestCase):

    def test_a_snapshot_without_the_config_file_cannot_reach_it(self):
        ok, why = score_reach.eligible(manifest("SimulationEngine.java"))
        self.assertFalse(ok)
        self.assertIn("SimulationConfig.java", why)

    def test_a_snapshot_with_the_config_file_is_comparable(self):
        ok, why = score_reach.eligible(manifest("SimulationConfig.java", "TruckType.java"))
        self.assertTrue(ok)
        self.assertIsNone(why)


class EmptyArtifact(unittest.TestCase):
    """아무것도 못 낸 실행을 **못 닿은 것으로 세지 않는다.**

    jn-S2-1 이 그랬다 — 근거 0건이다. 그것을 0%로 세면 S2 의 평균이 반으로 깎이고,
    계획의 성질이 아닌 것이 계획의 수가 된다. P-micro 를 빼는 것과 같은 이유다.
    """

    def test_an_artifact_with_no_evidence_is_excluded(self):
        ok, why = score_reach.produced({"evidence": [], "entities": []})
        self.assertFalse(ok)
        self.assertIn("근거", why)

    def test_an_artifact_with_evidence_is_measured(self):
        ok, why = score_reach.produced({"evidence": [{"id": "EV1"}]})
        self.assertTrue(ok)
        self.assertIsNone(why)


class Reach(unittest.TestCase):

    def setUp(self):
        self.d = draft([
            task("attr:A:x", "BD-1", "days", "user_decides"),
            task("attr:A:y", "BD-2", "days", "default_applies"),   # 같은 필드 — 한 번만 센다
            task("attr:A:z", "BD-3", "seeds", "unknown"),          # 붙었으나 쓸 정보가 없다
            task("attr:A:w"),                                      # 안 붙었다
        ])

    def test_it_counts_bound_tasks(self):
        self.assertEqual(3, score_reach.reach(self.d)["bound"])
        self.assertEqual(4, score_reach.reach(self.d)["tasks"])

    def test_it_counts_distinct_config_fields_not_tasks(self):
        """한 필드에 작업 둘이 붙어도 닿은 결정은 하나다."""
        self.assertEqual(2, score_reach.reach(self.d)["fields"])

    def test_a_bound_task_with_no_disposition_is_not_usable(self):
        """붙었는데 처분이 unknown 이면 서브태스크를 만들 정보가 없다."""
        self.assertEqual(2, score_reach.reach(self.d)["usable"])

    def test_it_carries_the_evidence_level_reach(self):
        r = score_reach.reach(self.d)
        self.assertEqual(10, r["evidence"])
        self.assertEqual(4, r["evidence_at_config"])


class Grouping(unittest.TestCase):

    def _runs(self):
        return [
            {"run": "jn-S2-2", "plan": "S2", "model": "gpt-4.1-mini", "policy": "P-target"},
            {"run": "jn-T2-1", "plan": "T2", "model": "gpt-4.1-mini", "policy": "P-target"},
            {"run": "jn-T2-3", "plan": "T2", "model": "gpt-4.1-mini", "policy": "P-target"},
            {"run": "q3-obs1", "plan": "T2-tree", "model": "qwen3-coder:30b",
             "policy": "P-pilot"},
        ]

    def test_runs_are_grouped_by_what_moves_with_the_plan(self):
        groups = score_reach.group(self._runs())
        self.assertIn(("P-target", "gpt-4.1-mini"), groups)
        self.assertIn(("P-pilot", "qwen3-coder:30b"), groups)

    def test_a_plan_seen_once_is_marked_as_such(self):
        """n=1 을 계획의 성질이라고 부르지 않는다."""
        rows = score_reach.by_plan(self._runs())
        self.assertEqual(2, rows[("P-target", "gpt-4.1-mini")]["T2"]["n"])
        self.assertTrue(rows[("P-pilot", "qwen3-coder:30b")]["T2-tree"]["single_run"])
        self.assertFalse(rows[("P-target", "gpt-4.1-mini")]["T2"]["single_run"])

    def test_a_group_with_one_plan_cannot_compare_plans(self):
        """계획이 하나뿐인 무리에서는 계획을 비교하지 않는다."""
        only = [r for r in self._runs() if r["policy"] == "P-pilot"]
        self.assertFalse(score_reach.comparable(score_reach.by_plan(only)))
        self.assertTrue(score_reach.comparable(score_reach.by_plan(self._runs())))


class RunMetadata(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="reach-")
        d = os.path.join(self.tmp, "r1", "stages", "a", "attempt-1")
        os.makedirs(d)
        self._write(os.path.join(d, "request.json"),
                    {"plan": "T2", "stage": "a", "model": {"name": "gpt-4.1-mini"}})
        self._write(os.path.join(self.tmp, "r1", "input-decisions.json"),
                    {"policy_id": "P-target"})
        self._write(os.path.join(self.tmp, "r1", "snapshot", "manifest.json"),
                    manifest("SimulationConfig.java"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, path, obj):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(obj, ensure_ascii=False))

    def test_the_plan_is_read_from_the_request_not_guessed_from_directories(self):
        """단계 폴더 이름으로 계획을 짐작하면 같은 단계 목록을 쓰는 계획이 섞인다."""
        meta = score_reach.run_meta(os.path.join(self.tmp, "r1"))
        self.assertEqual("T2", meta["plan"])
        self.assertEqual("gpt-4.1-mini", meta["model"])
        self.assertEqual("P-target", meta["policy"])
        self.assertTrue(meta["eligible"])

    def test_a_run_without_a_request_says_unknown_instead_of_guessing(self):
        os.makedirs(os.path.join(self.tmp, "r2"))
        meta = score_reach.run_meta(os.path.join(self.tmp, "r2"))
        self.assertIsNone(meta["plan"])
        self.assertFalse(meta["eligible"])


if __name__ == "__main__":
    unittest.main()
