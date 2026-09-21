# -*- coding: utf-8 -*-
"""PES — 요청한 시뮬레이터가 루트를 정한다.

이 시험의 첫 항목은 **모델 없이** 판정된다. 정답지(ref-v8)를 SES 로 넣고 루트
`장량동 생활쓰레기 수거 시뮬레이터` 로 요청했을 때 PES 가 나오는가. 나오지 않으면
검사기가 아니라 요청 계약이 틀린 것이다.
"""
from __future__ import annotations

import copy
import io
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

import refses  # noqa: E402
from sesx import pes, snapshot  # noqa: E402


def complete_request(doc, **over):
    """정답지를 자를 완전한 요청 하나. **고르는 것은 시험 작성자다** — pes.py 에는
    이런 보조 함수가 없다. 코드가 SPEC 선택을 대신 만들어 주면 안 되기 때문이다."""
    req = {
        "pes_request_version": "1",
        "request_id": "PES-jangnyang-baseline",
        "simulator": "장량동 생활쓰레기 수거 시뮬레이터",
        "requested_by": "시험",
        "ses_artifact_id": doc["artifact_id"],
        "ses_artifact_sha256": snapshot.digest(doc),
        "root": {"entity_id": root_of(doc), "basis": "requested_simulator",
                 "reason": "요청한 시뮬레이터의 전체다"},
        "selections": [{"decomposition_id": d["id"],
                        "chosen_member_id": d["members"][0]["entity_id"],
                        "reason": "시험이 첫 가지를 고른다"}
                       for d in doc["decompositions"] if d["kind"] == "SPEC"],
        "multiplicities": [{"decomposition_id": d["id"], "count": 2, "basis": "requested",
                            "evidence_ids": []}
                           for d in doc["decompositions"] if d["kind"] == "MULTI"],
        "aspects_included": "all",
    }
    req.update(over)
    return req


def root_of(doc):
    for e in doc["entities"]:
        if e["name"] == "장량동 생활쓰레기 수거 시뮬레이터":
            return e["id"]
    raise AssertionError("정답지 루트를 찾지 못했다")


def result_of(out, check_id):
    for c in out["validation_results"]["checks"]:
        if c["check_id"] == check_id:
            return c
    raise AssertionError(f"{check_id} 검사가 없다")


class RefV8Tests(unittest.TestCase):
    """가장 값싼 판정 — LLM 도 실행도 필요 없다."""

    @classmethod
    def setUpClass(cls):
        cls.doc, _, cls.raw = refses.load()

    def test_reference_prunes_into_a_pes(self):
        out = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        self.assertEqual(out["status"], "built", out["validation_results"]["blocking_failure_ids"])
        self.assertEqual(out["root_entity_id"], root_of(self.doc))
        self.assertTrue(out["entities"])
        self.assertEqual(out["validation_results"]["blocking_failure_ids"], [])

    def test_pruned_tree_is_single_rooted_and_reachable(self):
        out = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        kept = set(out["entities"])
        child = [m for d in out["decompositions"] for m in d["members"]]
        self.assertEqual(len(child), len(set(child)), "한 개체가 두 부모를 갖는다")
        self.assertNotIn(out["root_entity_id"], child)
        self.assertEqual(kept, {out["root_entity_id"]} | set(child))
        for cid in ("PES_SINGLE_ROOT", "PES_REACHABLE", "PES_ACYCLIC"):
            self.assertEqual(result_of(out, cid)["result"], "pass", cid)

    def test_unchosen_spec_branches_are_dropped_with_a_reason(self):
        out = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        spec_drops = [d for d in out["dropped"] if d["kind"] == "spec_branch"]
        self.assertTrue(spec_drops)
        for d in spec_drops:
            self.assertTrue(d["reason"])
            self.assertNotIn(d["entity_id"], out["entities"])
        self.assertEqual(result_of(out, "PES_DROP_RECORDED")["result"], "pass")

    def test_every_dropped_entity_is_accounted_for(self):
        """조용한 삭제가 없다 — SES 개체는 남거나 사유와 함께 빠지거나 둘 중 하나다."""
        out = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        all_ids = {e["id"] for e in self.doc["entities"]}
        dropped = {d["entity_id"] for d in out["dropped"] if d.get("entity_id")}
        self.assertEqual(all_ids, set(out["entities"]) | dropped)

    def test_multiplicity_is_recorded_not_expanded(self):
        """MULTI 는 개수만 적는다. 코드가 인스턴스 개체를 지어내지 않는다."""
        out = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        multi = [d for d in out["decompositions"] if d["kind"] == "MULTI"]
        self.assertTrue(multi)
        for d in multi:
            self.assertEqual(d["count"], 2)
        self.assertEqual(len(out["entities"]), len(set(out["entities"])))

    def test_same_request_twice_gives_the_same_pes(self):
        a = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        b = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        self.assertEqual(pes.digest(a), pes.digest(b))


class RefusalTests(unittest.TestCase):
    """요청이 불완전하면 PES 를 만들지 않는다. 코드가 채워 넣지 않는다."""

    @classmethod
    def setUpClass(cls):
        cls.doc, _, _ = refses.load()

    def refused(self, req, check_id):
        out = pes.build(self.doc, req, "PES-0001")
        self.assertEqual(out["status"], "refused")
        self.assertEqual(out["entities"], [])
        self.assertEqual(out["decompositions"], [])
        self.assertIn(check_id, out["validation_results"]["blocking_failure_ids"])
        return out

    def test_root_not_in_the_ses_is_refused(self):
        req = complete_request(self.doc)
        req["root"]["entity_id"] = "E9999"
        self.refused(req, "PES_ROOT_EXISTS")

    def test_root_must_be_named_at_all(self):
        req = complete_request(self.doc)
        req["root"] = {}
        self.refused(req, "PES_ROOT_EXISTS")

    def test_unselected_spec_axis_is_refused(self):
        req = complete_request(self.doc)
        req["selections"] = req["selections"][:-1]
        out = self.refused(req, "PES_SPEC_RESOLVED")
        self.assertTrue(result_of(out, "PES_SPEC_RESOLVED")["item_ids"])

    def test_two_selections_on_one_axis_is_refused(self):
        req = complete_request(self.doc)
        req["selections"].append(dict(req["selections"][0]))
        self.refused(req, "PES_SPEC_RESOLVED")

    def test_selection_of_a_non_member_is_refused(self):
        req = complete_request(self.doc)
        req["selections"][0]["chosen_member_id"] = "E9999"
        self.refused(req, "PES_SPEC_RESOLVED")

    def test_missing_multiplicity_count_is_refused(self):
        req = complete_request(self.doc)
        req["multiplicities"] = req["multiplicities"][:-1]
        self.refused(req, "PES_MULTI_RESOLVED")

    def test_multiplicity_without_evidence_needs_requested_basis(self):
        """근거 없이 'config_count' 라고 적을 수 없다. 모르면 requested 라고 적는다."""
        req = complete_request(self.doc)
        req["multiplicities"][0]["basis"] = "config_count"
        req["multiplicities"][0]["evidence_ids"] = []
        self.refused(req, "PES_MULTI_RESOLVED")

    def test_zero_multiplicity_is_refused(self):
        req = complete_request(self.doc)
        req["multiplicities"][0]["count"] = 0
        self.refused(req, "PES_MULTI_RESOLVED")

    def test_ses_hash_mismatch_is_refused(self):
        req = complete_request(self.doc)
        req["ses_artifact_sha256"] = "0" * 64
        self.refused(req, "PES_SES_IDENTITY")

    def test_ses_artifact_id_mismatch_is_refused(self):
        req = complete_request(self.doc)
        req["ses_artifact_id"] = "다른-리비전"
        self.refused(req, "PES_SES_IDENTITY")

    def test_unknown_request_version_is_refused(self):
        req = complete_request(self.doc)
        req["pes_request_version"] = "99"
        self.refused(req, "PES_REQUEST_SHAPE")

    def test_simulator_must_be_named(self):
        """어느 시뮬레이터를 원하는지가 루트의 근거다. 비워 둘 수 없다."""
        req = complete_request(self.doc)
        req["simulator"] = ""
        self.refused(req, "PES_REQUEST_SHAPE")


class HonestyTests(unittest.TestCase):
    """PES 가 모르는 것을 아는 척하지 않는가."""

    @classmethod
    def setUpClass(cls):
        cls.doc, _, _ = refses.load()

    def test_unknown_activation_is_reported_not_assumed(self):
        out = pes.build(self.doc, complete_request(self.doc), "PES-0001")
        self.assertTrue(out["activation_unknown"], "정답지 활성 조건은 전부 unknown 이다")
        self.assertEqual(result_of(out, "PES_ACTIVATION_NOT_ASSUMED")["result"], "pass")
        for rec in out["activation_unknown"]:
            self.assertNotEqual(rec.get("state"), "unconditional")

    def test_activation_silently_promoted_is_caught(self):
        doc = copy.deepcopy(self.doc)
        out = pes.build(doc, complete_request(doc), "PES-0001")
        out["activation_unknown"] = []          # 보고를 지운 위조본
        checks = pes.verify(doc, out)
        self.assertEqual(result_of({"validation_results": {"checks": checks}},
                                   "PES_ACTIVATION_NOT_ASSUMED")["result"], "fail")

    def test_unresolved_in_scope_is_surfaced(self):
        doc = copy.deepcopy(self.doc)
        keep = doc["entities"][0]["id"]
        doc["unresolved"] = [{"id": "U1", "item_ids": [keep],
                              "reason_code": "unknown_reference"}]
        out = pes.build(doc, complete_request(doc), "PES-0001")
        if keep in out["entities"]:
            self.assertIn("U1", out["unresolved_in_scope"])
        self.assertEqual(result_of(out, "PES_UNRESOLVED_IN_SCOPE")["blocking"], False)

    def test_couplings_whose_endpoint_is_pruned_away_are_dropped(self):
        doc = copy.deepcopy(self.doc)
        out = pes.build(doc, complete_request(doc), "PES-0001")
        kept = set(out["entities"])
        for c in out["couplings"]:
            self.assertIn(c["source"]["entity_id"], kept)
            self.assertIn(c["target"]["entity_id"], kept)
        gone = {c["id"] for c in doc["couplings"]} - {c["id"] for c in out["couplings"]}
        recorded = {d["coupling_id"] for d in out["dropped"] if d["kind"] == "coupling"}
        self.assertEqual(gone, recorded)


class CycleTests(unittest.TestCase):
    def test_cycle_in_the_ses_is_refused_not_looped(self):
        doc, _, _ = refses.load()
        root = root_of(doc)
        # 루트를 자기 자손의 자식으로 만든다
        child = doc["decompositions"][0]["members"][0]["entity_id"]
        doc["decompositions"].append({
            "id": "Dcycle", "status": "proposed", "evidence_ids": [], "kind": "ASPECT",
            "parent_entity_id": child, "activation_id": "AC0",
            "members": [{"entity_id": root, "evidence_ids": []}], "selection": None})
        out = pes.build(doc, complete_request(doc), "PES-0001")
        self.assertEqual(out["status"], "refused")
        self.assertIn("PES_ACYCLIC", out["validation_results"]["blocking_failure_ids"])


class RequestIoTests(unittest.TestCase):
    def test_request_round_trips_through_a_file(self):
        doc, _, _ = refses.load()
        req = complete_request(doc)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "request.json")
            with io.open(p, "w", encoding="utf-8") as f:
                f.write(json.dumps(req, ensure_ascii=False))
            self.assertEqual(pes.load_request(p), req)

    def test_request_schema_file_matches_the_module(self):
        path = os.path.join(os.path.dirname(HERE), "sesx", "pes-request.schema.json")
        with io.open(path, encoding="utf-8") as f:
            self.assertEqual(json.load(f), pes.REQUEST_SCHEMA)


if __name__ == "__main__":
    unittest.main()
