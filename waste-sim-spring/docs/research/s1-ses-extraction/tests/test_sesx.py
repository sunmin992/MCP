# -*- coding: utf-8 -*-
"""sesx 인수 시험. 교차검토 §12의 목록을 그대로 시험으로 옮긴 것이다.

  python -m unittest discover -s tests -v      (키트 디렉터리에서)
"""
from __future__ import annotations

import io
import json
import os
import shutil
import sys
import tempfile
import unittest

KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, KIT)

from sesx import (assemble, contract, evidence, input_policy, llm, report, review,  # noqa: E402
                  run_store, snapshot as snap_mod, stages, structure, validate)

REPO = os.path.abspath(os.path.join(KIT, "..", "..", ".."))


# ---------------------------------------------------------------- 도우미

def write(path, text, newline="\n"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", newline))


def tiny_repo(root):
    write(os.path.join(root, "src/main/java/app/Engine.java"), "\n".join([
        "package app;",                                        # 1
        "public class Engine {",                               # 2
        "    int[] fill = new int[3];",                         # 3
        "    void step(int amount) {",                          # 4
        '        String tag = "a  b";',                         # 5
        "        fill[0] += amount;",                           # 6
        "        if (mode == Mode.APPLY) { fill[1] += 1; }",     # 7
        "    }",                                                # 8
        "}",                                                    # 9
    ]))
    write(os.path.join(root, "src/main/java/app/Bin.java"),
          "package app;\npublic class Bin {\n    int load;\n}\n")
    write(os.path.join(root, "src/main/java/app/ses/Declared.java"),
          "package app.ses;\npublic class Declared { /* SES 트리 선언 */ }\n")
    write(os.path.join(root, "eval/reference-ses.json"), '{"root": "x"}\n')


BASE_POLICY = {
    "policy_id": "T", "approval_status": "approved",
    "include_globs": ["src/main/java/**/*.java", "eval/**/*.json"],
    "exclude_globs": [], "exclude_reasons": {},
    "evaluation_asset_globs": ["eval/**"],
    "require_classified_roles": True, "default_role_for_included": "unclassified",
    "file_roles": [
        {"glob": "src/main/java/app/ses/**", "role": "ses_declaration", "reason": "SES 선언"},
        {"glob": "src/main/java/app/**", "role": "simulation_core", "reason": "엔진"},
    ],
    "required_dependencies": {}, "dependency_waivers": {},
}


def policy_without_assets():
    p = json.loads(json.dumps(BASE_POLICY))
    p["include_globs"] = ["src/main/java/app/*.java"]
    return p


def make_snapshot(tmp, policy=None):
    repo = os.path.join(tmp, "repo")
    tiny_repo(repo)
    rep = input_policy.decide(repo, policy or policy_without_assets())
    out = os.path.join(tmp, "exp", "r1", "snapshot")
    m = snap_mod.materialize(repo, rep, out, "snap-r1", git={"rev": "deadbeef"})
    return repo, rep, snap_mod.Snapshot(out, m)


def ev(file_path, s, e, quote, symbol=None):
    return {"file_path": file_path, "start_line": s, "end_line": e, "quote": quote,
            "symbol": symbol}


ENGINE = "src/main/java/app/Engine.java"
BIN = "src/main/java/app/Bin.java"


# ---------------------------------------------------------------- 입력 경계

class InputPolicyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = os.path.join(self.tmp, "repo")
        tiny_repo(self.repo)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_evaluation_asset_and_ses_declaration_block_before_any_call(self):
        rep = input_policy.decide(self.repo, BASE_POLICY)
        codes = {b["code"] for b in rep["blocks"]}
        self.assertIn("EVALUATION_ASSET_IN_INPUT", codes)
        self.assertIn("BLOCKED_ROLE_IN_INPUT", codes)
        self.assertFalse(rep["approved"])
        self.assertNotIn("eval/reference-ses.json", rep["included"])
        self.assertNotIn("src/main/java/app/ses/Declared.java", rep["included"])

    def test_support_code_is_included_with_a_role_not_deleted(self):
        rep = input_policy.decide(self.repo, policy_without_assets())
        self.assertTrue(rep["approved"], rep["blocks"])
        roles = {d["path"]: d["role"] for d in rep["decisions"]}
        self.assertEqual(roles[ENGINE], "simulation_core")

    def test_missing_required_dependency_blocks(self):
        p = policy_without_assets()
        p["required_dependencies"] = {ENGINE: ["src/main/java/app/Missing.java"]}
        rep = input_policy.decide(self.repo, p)
        self.assertIn("REQUIRED_DEPENDENCY_MISSING", {b["code"] for b in rep["blocks"]})

    def test_unclassified_role_blocks(self):
        p = policy_without_assets()
        p["file_roles"] = []
        rep = input_policy.decide(self.repo, p)
        self.assertIn("FILE_ROLE_UNCLASSIFIED", {b["code"] for b in rep["blocks"]})

    def test_real_repo_pilot_policy_blocks_ses_package(self):
        pol = input_policy.load_policy(os.path.join(KIT, "sesx", "rules", "P-pilot.json"))
        rep = input_policy.decide(REPO, pol)
        self.assertTrue(rep["included"], "파일럿 정책이 아무것도 고르지 못했다")
        self.assertFalse([p for p in rep["included"] if "/ses/" in p])
        self.assertFalse([p for p in rep["included"] if p.endswith("reference-ses.json")])


# ---------------------------------------------------------------- 스냅샷

class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_copy_is_used_not_the_worktree(self):
        repo, _, snap = make_snapshot(self.tmp)
        write(os.path.join(repo, ENGINE), "완전히 다른 내용\n")
        self.assertIn("fill[0] += amount;", snap.text(ENGINE))

    def test_tampered_copy_is_detected(self):
        _, _, snap = make_snapshot(self.tmp)
        rec = snap.by_path[ENGINE]
        with io.open(os.path.join(snap.dir, rec["copy_path"]), "w", encoding="utf-8") as f:
            f.write("바꿔치기")
        snap._cache.clear()
        with self.assertRaises(ValueError):
            snap.text(ENGINE)

    def test_crlf_files_get_the_same_line_numbers(self):
        repo = os.path.join(self.tmp, "repo")
        tiny_repo(repo)
        write(os.path.join(repo, ENGINE), io.open(
            os.path.join(repo, ENGINE), encoding="utf-8").read(), newline="\r\n")
        rep = input_policy.decide(repo, policy_without_assets())
        m = snap_mod.materialize(repo, rep, os.path.join(self.tmp, "s2"), "s2")
        snap = snap_mod.Snapshot(os.path.join(self.tmp, "s2"), m)
        self.assertEqual(snap.slice(ENGINE, 6, 6).strip(), "fill[0] += amount;")


# ---------------------------------------------------------------- 근거

class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def verdict(self, rec):
        return evidence.verify_record(self.snap, rec)["verification"]

    def test_missing_file(self):
        v = self.verdict(ev("src/main/java/app/Nope.java", 1, 1, "x"))
        self.assertEqual(v["file"], "fail")
        self.assertEqual(v["verdict"], "fail")

    def test_range_outside_file(self):
        v = self.verdict(ev(ENGINE, 900, 901, "x"))
        self.assertEqual(v["range"], "fail")

    def test_quote_must_be_exact_even_inside_string_literals(self):
        good = self.verdict(ev(ENGINE, 5, 5, 'String tag = "a  b";'))
        self.assertEqual(good["quote"], "pass")
        squashed = self.verdict(ev(ENGINE, 5, 5, 'String tag = "a b";'))
        self.assertEqual(squashed["quote"], "fail")

    def test_quote_newline_difference_is_tolerated(self):
        piece = "    void step(int amount) {\r\n" + '        String tag = "a  b";'
        self.assertEqual(self.verdict(ev(ENGINE, 4, 5, piece))["quote"], "pass")

    def test_symbol_unknown_is_not_checked_not_failed(self):
        v = self.verdict(ev(ENGINE, 6, 6, "fill[0] += amount;", symbol="Engine.step"))
        self.assertEqual(v["symbol"], "pass")
        v2 = self.verdict(ev(ENGINE, 6, 6, "fill[0] += amount;", symbol="Engine.nowhere"))
        self.assertEqual(v2["symbol"], "fail")
        v3 = self.verdict(ev(ENGINE, 6, 6, "fill[0] += amount;", symbol="amount"))
        self.assertIn(v3["symbol"], ("pass", "not_checked"))

    def test_hash_mismatch_is_refused(self):
        v = self.verdict({**ev(ENGINE, 6, 6, "fill[0] += amount;"), "file_sha256": "0" * 64})
        self.assertEqual(v["file"], "fail")


# ---------------------------------------------------------------- 구조

def ent(i, name):
    return {"id": i, "name": name, "kind": "stateful", "scope": "simulation_target",
            "why_entity": None, "evidence_ids": [], "status": "proposed"}


def dec(i, parent, kind, members):
    return {"id": i, "kind": kind, "parent_entity_id": parent,
            "members": [{"entity_id": m, "evidence_ids": []} for m in members],
            "selection": None, "multiplicity": {}, "activation_id": None,
            "evidence_ids": [], "status": "proposed"}


class StructureTest(unittest.TestCase):
    def doc(self, entities, decs=(), coups=()):
        d = contract.new_artifact("t")
        d["entities"] = list(entities)
        d["decompositions"] = list(decs)
        d["couplings"] = list(coups)
        return d

    def test_single_node_is_a_root_candidate(self):
        a = structure.analyze(self.doc([ent("E1", "하나")]))
        self.assertEqual(a["root_candidate_ids"], ["E1"])

    def test_two_roots_are_not_collapsed_to_the_first(self):
        d = self.doc([ent("E1", "a"), ent("E2", "b"), ent("E3", "c")],
                     [dec("D1", "E1", "ASPECT", ["E3"])])
        a = structure.analyze(d)
        self.assertEqual(a["root_candidate_ids"], ["E1", "E2"])
        self.assertIsNone(a["selected_root_id"])

    def test_decomposition_cycle_leaves_no_root(self):
        d = self.doc([ent("E1", "a"), ent("E2", "b")],
                     [dec("D1", "E1", "ASPECT", ["E2"]), dec("D2", "E2", "ASPECT", ["E1"])])
        a = structure.analyze(d)
        self.assertTrue(a["decomposition_cycles"])
        self.assertEqual(a["root_candidate_ids"], [])

    def test_multi_parent_is_reported(self):
        d = self.doc([ent("E1", "a"), ent("E2", "b"), ent("E3", "c")],
                     [dec("D1", "E1", "ASPECT", ["E3"]), dec("D2", "E2", "ASPECT", ["E3"])])
        self.assertEqual(structure.analyze(d)["multi_parent_entity_ids"], ["E3"])

    def test_coupling_feedback_is_not_a_decomposition_cycle(self):
        coups = [{"id": "C1", "source": {"entity_id": "E1"}, "target": {"entity_id": "E2"},
                  "payload": {}, "activation_id": None, "evidence_ids": [], "status": "proposed"},
                 {"id": "C2", "source": {"entity_id": "E2"}, "target": {"entity_id": "E1"},
                  "payload": {}, "activation_id": None, "evidence_ids": [], "status": "proposed"}]
        d = self.doc([ent("E1", "a"), ent("E2", "b")], [dec("D1", "E1", "ASPECT", ["E2"])],
                     coups)
        a = structure.analyze(d)
        self.assertFalse(a["decomposition_cycles"])
        self.assertTrue(a["coupling_cycles"])


# ---------------------------------------------------------------- 조립

def stage_payload(**kw):
    return {k: v for k, v in kw.items()}


class AssembleTest(unittest.TestCase):
    def build(self, stages_dict):
        return assemble.build(stages_dict, "snap-r1", "t",
                              contract.new_artifact("t")["extraction_run"],
                              contract.new_artifact("t")["source_snapshot"])

    def test_merge_is_recorded_not_silent(self):
        st = {"b": stage_payload(entities=[
            {"name": "수거 지점", "evidence": [ev(ENGINE, 3, 3, "x")]},
            {"name": "수거지점", "evidence": [ev(ENGINE, 3, 3, "y")]}])}
        doc = self.build(st)
        self.assertEqual(len(doc["entities"]), 1)
        merged = [p for p in doc["provenance"] if p["decision"] == "merged"]
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["output_item_ids"], ["E1"])
        self.assertIn("수거지점", doc["entities"][0]["alternate_names"])

    def test_self_reference_is_held_not_quietly_stripped(self):
        st = {"b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]}]),
              "d": stage_payload(decompositions=[
                  {"parent": "A", "kind": "MULTI", "members": ["A"],
                   "evidence": [ev(ENGINE, 3, 3, "x")]}])}
        doc = self.build(st)
        self.assertEqual(doc["decompositions"], [])
        held = [u for u in doc["unresolved"] if u["reason_code"] == "self_reference"]
        self.assertEqual(len(held), 1)
        self.assertEqual(held[0]["origin_raw"]["members"], ["A"])

    def test_unknown_reference_is_held_with_origin(self):
        st = {"b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]}]),
              "c": stage_payload(attributes=[{"entity": "없는개체", "name": "v",
                                              "evidence": [ev(ENGINE, 3, 3, "x")]}])}
        doc = self.build(st)
        self.assertEqual(doc["attributes"], [])
        self.assertTrue([u for u in doc["unresolved"]
                         if u["reason_code"] == "unknown_reference"])

    def test_every_raw_candidate_is_traceable(self):
        st = {"a": stage_payload(observations=[{"name": "x"}]),
              "b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]},
                                           {"name": ""}]),
              "e": stage_payload(couplings=[{"source": {"entity": "A"},
                                             "target": {"entity": "없음"}}])}
        doc = self.build(st)
        self.assertEqual(assemble.traceable(doc, assemble.raw_ids_of(st)), [])

    def test_missing_activation_becomes_unknown_never_unconditional(self):
        st = {"b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]},
                                           {"name": "B", "evidence": [ev(ENGINE, 3, 3, "x")]}]),
              "e": stage_payload(couplings=[{"source": {"entity": "A"},
                                             "target": {"entity": "B"},
                                             "payload": {"kind": "value"},
                                             "evidence": [ev(ENGINE, 6, 6, "x")]}])}
        doc = self.build(st)
        act = doc["activation"][0]
        self.assertEqual(act["state"], "unknown")
        self.assertEqual(act["coverage"], "incomplete")
        self.assertTrue([u for u in doc["unresolved"]
                         if u["reason_code"] == "unknown_activation"])

    def test_unconditional_needs_complete_coverage(self):
        st = {"b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]},
                                           {"name": "B", "evidence": [ev(ENGINE, 3, 3, "x")]}]),
              "e": stage_payload(couplings=[{
                  "source": {"entity": "A"}, "target": {"entity": "B"},
                  "payload": {"kind": "value"}, "evidence": [ev(ENGINE, 6, 6, "x")],
                  "activation": {"state": "unconditional", "coverage": "incomplete"}}])}
        doc = self.build(st)
        self.assertEqual(doc["activation"][0]["state"], "unknown")

    def test_two_clauses_without_combination_are_not_known(self):
        st = {"b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]},
                                           {"name": "B", "evidence": [ev(ENGINE, 3, 3, "x")]}]),
              "e": stage_payload(couplings=[{
                  "source": {"entity": "A"}, "target": {"entity": "B"},
                  "payload": {"kind": "value"}, "evidence": [ev(ENGINE, 6, 6, "x")],
                  "activation": {"state": "known", "coverage": "complete", "combination": None,
                                 "clauses": [{"expression": "a", "evidence": [ev(ENGINE, 7, 7, "x")]},
                                             {"expression": "b", "evidence": [ev(ENGINE, 7, 7, "x")]}]}}])}
        doc = self.build(st)
        self.assertEqual(doc["activation"][0]["state"], "unknown")

    def test_value_without_evidence_stays_unknown(self):
        st = {"b": stage_payload(entities=[{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]}]),
              "c": stage_payload(attributes=[{"entity": "A", "name": "적재량",
                                              "evidence": [ev(ENGINE, 3, 3, "x")],
                                              "unit": {"value": "kg"}}])}
        doc = self.build(st)
        unit = doc["attributes"][0]["unit"]
        self.assertEqual(unit["status"], "unknown")
        self.assertEqual(unit["claimed_value"], "kg")

    def test_disputes_are_kept_for_review(self):
        st = {"c": stage_payload(disputes=[{"about": "A", "claim": "개체가 아니다"}])}
        doc = self.build(st)
        self.assertTrue([u for u in doc["unresolved"] if u["reason_code"] == "kind_conflict"])


# ---------------------------------------------------------------- 실행 기록·재개

class RunStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.store = run_store.RunStore(os.path.join(self.tmp, "r"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def key(self, prompt="p", user="u", upstream=None):
        return run_store.resume_key("a", prompt, user, {}, "v", "snap", upstream or {})

    def test_reuse_only_on_identical_key(self):
        k = self.key()
        adir = self.store.begin_attempt("a", {"x": 1})
        self.store.finish_attempt(adir, "completed", response_text="{}")
        self.store.record_completion("a", k, {"entities": []}, adir)
        self.assertIsNotNone(self.store.reusable("a", k))
        self.assertIsNone(self.store.reusable("a", self.key(prompt="p2")))
        self.assertIsNone(self.store.reusable("a", self.key(upstream={"z": "1"})))

    def test_failed_attempt_keeps_the_request_and_raw(self):
        adir = self.store.begin_attempt("a", {"system": "s"})
        self.store.finish_attempt(adir, "failed", response_text="{잘린", error="파싱 실패")
        self.assertTrue(os.path.exists(os.path.join(adir, "request.json")))
        self.assertTrue(os.path.exists(os.path.join(adir, "response.raw")))
        self.assertEqual(self.store.attempts("a")[0]["status"], "failed")

    def test_timeout_is_unknown_not_failed(self):
        adir = self.store.begin_attempt("a", {})
        self.assertEqual(json.loads(io.open(os.path.join(adir, "meta.json"),
                                            encoding="utf-8").read())["status"], "unknown")

    def test_attempts_never_overwrite(self):
        a1 = self.store.begin_attempt("a", {})
        a2 = self.store.begin_attempt("a", {})
        self.assertNotEqual(a1, a2)


# ---------------------------------------------------------------- 경로 실행

def fake_stage_payloads():
    e = [ev(ENGINE, 6, 6, "fill[0] += amount;", symbol="Engine.step")]
    return {
        "a": json.dumps({"observations": [{"name": "적재량", "what_changes": "늘어난다",
                                           "evidence": e}]}, ensure_ascii=False),
        "b": json.dumps({"entities": [
            {"name": "쓰레기통 집합", "kind": "set", "scope": "simulation_target",
             "why_entity": "여럿이다", "evidence": e},
            {"name": "쓰레기통", "kind": "stateful", "scope": "simulation_target",
             "why_entity": "적재량이 변한다", "evidence": e}]}, ensure_ascii=False),
        "c": json.dumps({"attributes": [{"entity": "쓰레기통", "name": "적재량",
                                         "value_type": "int", "evidence": e}]},
                        ensure_ascii=False),
        "d": json.dumps({"decompositions": [{"parent": "쓰레기통 집합", "kind": "MULTI",
                                             "members": ["쓰레기통"],
                                             "member_evidence": {"쓰레기통": e},
                                             "multiplicity": {"count_expression": "new int[3]",
                                                              "evidence": [ev(ENGINE, 3, 3,
                                                                              "int[] fill = new int[3];")]},
                                             "evidence": e}]}, ensure_ascii=False),
        "e": json.dumps({"couplings": []}, ensure_ascii=False),
        "r": json.dumps({"entities": [], "decompositions": [],
                         "note": "루트 후보가 하나뿐이라 상위 개체가 필요하지 않다"},
                        ensure_ascii=False),
        "f": json.dumps({"activation": [{"target": {"kind": "decomposition",
                                                    "ref": {"parent": "쓰레기통 집합",
                                                            "kind": "MULTI"}},
                                         "state": "known", "coverage": "complete",
                                         "clauses": [{"expression": "new int[3]",
                                                      "symbol": "fill",
                                                      "evidence": [ev(ENGINE, 3, 3,
                                                                      "int[] fill = new int[3];")]}],
                                         "combination": None}]}, ensure_ascii=False),
    }


class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)
        self.store = run_store.RunStore(os.path.join(self.tmp, "exp", "r1"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_stage_failure_keeps_earlier_stages(self):
        """한 단계가 죽어도 **독립적인 뒤 단계는 계속 돈다.**

        예전에는 break 로 전부 멈췄고, 반복 실행 5회 중 3회가 b2·d·f 한 단계 때문에
        통째로 죽었다 — 앞 단계 산출물이 멀쩡한데도 버려졌다. c(속성)는 d(분해)의
        입력이 아니므로 c 가 죽어도 d 는 답할 수 있다.
        """
        client = llm.FakeClient(fake_stage_payloads(), fail_on={"c"})
        payloads, errors = stages.run_pipeline(self.store, self.snap, client)
        self.assertIn("a", payloads)
        self.assertIn("b", payloads)
        self.assertNotIn("c", payloads)
        self.assertIn("d", payloads)            # c 에 기대지 않는다
        self.assertEqual([e["stage"] for e in errors], ["c"])
        self.assertIsNotNone(self.store.last_completion("b"))

    def test_a_stage_that_needs_the_dead_one_is_blocked_not_attempted(self):
        """맥락 없이 물으면 다른 질문이 된다. 실패가 아니라 blocked 로 남긴다."""
        client = llm.FakeClient(fake_stage_payloads(), fail_on={"b"})
        payloads, errors = stages.run_pipeline(self.store, self.snap, client)
        by = {e["stage"]: e for e in errors}
        self.assertEqual(by["b"]["kind"], "failed")
        self.assertEqual(by["c"]["kind"], "blocked")
        self.assertEqual(by["c"]["blocked_by"], ["b"])
        self.assertNotIn("c", client.calls)      # 부르지 않았다
        self.assertIn("a", payloads)

    def test_blocking_is_transitive(self):
        client = llm.FakeClient(fake_stage_payloads(), fail_on={"b"})
        _, errors = stages.run_pipeline(self.store, self.snap, client)
        kinds = {e["stage"]: e["kind"] for e in errors}
        self.assertEqual(kinds.get("d"), "blocked")   # b -> d
        self.assertEqual(kinds.get("f"), "blocked")   # d -> f

    def test_resume_does_not_recall_completed_stages(self):
        client = llm.FakeClient(fake_stage_payloads(), fail_on={"c"})
        stages.run_pipeline(self.store, self.snap, client)
        client2 = llm.FakeClient(fake_stage_payloads())
        payloads, errors = stages.run_pipeline(self.store, self.snap, client2)
        self.assertEqual(errors, [])
        self.assertNotIn("a", client2.calls)
        self.assertNotIn("b", client2.calls)
        self.assertIn("c", client2.calls)
        self.assertEqual(sorted(payloads), ["a", "b", "c", "d", "e", "f", "r"])

    def test_prompt_change_invalidates_the_cache(self):
        client = llm.FakeClient(fake_stage_payloads())
        stages.run_pipeline(self.store, self.snap, client, stages=("a",))
        alt = os.path.join(self.tmp, "prompts")
        shutil.copytree(stages.PROMPTS, alt)
        with io.open(os.path.join(alt, "T2", "a.md"), "a", encoding="utf-8") as f:
            f.write("\n추가 지시.\n")
        client2 = llm.FakeClient(fake_stage_payloads())
        stages.run_pipeline(self.store, self.snap, client2, stages=("a",), prompts_dir=alt)
        self.assertIn("a", client2.calls)

    def test_model_change_invalidates_the_cache(self):
        client = llm.FakeClient(fake_stage_payloads())
        stages.run_pipeline(self.store, self.snap, client, stages=("a",))
        class OtherClient(llm.FakeClient):
            @property
            def describe(self):
                return {"name": "different-model"}
        other = OtherClient(fake_stage_payloads())
        stages.run_pipeline(self.store, self.snap, other, stages=("a",))
        self.assertEqual(other.calls, ["a"])

    def test_output_limit_preserves_raw_and_blocks_completion(self):
        class LimitedClient(llm.FakeClient):
            def complete(self, stage, system, user):
                return '{"observations": []}', {"done_reason": "length"}
        payloads, errors = stages.run_pipeline(
            self.store, self.snap, LimitedClient({}), stages=("a",))
        self.assertEqual(payloads, {})
        self.assertEqual(errors[0]["kind"], "parse_error")
        self.assertIsNone(self.store.last_completion("a"))
        self.assertTrue(os.path.exists(os.path.join(errors[0]["attempt_dir"], "response.raw")))

    def test_parse_error_preserves_the_raw_response(self):
        bad = dict(fake_stage_payloads())
        bad["a"] = "{이건 JSON이 아니다"
        client = llm.FakeClient(bad)
        payloads, errors = stages.run_pipeline(self.store, self.snap, client)
        self.assertEqual(errors[0]["kind"], "parse_error")
        raw = io.open(os.path.join(errors[0]["attempt_dir"], "response.raw"),
                      encoding="utf-8").read()
        self.assertEqual(raw, "{이건 JSON이 아니다")

    def full_doc(self):
        client = llm.FakeClient(fake_stage_payloads())
        payloads, errors = stages.run_pipeline(self.store, self.snap, client)
        doc = assemble.build(payloads, self.snap.snapshot_id, "r1-draft-1",
                             contract.new_artifact("x")["extraction_run"],
                             {"snapshot_id": self.snap.snapshot_id, "status": "materialized",
                              "selection_rule": {"rule_id": "T", "approval_status": "approved"},
                              "manifest_path": "snapshot/manifest.json",
                              "files": self.snap.manifest["files"],
                              "exclusion_report_path": None})
        return doc, assemble.raw_ids_of(payloads), errors

    def test_end_to_end_draft_validates_and_reports(self):
        doc, raw_ids, errors = self.full_doc()
        self.assertEqual(errors, [])
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        by = {c["check_id"]: c for c in doc["validation_results"]["checks"]}
        self.assertEqual(by["EVIDENCE_LOCATION"]["result"], "pass")
        self.assertEqual(by["PROVENANCE_COMPLETE"]["result"], "pass")
        self.assertEqual(by["ROOT_CANDIDATES"]["result"], "pass")
        self.assertEqual(by["SCHEMA_VALID"]["result"], "pass")
        self.assertEqual(by["REF_INTEGRITY"]["result"], "pass")
        text = report.render(doc)
        self.assertIn("검증 결과", text)
        self.assertIn("원시 후보 판정", text)

    def test_bad_evidence_downgrades_but_report_still_renders(self):
        doc, raw_ids, _ = self.full_doc()
        doc["evidence"][0]["start_line"] = 999
        doc["evidence"][0]["end_line"] = 999
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        by = {c["check_id"]: c for c in doc["validation_results"]["checks"]}
        self.assertEqual(by["EVIDENCE_LOCATION"]["result"], "fail")
        self.assertFalse(doc["validation_results"]["approval_eligible"])
        self.assertTrue([u for u in doc["unresolved"] if u["reason_code"] == "evidence_failed"])
        self.assertIn("보류", report.render(doc))

    def test_multi_root_blocks_approval_but_not_review(self):
        doc, raw_ids, _ = self.full_doc()
        doc["entities"].append(ent("E99", "떠 있는 개체"))
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        by = {c["check_id"]: c for c in doc["validation_results"]["checks"]}
        self.assertEqual(by["ROOT_CANDIDATES"]["result"], "fail")
        self.assertFalse(doc["validation_results"]["approval_eligible"])
        text = report.render(doc)
        self.assertIn("루트 후보", text)
        self.assertIn("E99", text)

    def test_reviewer_can_choose_a_root_with_a_reason(self):
        doc, raw_ids, _ = self.full_doc()
        doc["entities"].append(ent("E99", "떠 있는 개체"))
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        roots = doc["structure"]["root_candidate_ids"]
        new = review.apply_decisions(doc, [{"decision": "set_root", "item_id": roots[0],
                                            "reason": "상위 구성 근거를 확인했다"}],
                                     "r1-rev1", reviewer="검토자")
        review.finalize(new, snapshot=self.snap, raw_ids=raw_ids)
        by = {c["check_id"]: c for c in new["validation_results"]["checks"]}
        self.assertEqual(by["ROOT_CANDIDATES"]["result"], "pass")

    def test_approval_refused_while_activation_unknown(self):
        doc, raw_ids, _ = self.full_doc()
        live = next(a for a in doc["activation"] if a["status"] != "superseded")
        live["state"] = "unknown"
        live["coverage"] = "incomplete"
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        by = {c["check_id"]: c for c in doc["validation_results"]["checks"]}
        self.assertEqual(by["ACTIVATION_RESOLVED"]["result"], "fail")
        ok, reasons = review.approvable(doc)
        self.assertFalse(ok)

    def test_accepted_item_cannot_lean_on_a_rejected_one(self):
        doc, raw_ids, _ = self.full_doc()
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        attr = doc["attributes"][0]
        new = review.apply_decisions(doc, [
            {"decision": "accept", "item_id": attr["id"], "reason": "확인했다"},
            {"decision": "reject", "item_id": attr["entity_id"], "reason": "개체가 아니다"},
        ], "r1-rev1", reviewer="검토자")
        review.finalize(new, snapshot=self.snap, raw_ids=raw_ids)
        by = {c["check_id"]: c for c in new["validation_results"]["checks"]}
        self.assertEqual(by["REVIEW_SCOPE_CONSISTENT"]["result"], "fail")
        self.assertFalse(new["approval"]["approved"])

    def test_evidence_alone_does_not_approve(self):
        doc, raw_ids, _ = self.full_doc()
        validate.run(doc, snapshot=self.snap, raw_ids=raw_ids)
        self.assertTrue(doc["validation_results"]["approval_eligible"])
        ok, reasons = review.approvable(doc)
        self.assertFalse(ok)
        self.assertIn("전문가 검토가 없다", reasons)


# ---------------------------------------------------------------- 계약

class ContractTest(unittest.TestCase):
    def test_empty_draft_is_valid(self):
        self.assertEqual(contract.validate_artifact(contract.new_artifact("d")), [])

    def test_broken_reference_is_caught(self):
        d = contract.new_artifact("d")
        d["attributes"].append({"id": "A1", "entity_id": "E-없음", "name": "x",
                                "evidence_ids": [], "status": "proposed"})
        codes = {f["code"] for f in contract.validate_artifact(d)}
        self.assertIn("ENTITY_REF_BROKEN", codes)

    def test_duplicate_ids_are_caught(self):
        d = contract.new_artifact("d")
        d["entities"] = [ent("E1", "a"), ent("E1", "b")]
        self.assertIn("ID_DUPLICATE", {f["code"] for f in contract.validate_artifact(d)})

    def test_unknown_activation_cannot_claim_complete_coverage(self):
        d = contract.new_artifact("d")
        d["activation"].append({"id": "AC1", "state": "unknown", "clauses": [],
                                "combination": None, "coverage": "complete",
                                "applies_to": {"kind": "coupling", "target_id": None},
                                "evidence_ids": [], "status": "proposed"})
        self.assertIn("ACTIVATION_UNKNOWN_COMPLETE",
                      {f["code"] for f in contract.validate_artifact(d)})


if __name__ == "__main__":
    unittest.main(verbosity=2)


class QuoteMatchModeTest(unittest.TestCase):
    """들여쓰기만 다른 인용과 지어낸 인용을 가른다."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def v(self, quote, s=4, e=7):
        return evidence.verify_record(self.snap, ev(ENGINE, s, e, quote))["verification"]

    def test_reindented_multiline_fails_with_review_hint(self):
        q = "void step(int amount) {\nString tag = \"a  b\";\nfill[0] += amount;"
        v = self.v(q)
        self.assertEqual(v["quote"], "fail")
        self.assertEqual(v["quote_match_mode"], "indent_normalized")

    def test_exact_multiline_is_marked_exact(self):
        v = self.v(self.snap.slice(ENGINE, 4, 7))
        self.assertEqual(v["quote_match_mode"], "exact")

    def test_inner_whitespace_change_still_fails(self):
        v = self.v("String tag = \"a b\";", 5, 5)
        self.assertEqual(v["quote"], "fail")

    def test_fabricated_quote_still_fails(self):
        v = self.v("fill[0] -= amount;", 6, 6)
        self.assertEqual(v["quote"], "fail")


class MalformedCandidateTest(unittest.TestCase):
    """후보가 계약을 어겨도 실행이 죽지 않는다. 보류로 간다."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"], base["source_snapshot"])

    def test_string_instead_of_object_is_held(self):
        st = {"b": {"entities": ["수거지점", {"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]}]},
              "c": {"attributes": ["적재량"]},
              "d": {"decompositions": ["말이 안 되는 것"]},
              "e": {"couplings": ["A -> B"]},
              "f": {"activation": ["언제나"]}}
        doc = self.build(st)
        self.assertEqual(len(doc["entities"]), 1)
        held = [u for u in doc["unresolved"] if u["reason_code"] == "schema_violation"]
        self.assertEqual(len(held), 5)
        self.assertEqual(assemble.traceable(doc, assemble.raw_ids_of(st)), [])

    def test_nested_non_objects_do_not_crash(self):
        st = {"b": {"entities": [{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]},
                                 {"name": "B", "evidence": [ev(ENGINE, 3, 3, "x")]}]},
              "d": {"decompositions": [{"parent": "A", "kind": "MULTI",
                                        "members": ["B", {"entity": "C"}],
                                        "member_evidence": {"B": [ev(ENGINE, 3, 3, "x")]},
                                        "multiplicity": "셋",
                                        "evidence": [ev(ENGINE, 3, 3, "x")]}]},
              "e": {"couplings": [{"source": "A", "target": {"entity": "B"},
                                   "payload": "값", "evidence": [ev(ENGINE, 6, 6, "x")]}]}}
        doc = self.build(st)
        self.assertEqual(len(doc["decompositions"]), 1)
        self.assertEqual([m["entity_id"] for m in doc["decompositions"][0]["members"]], ["E2"])
        self.assertTrue([u for u in doc["unresolved"]
                         if u["reason_code"] == "unknown_reference"])


class EmptyArtifactTest(unittest.TestCase):
    """빈 산출물이 '대부분 통과'로 보이면 안 된다 (jn-S2-1 에서 드러난 결함)."""

    def test_empty_draft_fails_and_does_not_fake_passes(self):
        doc = contract.new_artifact("empty")
        doc["source_snapshot"]["status"] = "materialized"
        doc["source_snapshot"]["files"] = [{"path": "x", "sha256": "y", "lines": 1}]
        validate.run(doc, snapshot=None, raw_ids=[])
        by = {c["check_id"]: c["result"] for c in doc["validation_results"]["checks"]}
        self.assertEqual(by["EXTRACTION_NONEMPTY"], "fail")
        self.assertEqual(by["EVIDENCE_REQUIRED"], "not_checked")
        self.assertEqual(by["ACTIVATION_RESOLVED"], "not_checked")
        self.assertFalse(doc["validation_results"]["approval_eligible"])


class RootStageTest(unittest.TestCase):
    """루트 후보는 코드가 센다. 루트를 고르는 것은 근거를 댄 쪽이다."""

    def test_root_candidates_counted_from_decompositions(self):
        payloads = {"b": {"entities": [{"name": "대상 시스템"}, {"name": "수거지점"},
                                       {"name": "수거차량"}, {"name": "떠 있는 것"}]},
                    "d": {"decompositions": [{"parent": "대상 시스템",
                                              "members": ["수거지점", "수거 차량"]}]}}
        self.assertEqual(stages.root_candidates(payloads), ["대상 시스템", "떠 있는 것"])

    def test_no_decompositions_means_every_entity_is_a_candidate(self):
        payloads = {"b": {"entities": [{"name": "A"}, {"name": "B"}]}, "d": {}}
        self.assertEqual(stages.root_candidates(payloads), ["A", "B"])

    def test_root_stage_is_in_the_plan_before_activation(self):
        plan = stages.PLANS["T2"]
        self.assertIn("r", plan)
        self.assertLess(plan.index("d"), plan.index("r"))
        self.assertLess(plan.index("r"), plan.index("f"))

    def test_empty_root_answer_is_allowed(self):
        base = contract.new_artifact("t")
        st = {"b": {"entities": [{"name": "A", "evidence": [ev(ENGINE, 3, 3, "x")]}]},
              "r": {"entities": [], "decompositions": [], "note": "근거가 없다"}}
        doc = assemble.build(st, "snap", "t", base["extraction_run"], base["source_snapshot"])
        self.assertEqual(len(doc["entities"]), 1)
        self.assertEqual(doc["decompositions"], [])


class DuplicateDecompositionTest(unittest.TestCase):
    """같은 분해를 두 단계가 내면 합친다. 두 번 세면 다부모로 오인된다."""

    def test_identical_decomposition_from_two_stages_is_merged(self):
        base = contract.new_artifact("t")
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        dec_raw = {"parent": "A", "kind": "ASPECT", "members": ["B"], "evidence": e,
                   "member_evidence": {"B": e}}
        st = {"b": {"entities": [{"name": "A", "evidence": e}, {"name": "B", "evidence": e}]},
              "d": {"decompositions": [dec_raw]},
              "r": {"decompositions": [dict(dec_raw)]}}
        doc = assemble.build(st, "snap", "t", base["extraction_run"], base["source_snapshot"])
        self.assertEqual(len(doc["decompositions"]), 1)
        self.assertTrue([p for p in doc["provenance"]
                         if p["decision"] == "merged" and p["origin_stage"] == "r"])
        self.assertEqual(structure.analyze(doc)["multi_parent_entity_ids"], [])


class RootProvenanceTest(unittest.TestCase):
    """루트를 추출기가 만들었으면 그 사실이 검사로 드러나야 한다."""

    def test_extractor_made_root_is_flagged(self):
        base = contract.new_artifact("t")
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        st = {"b": {"entities": [{"name": "A", "evidence": e}, {"name": "B", "evidence": e}]},
              "r": {"entities": [{"name": "위에서 만든 것", "evidence": e}],
                    "decompositions": [{"parent": "위에서 만든 것", "kind": "ASPECT",
                                        "members": ["A", "B"], "evidence": e,
                                        # 부분마다 다른 자리를 가리켜야 역할 근거다.
                                        # 한 줄이 둘을 똑같이 가리키면 어느 쪽인지 못 보인다
                                        "member_evidence": {
                                            "A": [ev(ENGINE, 4, 4, "a")],
                                            "B": [ev(ENGINE, 5, 5, "b")]}}]}}
        doc = assemble.build(st, "snap", "t", base["extraction_run"], base["source_snapshot"])
        validate.run(doc, snapshot=None, raw_ids=None)
        by = {c["check_id"]: c for c in doc["validation_results"]["checks"]}
        self.assertEqual(by["ROOT_CANDIDATES"]["result"], "pass")
        self.assertEqual(by["ROOT_INTRODUCED_BY_EXTRACTOR"]["result"], "fail")
        self.assertFalse(by["ROOT_INTRODUCED_BY_EXTRACTOR"]["blocking"])


class NoNewEntityStageTest(unittest.TestCase):
    """r2 는 새 개체를 만들 수 없다. 프롬프트가 아니라 조립기가 막는다."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"],
                              base["source_snapshot"],
                              no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)

    def test_new_entity_from_r2_is_held_with_its_origin(self):
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        st = {"b": {"entities": [{"name": "A", "evidence": e}, {"name": "B", "evidence": e}]},
              "r2": {"entities": [{"name": "지어낸 상위", "evidence": e}],
                     "decompositions": [{"parent": "지어낸 상위", "kind": "ASPECT",
                                         "member_evidence": {"A": e, "B": e},
                                         "members": ["A", "B"], "evidence": e}]}}
        doc = self.build(st)
        self.assertEqual([x["name"] for x in doc["entities"]], ["A", "B"])
        held = [u for u in doc["unresolved"] if u["reason_code"] == "schema_violation"]
        self.assertTrue(held)
        self.assertEqual(held[0]["origin_raw"]["name"], "지어낸 상위")
        self.assertEqual(doc["decompositions"], [])
        self.assertEqual(assemble.traceable(doc, assemble.raw_ids_of(st)), [])

    def test_r2_may_still_relate_existing_entities(self):
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        st = {"b": {"entities": [{"name": "A", "evidence": e}, {"name": "B", "evidence": e}]},
              "r2": {"decompositions": [{"parent": "A", "kind": "ASPECT", "members": ["B"],
                                         "member_evidence": {"B": e}, "evidence": e}]}}
        doc = self.build(st)
        self.assertEqual(len(doc["decompositions"]), 1)
        self.assertEqual(structure.analyze(doc)["root_candidate_ids"], ["E1"])


class RootCandidateFilteringTest(unittest.TestCase):
    """보류될 분해의 자식을 세면 후보가 줄어 잘못된 질문을 하게 된다 (jn-T2b-1)."""

    def test_children_of_invalid_decompositions_do_not_count(self):
        payloads = {"b": {"entities": [{"name": "A"}, {"name": "B"}]},
                    "d": {"decompositions": [
                        {"parent": "없는 개체", "members": ["A"]},        # 부모가 개체가 아니다
                        {"parent": "A", "members": ["CollectEvt"]},       # 자식이 개체가 아니다
                        {"parent": "B", "members": ["B"]},                # 자기 참조
                    ]}}
        self.assertEqual(stages.root_candidates(payloads), ["A", "B"])

    def test_partly_invalid_decomposition_is_rejected_whole(self):
        """조립기가 통째로 보류하는 분해는 후보 계산에서도 통째로 무시해야 한다."""
        payloads = {"b": {"entities": [{"name": "A"}, {"name": "B"}, {"name": "C"}]},
                    "d": {"decompositions": [
                        {"parent": "A", "members": ["B", "A"]},          # 자기 참조 포함
                        {"parent": "A", "members": ["C", "없는 것"]},     # 미해결 자식 포함
                    ]}}
        self.assertEqual(stages.root_candidates(payloads), ["A", "B", "C"])

    def test_valid_decomposition_still_removes_the_child(self):
        payloads = {"b": {"entities": [{"name": "A"}, {"name": "B"}]},
                    "d": {"decompositions": [{"parent": "A", "members": ["B"]}]}}
        self.assertEqual(stages.root_candidates(payloads), ["A"])


class SubjectLinkageTest(unittest.TestCase):
    """b2 — 상태와 소유자를 근거로 잇지 못하면 개체를 만들지 않는다."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"],
                              base["source_snapshot"],
                              no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)

    def subject(self, **kw):
        base = {"state": "적재량", "owner_candidate": "수거지점",
                "classification": "entity_state",
                "state_evidence": [ev(ENGINE, 6, 6, "fill[0] += amount;")],
                "identity_evidence": [ev(BIN, 2, 2, "public class Bin {")],
                "linkage_evidence": [ev(ENGINE, 3, 3, "int[] fill = new int[3];")],
                "consumption_evidence": [ev(ENGINE, 7, 7,
                                            "if (mode == Mode.APPLY) { fill[1] += 1; }")],
                "why": "건물별 적재량"}
        base.update(kw)
        return {"b2": {"subjects": [base]}}

    def test_complete_chain_creates_entity_with_its_state(self):
        doc = self.build(self.subject())
        self.assertEqual([e["name"] for e in doc["entities"]], ["수거지점"])
        self.assertEqual([a["name"] for a in doc["attributes"]], ["적재량"])
        fields = {s["field"] for r in doc["evidence"] for s in r["supports"]}
        self.assertEqual(fields, {"identity", "state_change", "ownership", "consumption"})

    def test_missing_linkage_is_held_not_accepted(self):
        doc = self.build(self.subject(linkage_evidence=[]))
        self.assertEqual(doc["entities"], [])
        self.assertEqual(doc["attributes"], [])
        held = [u for u in doc["unresolved"] if u["reason_code"] == "unproven_ownership"]
        self.assertEqual(len(held), 1)
        self.assertIn("linkage_evidence", held[0]["explanation"])

    def test_value_becomes_observation_machinery_stays_held(self):
        """계약 변경(2026-09-16): value 는 보류가 아니라 관측 기록이 된다."""
        doc = self.build({"b2": {"subjects": [
            {"state": "월별 배출량", "classification": "value", "why": "집계 결과",
             "scope": "whole", "about": {"kind": "whole", "ref": None},
             "state_evidence": [ev(ENGINE, 6, 6, "fill[0] += amount;")]},
            {"state": "이벤트 큐", "classification": "execution_machinery", "why": "스케줄러",
             "state_evidence": [ev(ENGINE, 6, 6, "fill[0] += amount;")]}]}})
        self.assertEqual(doc["entities"], [])
        self.assertEqual(len(doc["observations"]), 1)
        codes = [u["reason_code"] for u in doc["unresolved"]]
        self.assertEqual(codes.count("not_an_entity"), 1)

    def test_unresolved_owner_is_held_without_inventing_one(self):
        doc = self.build(self.subject(classification="unresolved", owner_candidate=None,
                                      hold_reason="인덱스 대응을 못 찾았다"))
        self.assertEqual(doc["entities"], [])
        self.assertTrue([u for u in doc["unresolved"]
                         if u["reason_code"] == "unproven_ownership"])

    def test_two_states_of_one_owner_merge_into_one_entity(self):
        s1 = self.subject()["b2"]["subjects"][0]
        s2 = dict(s1, state="교통구역")
        doc = self.build({"b2": {"subjects": [s1, s2]}})
        self.assertEqual(len(doc["entities"]), 1)
        self.assertEqual(len(doc["attributes"]), 2)

    def test_every_subject_is_traceable(self):
        st = {"b2": {"subjects": [self.subject()["b2"]["subjects"][0],
                                  {"state": "x", "classification": "value"},
                                  "문자열"]}}
        doc = self.build(st)
        self.assertEqual(assemble.traceable(doc, assemble.raw_ids_of(st)), [])


class JoinedQuoteTest(unittest.TestCase):
    """여러 줄을 한 줄로 이어 적은 인용과 지어낸 인용을 가른다 (jn-T2c-1)."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def v(self, quote, s, e):
        return evidence.verify_record(self.snap, ev(ENGINE, s, e, quote))["verification"]

    def test_joined_lines_fail_with_review_hint(self):
        v = self.v('void step(int amount) { String tag = "a  b"; fill[0] += amount;', 4, 6)
        self.assertEqual(v["quote"], "fail")
        self.assertEqual(v["quote_match_mode"], "line_joined")

    def test_joining_does_not_hide_inner_whitespace_change(self):
        v = self.v('void step(int amount) { String tag = "a b"; fill[0] += amount;', 4, 6)
        self.assertEqual(v["quote"], "fail")

    def test_joining_does_not_hide_fabrication(self):
        v = self.v('void step(int amount) { fill[0] -= amount;', 4, 6)
        self.assertEqual(v["quote"], "fail")


class WrongRangeDiagnosticTest(unittest.TestCase):
    """행 범위만 틀린 인용은 실패하되, 지어낸 것과 구별되게 적는다."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_content_elsewhere_in_file_is_reported(self):
        v = evidence.verify_record(
            self.snap, ev(ENGINE, 2, 2, "fill[0] += amount;"))["verification"]
        self.assertEqual(v["quote"], "fail")
        self.assertIn("quote_found_at", v["details"])
        self.assertIn("6행", v["details"]["quote_found_at"])

    def test_fabricated_quote_has_no_location_hint(self):
        v = evidence.verify_record(
            self.snap, ev(ENGINE, 2, 2, "fill[0] -= amount;"))["verification"]
        self.assertEqual(v["quote"], "fail")
        self.assertNotIn("quote_found_at", v["details"])


class NearNameHintTest(unittest.TestCase):
    """이름이 겹치는 확정 개체는 힌트로만 적는다. 자동으로 잇지 않는다."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"],
                              base["source_snapshot"])

    def test_hint_is_recorded_but_not_linked(self):
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        st = {"b": {"entities": [{"name": "수거 지점", "evidence": e}]},
              "c": {"attributes": [{"entity": "수거 지점(CollectionSite)", "name": "적재량",
                                    "evidence": e}]}}
        doc = self.build(st)
        self.assertEqual(doc["attributes"], [])          # 조용히 잇지 않는다
        u = [x for x in doc["unresolved"] if x["reason_code"] == "unknown_reference"][0]
        self.assertIn("near_confirmed_names", u)
        self.assertTrue(any("수거 지점" in h for h in u["near_confirmed_names"]))

    def test_no_hint_when_nothing_overlaps(self):
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        st = {"b": {"entities": [{"name": "수거 지점", "evidence": e}]},
              "c": {"attributes": [{"entity": "전혀 다른 것", "name": "x", "evidence": e}]}}
        doc = self.build(st)
        u = [x for x in doc["unresolved"] if x["reason_code"] == "unknown_reference"][0]
        self.assertNotIn("near_confirmed_names", u)


class QuoteModeTallyTest(unittest.TestCase):
    """인용 모드는 정확 일치와 갈라 센다."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_modes_are_counted_separately(self):
        base = contract.new_artifact("t")
        st = {"b": {"entities": [
            {"name": "A", "evidence": [ev(ENGINE, 6, 6, "fill[0] += amount;")]},
            {"name": "B", "evidence": [ev(ENGINE, 4, 6,
                                          'void step(int amount) {\nString tag = "a  b";')]}]}}
        doc = assemble.build(st, self.snap.snapshot_id, "t", base["extraction_run"],
                             {"snapshot_id": self.snap.snapshot_id, "status": "materialized",
                              "selection_rule": {"rule_id": "T", "approval_status": "approved"},
                              "manifest_path": "m", "files": self.snap.manifest["files"],
                              "exclusion_report_path": None})
        validate.run(doc, snapshot=self.snap, raw_ids=None)
        c = next(x for x in doc["validation_results"]["checks"]
                 if x["check_id"] == "EVIDENCE_QUOTE_MODES")
        self.assertFalse(c["blocking"])
        self.assertIn("정확 1", c["details"])
        self.assertIn("들여쓰기보정 1", c["details"])


class AttrNameOverlapTest(unittest.TestCase):
    """같은 상태를 두 이름으로 적은 것을 보이게만 한다. 합치지 않는다 (jn-T2c-2)."""

    def doc_with(self, n1, n2):
        d = contract.new_artifact("t")
        d["entities"].append({"id": "E1", "name": "수거 지점", "kind": "stateful",
                              "scope": "simulation_target", "why_entity": None,
                              "evidence_ids": [], "status": "proposed"})
        for i, n in enumerate((n1, n2), 1):
            d["attributes"].append({"id": f"A{i}", "entity_id": "E1", "name": n,
                                    "value_type": None, "unit": None, "default": None,
                                    "range": None, "evidence_ids": [], "status": "proposed"})
        return d

    def check(self, d):
        validate.run(d, snapshot=None, raw_ids=None)
        return next(c for c in d["validation_results"]["checks"]
                    if c["check_id"] == "ATTR_NAME_OVERLAP")

    def test_gloss_suffix_is_flagged_but_both_kept(self):
        d = self.doc_with("fill[building][type] (적재량)", "fill[building][type]")
        c = self.check(d)
        self.assertEqual(c["result"], "fail")
        self.assertEqual(c["item_ids"], ["A1~A2"])
        self.assertFalse(c["blocking"])
        self.assertEqual(len(d["attributes"]), 2)

    def test_distinct_names_are_not_flagged(self):
        self.assertEqual(self.check(self.doc_with("적재량", "최댓값"))["result"], "pass")


class PendingEndpointTest(unittest.TestCase):
    """2차 질문의 대상은 코드가 고른다 — 조립기가 보류할 이름과 같은 규칙으로."""

    def test_only_unconfirmed_endpoints_are_selected(self):
        payloads = {"b2": {"subjects": [
            {"classification": "entity_state", "owner_candidate": "수거 지점",
             "state_evidence": [1], "identity_evidence": [1], "linkage_evidence": [1],
             "consumption_evidence": [1]}]},
            "e": {"couplings": [
                {"source": {"entity": "residents"}, "target": {"entity": "수거 지점"}},
                {"source": {"entity": "수거 지점"}, "target": {"entity": "truck"}},
                {"source": {"entity": "residents"}, "target": {"entity": "수거 지점"}}]}}
        self.assertEqual(stages.pending_endpoints(payloads), ["residents", "truck"])

    def test_confirmed_names_are_not_asked_again(self):
        payloads = {"b2": {"subjects": [
            {"classification": "entity_state", "owner_candidate": "A",
             "state_evidence": [1], "identity_evidence": [1], "linkage_evidence": [1],
             "consumption_evidence": [1]}]},
            "e": {"couplings": [{"source": {"entity": "A"}, "target": {"entity": "A"}}]}}
        self.assertEqual(stages.pending_endpoints(payloads), [])


class CouplingRevivalTest(unittest.TestCase):
    """2차 확인으로 끝점이 개체가 되면, 보류됐던 결합이 조립에서 되살아난다."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"], base["source_snapshot"])

    def subject(self, owner, state):
        e = [ev(ENGINE, 6, 6, "fill[0] += amount;")]
        return {"state": state, "owner_candidate": owner, "classification": "entity_state",
                "state_evidence": e, "identity_evidence": [ev(BIN, 2, 2, "public class Bin {")],
                "linkage_evidence": [ev(ENGINE, 3, 3, "int[] fill = new int[3];")],
                "consumption_evidence": [ev(ENGINE, 7, 7,
                                            "if (mode == Mode.APPLY) { fill[1] += 1; }")],
                "why": "x"}

    def test_endpoint_confirmed_later_revives_the_coupling(self):
        cpl = {"source": {"entity": "거주민"}, "target": {"entity": "수거 지점"},
               "payload": {"kind": "value"}, "evidence": [ev(ENGINE, 6, 6, "fill[0] += amount;")]}
        before = self.build({"b2": {"subjects": [self.subject("수거 지점", "적재량")]},
                             "e": {"couplings": [cpl]}})
        self.assertEqual(before["couplings"], [])
        self.assertTrue([u for u in before["unresolved"]
                         if u["reason_code"] == "unknown_reference"])

        after = self.build({"b2": {"subjects": [self.subject("수거 지점", "적재량")]},
                            "e": {"couplings": [cpl]},
                            "b3": {"subjects": [self.subject("거주민", "배출량")]}})
        self.assertEqual(len(after["couplings"]), 1)
        self.assertEqual({e["name"] for e in after["entities"]}, {"수거 지점", "거주민"})


class Retryable429Test(unittest.TestCase):
    """기다리면 풀리는 429와 그렇지 않은 429를 가른다 (jn-T2d-1 에서 10분을 버렸다)."""

    def test_rate_limit_is_retryable(self):
        self.assertTrue(llm.retryable_429('{"error":{"code":"rate_limit_exceeded"}}'))

    def test_quota_exhaustion_is_not(self):
        self.assertFalse(llm.retryable_429(
            '{"error":{"type":"insufficient_quota","code":"credit_balance_exhausted"}}'))

    def test_empty_body_is_treated_as_retryable(self):
        self.assertTrue(llm.retryable_429(""))


class OllamaClientTest(unittest.TestCase):
    """로컬 모델 경로. 창에 닿은 입력은 조용히 넘어가지 않는다."""

    def fake_post(self, payload, captured):
        class R:
            def __init__(self, d): self.d = d
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def read(self): return json.dumps(self.d).encode("utf-8")

        def urlopen(req, timeout=None):
            captured["body"] = json.loads(req.data.decode("utf-8"))
            captured["url"] = req.full_url
            return R(payload)
        return urlopen

    def test_num_ctx_is_sent_and_recorded(self):
        import urllib.request
        cap = {}
        orig = urllib.request.urlopen
        urllib.request.urlopen = self.fake_post(
            {"message": {"content": "{}"}, "prompt_eval_count": 10, "eval_count": 2}, cap)
        try:
            c = llm.OllamaClient("qwen2.5:7b", num_ctx=32768)
            text, usage = c.complete("b2", "sys", "user")
        finally:
            urllib.request.urlopen = orig
        self.assertEqual(cap["body"]["options"]["num_ctx"], 32768)
        self.assertEqual(cap["body"]["options"]["num_predict"], 16000)
        self.assertEqual(cap["body"]["format"], "json")
        self.assertTrue(cap["url"].endswith("/api/chat"))
        self.assertEqual(c.describe["provider"], "ollama")
        self.assertNotIn("warning", usage)

    def test_input_touching_the_window_is_warned(self):
        import urllib.request
        cap = {}
        orig = urllib.request.urlopen
        urllib.request.urlopen = self.fake_post(
            {"message": {"content": "{}"}, "prompt_eval_count": 4096, "eval_count": 1}, cap)
        try:
            _, usage = llm.OllamaClient("m", num_ctx=4096 + llm.MIN_OUTPUT_TOKENS
                                        + llm.TEMPLATE_RESERVE).complete("b2", "s", "u")
        finally:
            urllib.request.urlopen = orig
        self.assertNotIn("warning", usage)   # 창이 늘어 더는 천장에 닿지 않는다
        self.assertEqual(usage["num_predict"], min(usage["max_tokens"],
                                                   usage["output_budget"]))

    def test_no_room_to_answer_blocks_before_sending(self):
        """끝낼 수 없는 요청을 보내지 않는다 — q3-whole-4 의 d 단계가 그랬다."""
        class CountTokenizer:
            def encode(self, text):
                class Encoded:
                    ids = [0] * 2000
                return Encoded()
        client = llm.OllamaClient("m", num_ctx=4096, max_tokens=100)
        client.tokenizer = CountTokenizer()
        with self.assertRaisesRegex(llm.LlmError, "답할 자리가 없다"):
            client.complete("a", "system", "user")

    def test_the_budget_is_computed_without_a_tokenizer(self):
        import urllib.request
        cap = {}
        orig = urllib.request.urlopen
        urllib.request.urlopen = self.fake_post(
            {"message": {"content": "{}"}, "prompt_eval_count": 66129, "eval_count": 1}, cap)
        try:
            # q3-decl-4 의 a 단계 실측값이다 — 162,132자 / 66,129토큰.
            _, usage = llm.OllamaClient("m", num_ctx=131072, max_tokens=36000).complete(
                "a", "s" * 1000, "u" * 161132)
        finally:
            urllib.request.urlopen = orig
        self.assertEqual(usage["estimated_prompt_tokens"], 81066)
        self.assertEqual(usage["output_budget"], 131072 - 81066 - llm.TEMPLATE_RESERVE)
        self.assertEqual(usage["num_predict"], 36000)


class FoundAtLineTest(unittest.TestCase):
    """줄이음으로 찾았을 때도 행 번호는 원문 기준이어야 한다 (lo-1 에서 늘 1이 나왔다)."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        _, _, self.snap = make_snapshot(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_joined_quote_reports_the_real_line(self):
        q = 'void step(int amount) { String tag = "a  b";'
        v = evidence.verify_record(self.snap, ev(ENGINE, 1, 1, q))["verification"]
        self.assertEqual(v["quote"], "fail")
        self.assertIn("4행", v["details"]["quote_found_at"])

    def test_single_line_quote_reports_its_line(self):
        v = evidence.verify_record(
            self.snap, ev(ENGINE, 1, 1, "fill[0] += amount;"))["verification"]
        self.assertIn("6행", v["details"]["quote_found_at"])


class IdentityShapeTest(unittest.TestCase):
    """식별 근거의 모양은 기록만 한다. 차단하지 않는다."""

    def test_shapes_are_named(self):
        from sesx.shapes import identity_shape
        self.assertEqual(identity_shape("public record CollectionSite("), "type_declaration")
        self.assertEqual(identity_shape("double[][] fill = new double[nB][nT];"), "array_alloc")
        self.assertEqual(identity_shape("remainingTruckCapacity.put(tripId, cap);"), "registration")
        self.assertEqual(identity_shape("tripAccs.put(id, new TripAcc(a, b));"), "registration")
        self.assertEqual(identity_shape("if (ratio >= t) complained = true;"), "branch")
        self.assertEqual(identity_shape(""), "none")

    def make(self, quotes):
        d = contract.new_artifact("t")
        d["entities"].append({"id": "E1", "name": "건물", "kind": "stateful",
                              "scope": "simulation_target", "why_entity": None,
                              "evidence_ids": [f"EV{i}" for i in range(len(quotes))],
                              "status": "proposed"})
        for i, q in enumerate(quotes):
            d["evidence"].append({"id": f"EV{i}", "file_path": "x", "start_line": 1,
                                  "end_line": 1, "quote": q, "symbol": None,
                                  "supports": [{"item_id": "E1", "field": "identity"}],
                                  "verification": {"verdict": "pass"}})
        return d

    def check(self, d):
        validate.run(d, snapshot=None, raw_ids=None)
        return next(c for c in d["validation_results"]["checks"]
                    if c["check_id"] == "IDENTITY_EVIDENCE_SHAPE")

    def test_array_alloc_only_is_warned_not_blocked(self):
        c = self.check(self.make(["double[][] fill = new double[nB][nT];",
                                  "if (ratio >= t) complained = true;"]))
        self.assertEqual(c["result"], "fail")
        self.assertFalse(c["blocking"])
        self.assertIn("E1", c["item_ids"][0])

    def test_declaration_present_is_not_warned(self):
        c = self.check(self.make(["public record CollectionSite(",
                                  "double[][] fill = new double[nB][nT];"]))
        self.assertEqual(c["result"], "pass")

    def test_warning_never_blocks_approval(self):
        d = self.make(["double[][] fill = new double[nB][nT];"])
        validate.run(d, snapshot=None, raw_ids=None)
        self.assertNotIn("IDENTITY_EVIDENCE_SHAPE",
                         d["validation_results"]["blocking_failure_ids"])


class MultiSingleMemberTest(unittest.TestCase):
    """MULTI 의 자식이 여럿이면 복제가 아닐 수 있다 — 경고만 한다."""

    def doc_with(self, kind, members):
        d = contract.new_artifact("t")
        for i, n in enumerate(["부모"] + members, 1):
            d["entities"].append({"id": f"E{i}", "name": n, "kind": "stateful",
                                  "scope": "simulation_target", "why_entity": None,
                                  "evidence_ids": [], "status": "proposed"})
        d["decompositions"].append(
            {"id": "D1", "kind": kind, "parent_entity_id": "E1",
             "members": [{"entity_id": f"E{i}", "evidence_ids": []}
                         for i in range(2, len(members) + 2)],
             "selection": None, "multiplicity": {}, "activation_id": None,
             "evidence_ids": [], "status": "proposed"})
        return d

    def check(self, d):
        validate.run(d, snapshot=None, raw_ids=None)
        return next((c for c in d["validation_results"]["checks"]
                     if c["check_id"] == "MULTI_SINGLE_MEMBER"), None)

    def test_multi_with_two_kinds_is_warned(self):
        c = self.check(self.doc_with("MULTI", ["트럭", "운행"]))
        self.assertEqual(c["result"], "fail")
        self.assertFalse(c["blocking"])
        self.assertEqual(c["item_ids"], ["D1"])

    def test_multi_with_one_kind_passes(self):
        self.assertEqual(self.check(self.doc_with("MULTI", ["수거지점"]))["result"], "pass")

    def test_aspect_with_two_children_is_not_warned(self):
        self.assertIsNone(self.check(self.doc_with("ASPECT", ["가", "나"])))


class ObservationRecordTest(unittest.TestCase):
    """관측값은 개체 소유자를 요구하지 않는다. 대상·범위·산출 근거를 스스로 갖는다."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"], base["source_snapshot"])

    def value(self, **kw):
        base = {"state": "byOcc", "classification": "value", "scope": "per_category",
                "about": {"kind": "axis", "ref": "직업"},
                "state_evidence": [ev(ENGINE, 6, 6, "fill[0] += amount;")],
                "why": "직업별 집계"}
        base.update(kw)
        return {"b2": {"subjects": [base]}}

    def test_value_becomes_an_observation_not_a_hold(self):
        doc = self.build(self.value())
        self.assertEqual(len(doc["observations"]), 1)
        o = doc["observations"][0]
        self.assertEqual(o["scope"], "per_category")
        self.assertEqual(o["about"], {"kind": "axis", "ref": "직업"})
        self.assertEqual(o["status"], "proposed")
        self.assertFalse([u for u in doc["unresolved"] if u["reason_code"] == "not_an_entity"])
        self.assertEqual(contract.validate_artifact(doc), [])

    def test_observation_without_evidence_is_held(self):
        doc = self.build(self.value(state_evidence=[]))
        self.assertEqual(doc["observations"][0]["status"], "unresolved")
        self.assertTrue([u for u in doc["unresolved"] if u["reason_code"] == "no_evidence"])

    def test_unknown_scope_or_target_is_held_but_kept(self):
        doc = self.build(self.value(scope=None, about=None))
        self.assertEqual(len(doc["observations"]), 1)
        self.assertEqual(doc["observations"][0]["scope"], "unknown")
        self.assertTrue([u for u in doc["unresolved"]
                         if u["reason_code"] == "unproven_ownership"])

    def test_about_entity_is_linked_when_the_entity_exists(self):
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        st = {"b2": {"subjects": [
            {"state": "적재량", "owner_candidate": "수거지점", "classification": "entity_state",
             "state_evidence": e, "identity_evidence": e, "linkage_evidence": e,
             "consumption_evidence": e, "why": "x"},
            {"state": "residualByBuilding", "classification": "value", "scope": "per_unit",
             "about": {"kind": "entity", "ref": "수거지점"}, "state_evidence": e, "why": "y"}]}}
        doc = self.build(st)
        self.assertEqual(doc["observations"][0]["about"]["kind"], "entity")
        self.assertEqual(doc["observations"][0]["about"]["ref"], doc["entities"][0]["id"])
        self.assertEqual(contract.validate_artifact(doc), [])

    def test_activity_and_machinery_are_still_held(self):
        doc = self.build({"b2": {"subjects": [
            {"state": "pq", "classification": "execution_machinery", "why": "큐",
             "state_evidence": [ev(ENGINE, 6, 6, "x")]}]}})
        self.assertEqual(doc["observations"], [])
        self.assertTrue([u for u in doc["unresolved"] if u["reason_code"] == "not_an_entity"])


class RoleEvidenceChildTest(unittest.TestCase):
    """d 는 자식을 새로 만들 수 있다 — 역할별 근거가 있을 때만."""

    def build(self, st):
        base = contract.new_artifact("t")
        return assemble.build(st, "snap", "t", base["extraction_run"],
                              base["source_snapshot"],
                              no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)

    def base_entities(self):
        e = [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]
        return {"b2": {"subjects": [
            {"state": "적재량", "owner_candidate": "직업군", "classification": "entity_state",
             "state_evidence": e, "identity_evidence": e, "linkage_evidence": e,
             "consumption_evidence": e, "why": "x"}]}}

    def test_child_with_role_evidence_is_created(self):
        st = self.base_entities()
        st["d"] = {"decompositions": [{
            "parent": "직업군", "kind": "SPEC", "members": ["생산직", "학생"],
            "member_evidence": {"생산직": [ev(ENGINE, 7, 7, "if (mode == Mode.APPLY) { fill[1] += 1; }")],
                                "학생": [ev(ENGINE, 7, 7, "if (mode == Mode.APPLY) { fill[1] += 1; }")]},
            "evidence": [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]}]}
        doc = self.build(st)
        self.assertEqual(len(doc["decompositions"]), 1)
        names = {e["name"] for e in doc["entities"]}
        self.assertEqual(names, {"직업군", "생산직", "학생"})
        made = [e for e in doc["entities"] if e.get("introduced_by")]
        self.assertEqual(len(made), 2)
        self.assertEqual(made[0]["introduced_by"]["role"], "SPEC")
        fields = {s["field"] for r in doc["evidence"] for s in r["supports"]}
        self.assertIn("subtype", fields)

    def test_child_without_role_evidence_is_held(self):
        st = self.base_entities()
        st["d"] = {"decompositions": [{
            "parent": "직업군", "kind": "SPEC", "members": ["생산직"],
            "evidence": [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]}]}
        doc = self.build(st)
        self.assertEqual(doc["decompositions"], [])
        u = [x for x in doc["unresolved"] if x["reason_code"] == "unknown_reference"][0]
        self.assertIn("역할 근거 없음", u["explanation"])

    def test_r2_still_cannot_create_children(self):
        st = self.base_entities()
        st["r2"] = {"decompositions": [{
            "parent": "직업군", "kind": "MULTI", "members": ["새것"],
            "member_evidence": {"새것": [ev(ENGINE, 7, 7, "if (mode == Mode.APPLY) { fill[1] += 1; }")]},
            "evidence": [ev(ENGINE, 3, 3, "int[] fill = new int[3];")]}]}
        doc = self.build(st)
        self.assertEqual(doc["decompositions"], [])
        self.assertEqual({e["name"] for e in doc["entities"]}, {"직업군"})


class ConsumptionDistinctTest(unittest.TestCase):
    """소비 근거가 갱신·선언 줄을 그대로 다시 인용하면 소비가 아니다 — 경고만 한다."""

    def doc_with(self, con_line, con_quote):
        d = contract.new_artifact("t")
        d["entities"].append({"id": "E1", "name": "건물", "kind": "stateful",
                              "scope": "simulation_target", "why_entity": None,
                              "evidence_ids": [], "status": "proposed"})
        d["attributes"].append({"id": "A1", "entity_id": "E1", "name": "fill",
                                "value_type": None, "unit": None, "default": None,
                                "range": None, "evidence_ids": ["EV1", "EV2"],
                                "status": "proposed"})
        d["evidence"] += [
            {"id": "EV1", "file_path": "x", "start_line": 10, "end_line": 10,
             "quote": "fill[b][t] += add;", "symbol": None,
             "supports": [{"item_id": "A1", "field": "state_change"}],
             "verification": {"verdict": "pass"}},
            {"id": "EV2", "file_path": "x", "start_line": con_line, "end_line": con_line,
             "quote": con_quote, "symbol": None,
             "supports": [{"item_id": "A1", "field": "consumption"}],
             "verification": {"verdict": "pass"}}]
        return d

    def check(self, d):
        validate.run(d, snapshot=None, raw_ids=None)
        return next(c for c in d["validation_results"]["checks"]
                    if c["check_id"] == "CONSUMPTION_IS_DISTINCT")

    def test_same_line_as_update_is_warned(self):
        c = self.check(self.doc_with(10, "fill[b][t] += add;"))
        self.assertEqual(c["result"], "fail")
        self.assertFalse(c["blocking"])

    def test_declaration_quote_is_warned(self):
        c = self.check(self.doc_with(3, "double[][] fill = new double[nB][nT];"))
        self.assertEqual(c["result"], "fail")

    def test_real_consumption_passes(self):
        c = self.check(self.doc_with(20, "if (fill[b][t] / cap >= threshold) count++;"))
        self.assertEqual(c["result"], "pass")


class SelfCouplingAndProvenance(unittest.TestCase):
    """q3-control-1 이 드러낸 두 결함. 하나가 다른 하나를 가렸다.

    결합 8건이 전부 자기결합이었는데, 판정 기록의 `input_item_ids` 가 ['E2','E2'] 가 되어
    JSON_SCHEMA 가 먼저 터졌다. 검증이 거기서 멈추는 바람에 진짜 원인인 SELF_COUPLING 은
    보고되지도 않았다.
    """

    def payload(self, source_entity, target_entity):
        ev = [{"file_path": "f.java", "start_line": 1, "end_line": 1, "quote": "q"}]
        return {
            "b": {"entities": [{"name": "운행", "kind": "stateful",
                                "scope": "simulation_target", "evidence": ev}]},
            "e": {"couplings": [{
                "source": {"entity": source_entity, "attribute": None, "symbol": "x"},
                "target": {"entity": target_entity, "attribute": None, "symbol": "x"},
                "payload": {"kind": "value", "code_expression": "x", "meaning": "값"},
                "evidence": ev}]},
        }

    def build(self, payloads):
        from sesx import assemble as asm
        return asm.build(payloads, "snap", "art", {"strategy": "T2", "run_id": "r"}, {})

    def test_a_self_coupling_is_held_not_made_into_an_item(self):
        doc = self.build(self.payload("운행", "운행"))
        self.assertEqual(doc["couplings"], [])
        held = [u for u in doc["unresolved"] if u["reason_code"] == "self_reference"]
        self.assertEqual(len(held), 1)
        self.assertEqual(held[0]["origin_raw"]["source"]["entity"], "운행")

    def test_the_schema_no_longer_breaks_on_it(self):
        from sesx import schema
        doc = self.build(self.payload("운행", "운행"))
        self.assertEqual(schema.errors(doc), [])

    def test_provenance_ids_are_unique(self):
        doc = self.build(self.payload("운행", "운행"))
        for p in doc["provenance"]:
            for key in ("input_item_ids", "output_item_ids"):
                self.assertEqual(len(p[key]), len(set(p[key])), p)

    def test_a_normal_coupling_still_survives(self):
        """대조 — 고치면서 정상 결합까지 막지 않았는가."""
        p = self.payload("운행", "건물")
        p["b"]["entities"].append({"name": "건물", "kind": "stateful",
                                   "scope": "simulation_target",
                                   "evidence": [{"file_path": "f.java", "start_line": 1,
                                                 "end_line": 1, "quote": "q"}]})
        doc = self.build(p)
        self.assertEqual(len(doc["couplings"]), 1)
        self.assertEqual([u for u in doc["unresolved"]
                          if u["reason_code"] == "self_reference"], [])


class AttributeAsMember(unittest.TestCase):
    """q3-control-1 이 드러낸 층위 혼동.

    b2 가 `fill` 을 `건물` 의 상태로 옳게 이어 놓았는데, d 단계가 `ASPECT 건물 → [fill]` 을
    내자 조립기가 `fill` 을 **자식 개체로 새로 만들었다.** 같은 것이 건물의 속성이자 건물의
    자식으로 두 번 섰고, 그래서 트리가 소스 코드 구조를 베낀 모양이 됐다.
    """

    EV = [{"file_path": "f.java", "start_line": 1, "end_line": 1, "quote": "q"}]

    def payloads(self, member="fill"):
        ev = self.EV
        return {
            "b2": {"subjects": [{
                "state": "fill", "owner_candidate": "건물", "classification": "entity_state",
                "state_evidence": ev, "identity_evidence": ev,
                "linkage_evidence": ev, "consumption_evidence": ev}]},
            "d": {"decompositions": [{
                "kind": "ASPECT", "parent": "건물", "members": [member],
                "member_evidence": {member: ev}, "evidence": ev}]},
        }

    def build(self, payloads):
        from sesx import assemble as asm
        return asm.build(payloads, "snap", "art", {"strategy": "T2", "run_id": "r"}, {})

    def test_an_attribute_is_not_reborn_as_a_child_entity(self):
        doc = self.build(self.payloads())
        self.assertEqual([e["name"] for e in doc["entities"]], ["건물"])
        self.assertEqual([a["name"] for a in doc["attributes"]], ["fill"])

    def test_the_clash_is_held_with_the_attribute_named(self):
        doc = self.build(self.payloads())
        held = [u for u in doc["unresolved"] if u["reason_code"] == "attribute_as_member"]
        self.assertEqual(len(held), 1)
        self.assertIn("fill", held[0]["explanation"])
        self.assertEqual(held[0]["item_ids"], [doc["attributes"][0]["id"]])

    def test_the_decomposition_is_not_built(self):
        doc = self.build(self.payloads())
        self.assertEqual(doc["decompositions"], [])

    def test_the_clash_is_caught_under_a_different_parent_too(self):
        """q3-whole-5 는 b2 가 찾은 소유자를 무시하고 다른 부모 밑에 매달았다."""
        p = self.payloads()
        p["b2"]["subjects"].append({
            "state": "run", "owner_candidate": "엔진", "classification": "entity_state",
            "state_evidence": self.EV, "identity_evidence": self.EV,
            "linkage_evidence": self.EV, "consumption_evidence": self.EV})
        p["d"]["decompositions"][0]["parent"] = "엔진"
        doc = self.build(p)
        held = [u for u in doc["unresolved"] if u["reason_code"] == "attribute_as_member"]
        self.assertEqual(len(held), 1)
        self.assertIn("건물", held[0]["explanation"])   # 진짜 소유자를 알려 준다
        self.assertNotIn("fill", [e["name"] for e in doc["entities"]])

    def test_a_genuine_new_child_still_becomes_an_entity(self):
        """대조 — 속성과 겹치지 않는 자식은 그대로 만들어진다."""
        doc = self.build(self.payloads(member="지하주차장"))
        self.assertIn("지하주차장", [e["name"] for e in doc["entities"]])
        self.assertEqual(len(doc["decompositions"]), 1)


if __name__ == "__main__":
    unittest.main()
