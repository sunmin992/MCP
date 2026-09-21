import unittest
from sesx import assemble, stages, structure, validate

EV = [{"file_path": "Model.java", "start_line": 1, "end_line": 1,
       "quote": "system.add(child);"}]


def entity(name):
    return {"name": name, "kind": "boundary", "scope": "simulation_target",
            "identity_evidence": EV, "composition_evidence": EV}


def relation(parent, children):
    return {"parent": parent, "kind": "ASPECT", "members": children,
            "evidence": EV, "member_evidence": {n: EV for n in children}}


def build(payload):
    return assemble.build(payload, "s", "a", {"strategy": "T2-tree"}, {},
                          no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)


class TreeTests(unittest.TestCase):
    def payload(self):
        return {"g": {"entities": [entity("system"), entity("subsystem")]},
                "b2": {"subjects": [{"state": "fill", "classification": "entity_state",
                    "owner_candidate": "site", "state_evidence": EV,
                    "identity_evidence": EV, "linkage_evidence": EV,
                    "consumption_evidence": EV}]},
                "d": {"decompositions": [relation("system", ["subsystem"]),
                                         relation("subsystem", ["site"])]}}

    def test_state_free_parent_connects_state_owner(self):
        p = self.payload()
        doc = build(p)
        a = structure.analyze(doc)
        self.assertEqual(len(a["root_candidate_ids"]), 1)
        self.assertEqual(a["unreachable_entity_ids"], [])
        self.assertEqual(len(doc["attributes"]), 1)
        self.assertEqual(assemble.traceable(doc, assemble.raw_ids_of(p)), [])
        self.assertEqual(stages.entity_names(p), [e["name"] for e in doc["entities"]])
        self.assertEqual(stages.root_candidates(p), ["system"])
        self.assertTrue(all(m["evidence_ids"] for d in doc["decompositions"]
                            for m in d["members"]))
        self.assertTrue(all(a["state"] == "unknown" for a in doc["activation"]))

    def test_unproven_parent_never_created(self):
        for bad in (None, [], [{}], ["claim"]):
            p = self.payload()
            p["g"]["entities"][0]["composition_evidence"] = bad
            doc = build(p)
            self.assertNotIn("system", [e["name"] for e in doc["entities"]])
            self.assertEqual(assemble.traceable(doc, assemble.raw_ids_of(p)), [])

    def test_existing_child_still_needs_link(self):
        p = self.payload()
        p["d"]["decompositions"][0]["member_evidence"] = {}
        self.assertEqual(len(build(p)["decompositions"]), 1)

    def test_aspect_cannot_invent_child(self):
        p = self.payload()
        p["d"]["decompositions"].append(relation("system", ["imagined"]))
        self.assertNotIn("imagined", stages.entity_names(p))

    def test_direct_entities_cannot_bypass_g(self):
        p = self.payload()
        p["r2"] = {"entities": [entity("invented root")]}
        self.assertNotIn("invented root", stages.entity_names(p))

    def test_multi_requires_count_evidence(self):
        p = self.payload()
        d = p["d"]["decompositions"][0]
        d["kind"] = "MULTI"
        self.assertEqual(len(build(p)["decompositions"]), 1)

    def test_disconnected_cycle_and_multiple_parents_fail_tree(self):
        for case in ("disconnected", "cycle", "multiple"):
            p = self.payload()
            if case == "disconnected":
                p["d"]["decompositions"].pop()
            elif case == "cycle":
                p["d"]["decompositions"].append(relation("site", ["system"]))
            else:
                p["d"]["decompositions"].append(relation("system", ["site"]))
            doc = build(p)
            validate.run(doc, raw_ids=assemble.raw_ids_of(p))
            self.assertIn("TREE_COMPLETE", doc["validation_results"]["blocking_failure_ids"])

    def test_old_plan_dependencies_unchanged(self):
        self.assertNotIn("g", stages.dependencies("d"))
        self.assertIn("g", stages.dependencies("d", True))
        self.assertEqual(stages.PLANS["T2-tree"][2], "g")

    def test_connected_tree_check_does_not_approve_meaning(self):
        p = self.payload()
        doc = build(p)
        validate.run(doc, raw_ids=assemble.raw_ids_of(p))
        checks = {c["check_id"]: c for c in doc["validation_results"]["checks"]}
        self.assertEqual(checks["TREE_COMPLETE"]["result"], "pass")
        self.assertFalse(doc["validation_results"]["approval_eligible"])


    def test_nine_stage_pipeline_and_resume(self):
        import json
        import tempfile
        from test_sesx import make_snapshot
        from sesx import llm, run_store
        with tempfile.TemporaryDirectory() as tmp:
            _, _, snap = make_snapshot(tmp)
            store = run_store.RunStore(tmp + "/run")
            responses = {s: json.dumps(self.payload().get(s, {}))
                         for s in stages.PLANS["T2-tree"]}
            client = llm.FakeClient(responses)
            payloads, errors = stages.run_pipeline(store, snap, client, plan="T2-tree")
            self.assertEqual(errors, [])
            self.assertEqual(client.calls, list(stages.PLANS["T2-tree"]))
            self.assertEqual(stages.root_candidates(payloads), ["system"])
            stages.run_pipeline(store, snap, client, plan="T2-tree")
            self.assertEqual(len(client.calls), 9)
