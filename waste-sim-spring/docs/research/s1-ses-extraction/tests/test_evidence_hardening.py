"""Adversarial acceptance tests; fixtures are synthetic, never LLM accuracy evidence."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import extract
from sesx import assemble, contract, evidence, input_policy, llm, review, run_store, schema, snapshot, stages, validate

SOURCE = '''class Simulation {
    Bin bin = new Bin();
    Truck truck = new Truck();
    void step() {
        bin.fill += 2;
        if (bin.fill > 0) {
            truck.load += bin.fill;
            bin.fill = 0;
        }
    }
}
class Bin { int fill; }
class Truck { int load; }
'''
POLICY = {"policy_id": "test", "approval_status": "approved", "include_globs": ["*.java"],
          "default_role_for_included": "simulation_core", "require_classified_roles": True}


def ev(line, field=None):
    return {"file_path": "Simulation.java", "start_line": line, "end_line": line,
            "quote": SOURCE.splitlines()[line-1], "symbol": field or "Simulation"}


def payload():
    active = {"state": "known", "clauses": [{"expression": "bin.fill > 0", "symbol": "fill",
                                               "evidence": [ev(6, "fill")]}],
              "combination": None, "coverage": "complete", "evidence": [ev(6, "fill")]}
    always = {"state": "unconditional", "coverage": "complete", "clauses": [],
              "combination": None, "evidence": [ev(1), ev(2), ev(3)]}
    return {"entities": [
        {"name": "Simulation", "kind": "boundary", "scope": "simulation_target", "why_entity": "contains bin and truck", "evidence": [ev(1)]},
        {"name": "Bin", "kind": "stateful", "scope": "simulation_target", "evidence": [ev(2)],
         "identity_evidence": [ev(2)], "state_evidence": [ev(5, "fill")]},
        {"name": "Truck", "kind": "stateful", "scope": "simulation_target", "evidence": [ev(3)],
         "identity_evidence": [ev(3)], "state_evidence": [ev(7, "load")]},
    ], "attributes": [], "decompositions": [
        {"parent": "Simulation", "kind": "ASPECT", "label": "components", "members": ["Bin", "Truck"],
         "member_evidence": {"Bin": [ev(2)], "Truck": [ev(3)]},
         "evidence": [ev(1)], "activation": always}],
        "couplings": [{"source": {"entity": "Bin", "symbol": "fill", "evidence": [ev(5, "fill")]},
                       "target": {"entity": "Truck", "symbol": "load", "evidence": [ev(7, "load")]},
                       "payload": {"kind": "value", "code_expression": "bin.fill", "evidence": [ev(7, "fill")]},
                       "evidence": [ev(7)], "activation": active}], "activation": [], "disputes": []}


class HardenedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        repo = self.root / "repo"
        repo.mkdir()
        (repo / "Simulation.java").write_text(SOURCE, encoding="utf-8")
        self.repo = repo
        self.decision = input_policy.decide(repo, POLICY)
        snapshot.materialize(repo, self.decision, self.root / "snapshot", "snap-test")
        self.snap = snapshot.Snapshot(self.root / "snapshot")
        self.store = run_store.RunStore(str(self.root / "run"))

    def doc(self):
        raw = {"single": payload()}
        doc = assemble.build(raw, self.snap.snapshot_id, "draft", {
            "validation_profile": "evidence-v1", "status": "completed", "strategy": "S2",
            "expected_stages": ["single"], "stages_completed": ["single"], "errors": []}, {
            "snapshot_id": self.snap.snapshot_id, "status": "materialized",
            "selection_rule": {"rule_id": "test", "approval_status": "approved"},
            "manifest_sha256": self.snap.manifest["manifest_sha256"], "files": self.snap.manifest["files"]})
        return doc, assemble.raw_ids_of(raw)

    def checks(self, doc, ids):
        validate.run(doc, self.snap, ids)
        return {c["check_id"]: c for c in doc["validation_results"]["checks"]}

    def test_positive_traceable_draft_is_not_automatically_accepted(self):
        doc, ids = self.doc()
        checks = self.checks(doc, ids)
        self.assertTrue(doc["validation_results"]["approval_eligible"], checks)
        self.assertFalse(review.approvable(doc)[0])
        self.assertTrue(all(e["file_sha256"] == self.snap.sha256("Simulation.java") for e in doc["evidence"]))

    def test_reference_cannot_be_admitted_by_mislabelling_role(self):
        (self.repo / "reference-ses-v9.json").write_text('{}', encoding="utf-8")
        p = {**POLICY, "include_globs": ["*"]}
        decision = input_policy.decide(self.repo, p)
        self.assertFalse(decision["approved"])
        self.assertNotIn("reference-ses-v9.json", decision["included"])

    def test_empty_input_is_not_approved(self):
        d = input_policy.decide(self.repo, {**POLICY, "include_globs": ["missing/**"]})
        self.assertFalse(d["approved"])

    def test_missing_local_import_is_recorded(self):
        (self.repo / "Simulation.java").write_text('import com.wastesim.Missing;\n' + SOURCE, encoding="utf-8")
        d = input_policy.decide(self.repo, {**POLICY, "check_local_imports": True})
        self.assertIn("LOCAL_IMPORT_MISSING", [b["code"] for b in d["blocks"]])

    def test_snapshot_cannot_overwrite_and_policy_cannot_be_bypassed(self):
        with self.assertRaises(FileExistsError):
            snapshot.materialize(self.repo, self.decision, self.root / "snapshot", "snap-other")
        with self.assertRaises(ValueError):
            snapshot.materialize(self.repo, {**self.decision, "approved": False}, self.root / "other", "x")

    def test_path_escape_is_rejected(self):
        for name in ("../outside", "/absolute", "C:/outside", "a/../b", "a\\b"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                snapshot.safe_path(self.root, name)
        with self.assertRaises(ValueError):
            extract.run_dir("../outside")

    def test_manifest_tampering_is_detected(self):
        m = copy.deepcopy(self.snap.manifest)
        m["policy_id"] = "altered"
        with self.assertRaises(ValueError):
            snapshot.Snapshot(self.snap.dir, m)

    def test_copy_tampering_after_first_read_is_detected(self):
        self.snap.text("Simulation.java")
        Path(self.snap.dir, "files/Simulation.java").write_text("changed", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.snap.text("Simulation.java")

    def test_boolean_line_number_is_not_integer(self):
        self.assertIsNone(self.snap.slice("Simulation.java", True, 1))

    def test_malformed_nested_schema_is_reported_not_crashed(self):
        doc, ids = self.doc()
        doc["couplings"][0]["source"] = "not an object"
        self.assertEqual(self.checks(doc, ids)["JSON_SCHEMA"]["result"], "fail")

    def test_activation_quote_failure_downgrades_condition(self):
        doc, ids = self.doc()
        act = doc["activation"][-1]
        record = next(e for e in doc["evidence"] if e["id"] in act["evidence_ids"])
        record["quote"] = "if (fabricated)"
        self.checks(doc, ids)
        self.assertEqual(act["status"], "unresolved")

    def test_unconditional_without_field_evidence_is_held(self):
        doc, ids = self.doc()
        act = doc["activation"][0]
        act["evidence_ids"] = []
        self.assertEqual(self.checks(doc, ids)["FIELD_EVIDENCE"]["result"], "fail")
        self.assertEqual(act["status"], "unresolved")

    def test_unit_cannot_borrow_unrelated_evidence(self):
        doc, ids = self.doc()
        owner = doc["entities"][1]
        ref = owner["evidence_ids"][0]
        unknown = {"status": "unknown", "value": None, "evidence_ids": []}
        doc["attributes"].append({"id": "AX", "name": "fill", "entity_id": owner["id"],
            "status": "proposed", "evidence_ids": [ref], "unit": {"status": "known", "value": "kg", "evidence_ids": [ref]},
            "default": unknown, "range": unknown})
        self.assertEqual(self.checks(doc, ids)["FIELD_EVIDENCE"]["result"], "fail")

    def test_support_class_is_preserved_as_unresolved_not_entity(self):
        p = payload()
        p["entities"].append({"name": "ChatController", "kind": "stateful", "scope": "support_software", "evidence": [ev(1)]})
        d = assemble.build({"single": p}, "s", "d", {"validation_profile": "evidence-v1"}, {})
        self.assertNotIn("ChatController", [e["name"] for e in d["entities"]])
        self.assertTrue(any(u.get("origin_raw", {}).get("name") == "ChatController" for u in d["unresolved"] if isinstance(u.get("origin_raw"), dict)))

    def test_multiple_roots_never_choose_first(self):
        doc, ids = self.doc()
        doc["decompositions"] = []
        self.checks(doc, ids)
        self.assertIsNone(doc["structure"]["selected_root_id"])

    def test_failed_extraction_cannot_pass_on_partial_good_draft(self):
        doc, ids = self.doc()
        doc["extraction_run"]["status"] = "failed"
        self.assertEqual(self.checks(doc, ids)["EXTRACTION_RUN_CLEAN"]["result"], "fail")

    def test_scope_and_run_state_are_reported_apart(self):
        """실행이 끝난 것과 추출 범위를 채운 것은 다르다. 부분 산출물을 완성으로
        읽히게 두지 않는다."""
        doc, ids = self.doc()
        doc["extraction_run"]["status"] = "partial"
        doc["extraction_run"]["stages_completed"] = []
        c = self.checks(doc, ids)
        self.assertEqual(c["EXTRACTION_SCOPE_MET"]["result"], "fail")
        self.assertEqual(c["EXTRACTION_RUN_CLEAN"]["result"], "fail")
        self.assertIn("single", c["EXTRACTION_SCOPE_MET"]["details"])

    def test_a_clean_partial_run_still_says_the_scope_is_short(self):
        doc, ids = self.doc()
        doc["extraction_run"]["expected_stages"] = ["single", "extra"]
        c = self.checks(doc, ids)
        self.assertEqual(c["EXTRACTION_SCOPE_MET"]["result"], "fail")
        self.assertEqual(c["EXTRACTION_RUN_CLEAN"]["result"], "pass")

    def test_source_identity_mismatch_blocks(self):
        doc, ids = self.doc()
        doc["source_snapshot"]["snapshot_id"] = "wrong"
        self.assertEqual(self.checks(doc, ids)["SOURCE_IDENTITY"]["result"], "fail")

    def test_ambiguous_activation_target_is_not_first_match(self):
        p = payload()
        second = copy.deepcopy(p["couplings"][0])
        second["payload"]["code_expression"] = "other"
        p["couplings"].append(second)
        p["activation"] = [{"target": {"kind": "coupling", "ref": {"source_entity": "Bin", "target_entity": "Truck"}},
                            "state": "unconditional", "coverage": "complete", "evidence": [ev(1)]}]
        doc = assemble.build({"single": p}, "s", "d", {"validation_profile": "evidence-v1"}, {})
        self.assertTrue(any(u["reason_code"] == "unknown_reference" for u in doc["unresolved"]))

    def test_exact_resume_does_not_call_model(self):
        client = llm.FakeClient({"single": json.dumps(payload())})
        stages.run_pipeline(self.store, self.snap, client, plan="S2")
        client.calls.clear()
        stages.run_pipeline(self.store, self.snap, client, plan="S2")
        self.assertEqual(client.calls, [])

    def test_changed_model_blocks_partial_resume_of_old_dependency(self):
        client = llm.FakeClient({s: '{}' for s in stages.PLANS["T2-evidence"]})
        stages.run_pipeline(self.store, self.snap, client, plan="T2-evidence")
        class Changed(llm.FakeClient):
            @property
            def describe(self):
                return {"name": "changed"}
        changed = Changed({})
        p, errors = stages.run_pipeline(self.store, self.snap, changed, plan="T2-evidence", stages=("f",))
        self.assertEqual(errors[0]["kind"], "stale_dependency")
        self.assertEqual(changed.calls, [])
        self.assertEqual(p, {})

    def test_completion_payload_tampering_blocks_reuse(self):
        client = llm.FakeClient({"single": '{}'})
        stages.run_pipeline(self.store, self.snap, client, plan="S2")
        rec = self.store.last_completion("single")
        Path(self.store.dir, rec["payload_path"]).write_text('{"altered":true}', encoding="utf-8")
        client.calls.clear()
        _, errors = stages.run_pipeline(self.store, self.snap, client, plan="S2")
        self.assertEqual(errors[0]["kind"], "integrity")
        self.assertEqual(client.calls, [])

    def test_context_overflow_fails_without_truncating_or_calling(self):
        client = llm.FakeClient({"a": '{"observations":[{"claim":"long observation"}]}'})
        _, errors = stages.run_pipeline(self.store, self.snap, client, plan="T2-evidence", context_limit=1)
        self.assertEqual(errors[0]["kind"], "context_overflow")
        self.assertEqual(client.calls, ["a"])

    def test_provider_length_stop_preserves_raw_and_fails(self):
        class Limited(llm.FakeClient):
            def complete(self, *args):
                return '{}', {"finish_reason": "length"}
        _, errors = stages.run_pipeline(self.store, self.snap, Limited({}), plan="S2")
        self.assertEqual(errors[0]["kind"], "parse_error")
        self.assertTrue(Path(errors[0]["attempt_dir"], "response.raw").exists())

    def test_publishing_never_overwrites_old_artifacts(self):
        root = str(self.root / "publish")
        a = extract._publish(root, "draft", {"artifact_id": "first"})
        before = Path(root, a).read_bytes()
        b = extract._publish(root, "draft", {"artifact_id": "second"})
        self.assertNotEqual(a, b)
        self.assertEqual(Path(root, a).read_bytes(), before)
        self.assertEqual(extract._latest(root)["artifact_id"], "second")

    def test_pipeline_exit_code_does_not_hide_validation_failure(self):
        with patch.object(extract, 'cmd_policy', return_value=0), patch.object(extract, 'cmd_snapshot', return_value=0), \
             patch.object(extract, 'cmd_run', return_value=0), patch.object(extract, 'cmd_validate', return_value=1), \
             patch.object(extract, 'cmd_report', return_value=0):
            self.assertEqual(extract.cmd_pipeline(object()), 1)

    def test_schema_file_matches_executable_contract(self):
        stored = json.loads(Path(schema.__file__).with_name("artifact.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(stored, schema.SCHEMA)


if __name__ == "__main__":
    unittest.main()
