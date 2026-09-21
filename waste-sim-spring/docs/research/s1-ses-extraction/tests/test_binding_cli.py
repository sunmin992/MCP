# -*- coding: utf-8 -*-
"""연결 경로의 명령 하나하나가 실제로 도는지 본다.

근거 수집 → 대응표 → 템플릿 초안 → 제공자 빈칸 목록. 실제 저장소·실제 경계로 돈다.
모델을 부르지 않으므로 열쇠 없이 돌 수 있다.
"""
import io
import json
import os
import shutil
import tempfile
import unittest
from unittest.mock import patch

import extract

SES = {
    "entities": [{"id": "E1", "name": "수거차량", "kind": "stateful"},
                 {"id": "E2", "name": "5톤 차량", "kind": "type"},
                 {"id": "E3", "name": "1톤 차량", "kind": "type"}],
    "attributes": [{"id": "A1", "entity_id": "E1", "name": "배차간격",
                    "evidence_ids": ["EV1"]}],
    "decompositions": [{"id": "D1", "kind": "SPEC", "parent_entity_id": "E1",
                        "members": [{"entity_id": "E2"}, {"entity_id": "E3"}],
                        "selection": {"symbol": "TruckType"},
                        "activation_id": None, "evidence_ids": ["EV2"]}],
    "couplings": [], "activation": [],
    "evidence": [
        {"id": "EV1", "file_path": "src/main/java/com/wastesim/model/SimulationConfig.java",
         "start_line": 208, "end_line": 208, "symbol": "dispatchIntervalMinutes", "quote": "…"},
        {"id": "EV2", "file_path": "src/main/java/com/wastesim/model/TruckType.java",
         "start_line": 7, "end_line": 7, "symbol": "TruckType", "quote": "…"}],
}


class BindingChain(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="bindcli-")
        self.patch = patch.object(extract, "EXP", self.tmp)
        self.patch.start()
        os.makedirs(os.path.join(self.tmp, "r1"))
        with io.open(os.path.join(self.tmp, "r1", "ses.json"), "w", encoding="utf-8") as f:
            f.write(json.dumps(SES, ensure_ascii=False))

    def tearDown(self):
        self.patch.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, *argv):
        return extract.main(list(argv))

    def _read(self, *parts):
        with io.open(os.path.join(self.tmp, "r1", "binding", *parts), encoding="utf-8") as f:
            return json.load(f)

    def test_the_whole_chain_runs_and_leaves_its_evidence(self):
        self.assertEqual(0, self._run("bind", "--run-id", "r1", "--rule", "P-binding"))
        bindings = self._read("bindings.json")
        by_field = {b["config_field"]: b for b in bindings["bindings"]}
        self.assertEqual("truckCount", by_field["numTrucks"]["answer_field"])
        self.assertEqual("proposed", by_field["truckType"]["ses_link"]["state"])

        self.assertEqual(0, self._run("template", "--run-id", "r1"))
        tasks = {t["point_id"]: t for t in self._read("template.json")["tasks"]}
        self.assertEqual(["5톤 차량", "1톤 차량"],
                         tasks["spec:수거차량:TruckType"]["slots"]["options"])
        self.assertEqual("user_decides", tasks["attr:수거차량:배차간격"]["disposition"])

        self.assertEqual(0, self._run("gaps", "--run-id", "r1"))
        rows = self._read("gaps.json")["rows"]
        self.assertTrue(any(r["state"] == "missing" for r in rows))
        self.assertTrue(all(r["source_digest"] for r in rows))

    def test_bind_refuses_to_overwrite_its_own_output(self):
        """같은 run-id 로 다시 만들지 않는다."""
        self.assertEqual(0, self._run("bind", "--run-id", "r1", "--rule", "P-binding"))
        self.assertEqual(3, self._run("bind", "--run-id", "r1", "--rule", "P-binding"))

    def test_a_provider_fill_lands_as_a_new_revision_marked_human(self):
        for cmd in (("bind", "--run-id", "r1", "--rule", "P-binding"),
                    ("template", "--run-id", "r1"), ("gaps", "--run-id", "r1")):
            self.assertEqual(0, self._run(*cmd))
        fill = os.path.join(self.tmp, "fill.json")
        with io.open(fill, "w", encoding="utf-8") as f:
            f.write(json.dumps([{"point_id": "attr:수거차량:배차간격",
                                 "slot": "applies_when", "value": "언제나",
                                 "why": "조건 없이 쓰인다"}], ensure_ascii=False))
        self.assertEqual(0, self._run("gaps", "--run-id", "r1", "--fill", fill,
                                      "--reviewer", "검토자"))
        rows = self._read("gaps-rev1.json")["rows"]
        at = {(r["point_id"], r["slot"]): r for r in rows}
        r = at[("attr:수거차량:배차간격", "applies_when")]
        self.assertEqual("human", r["origin"])
        self.assertEqual("검토자", r["reviewer"])
        self.assertEqual(1, self._read("gaps-rev1.json")["summary"]["by_origin"]["human"])

    def test_template_before_bind_says_so_instead_of_crashing(self):
        self.assertEqual(2, self._run("template", "--run-id", "r1"))


if __name__ == "__main__":
    unittest.main()
