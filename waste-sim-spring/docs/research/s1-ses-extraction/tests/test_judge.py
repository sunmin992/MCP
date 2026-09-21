# -*- coding: utf-8 -*-
"""후보별 짧은 판정.

바꾼 이유는 실측이다. 단계별 장문 생성은 매 호출이 소스 전문(약 66,000토큰)을 지고 가서
긴 답을 한 번에 받았고, 반복 실행 5회 중 3회가 출력이 잘려 멈췄다. 그리고 모델이 쓴 인용은
73~94%만 통과했다 — 코드가 만든 인용은 100%다.

여기서는 코드가 후보와 근거를 먼저 만들고, 모델은 후보 ID 하나에 한 줄로 답한다. 인용·행
번호·개체 ID 는 코드가 채우므로 모델이 틀릴 자리가 없다.
"""
from __future__ import annotations

import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import assemble, candidates, judge, stages  # noqa: E402

FILES = {
    "a/CollectionSite.java": "public record CollectionSite(String id) {\n}\n",
    "a/Registry.java": ("public class Registry {\n"
                        "    private Map<String, CollectionSite> sites = Map.of();\n"
                        "    void add(CollectionSite s) { }\n}\n"),
    "a/EventQueue.java": "public class EventQueue {\n    void push() { }\n}\n",
}


class Snap:
    snapshot_id = "snap-t"

    def __init__(self, files):
        self.by_path = files

    def has(self, path):
        return path in self.by_path

    def lines(self, path):
        return self.by_path[path].split("\n")


SNAP = Snap(FILES)


class Enumeration(unittest.TestCase):
    def test_candidate_ids_come_from_the_code_site(self):
        ids = [c["cand_id"] for c in candidates.build(SNAP)]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(i.startswith("CD-") for i in ids))

    def test_the_same_snapshot_gives_the_same_ids(self):
        self.assertEqual([c["cand_id"] for c in candidates.build(SNAP)],
                         [c["cand_id"] for c in candidates.build(Snap(dict(FILES)))])

    def test_types_and_collection_fields_are_both_candidates(self):
        self.assertEqual({c["kind"] for c in candidates.build(SNAP)},
                         {"type_declaration", "collection_field"})

    def test_each_candidate_carries_its_sites(self):
        for c in candidates.build(SNAP):
            self.assertTrue(c["sites"])
            for s in c["sites"]:
                self.assertIn(s["file_path"], FILES)

    def test_the_prompt_shows_quotes_but_never_line_numbers(self):
        text = "\n".join(candidates.prompt_rows(candidates.build(SNAP)))
        self.assertIn("CD-", text)
        self.assertNotIn("start_line", text)
        for c in candidates.build(SNAP):
            for s in c["sites"]:
                self.assertNotIn(f"{s['start_line']}행", text)


def answer(cands, verdict="simulation_target", role="object", drop=()):
    rows = [{"cand_id": c["cand_id"], "verdict": verdict, "role": role, "why": "x"}
            for c in cands if c["cand_id"] not in drop]
    return json.dumps({"judgments": rows}, ensure_ascii=False)


class Batching(unittest.TestCase):
    def render(self, batch):
        return "\n".join(candidates.prompt_rows(batch))

    def client(self, responder):
        class C:
            describe = {"name": "fake"}

            def __init__(self):
                self.batches = []

            def complete(self, stage, system, user):
                ids = [ln.split()[0] for ln in user.split("\n") if ln.startswith("CD-")]
                self.batches.append(ids)
                return responder(ids, self)
        return C()

    def cands(self):
        return candidates.build(SNAP)

    def test_every_candidate_gets_a_judgment(self):
        cands = self.cands()
        by = {c["cand_id"]: c for c in cands}
        c = self.client(lambda ids, _: (answer([by[i] for i in ids]),
                                        {"done_reason": "stop"}))
        good, held = judge.judge_batch(c, "j", "sys", self.render, cands)
        self.assertEqual(len(good), len(cands))
        self.assertEqual(held, [])

    def test_a_truncated_batch_is_split_and_retried(self):
        cands = self.cands()
        by = {c["cand_id"]: c for c in cands}
        seen = []

        def responder(ids, _):
            seen.append(len(ids))
            if len(ids) > 1:
                return "{}", {"done_reason": "length"}
            return answer([by[i] for i in ids]), {"done_reason": "stop"}

        good, held = judge.judge_batch(self.client(responder), "j", "sys", self.render,
                                       cands, batch=len(cands))
        self.assertEqual(len(good), len(cands))
        self.assertEqual(held, [])
        self.assertGreater(max(seen), 1)      # 큰 묶음을 먼저 시도했다
        self.assertEqual(min(seen), 1)        # 끝까지 쪼갰다

    def test_a_candidate_that_never_answers_is_held_not_dropped(self):
        cands = self.cands()
        c = self.client(lambda ids, _: ("{}", {"done_reason": "length"}))
        good, held = judge.judge_batch(c, "j", "sys", self.render, cands)
        self.assertEqual(good, [])
        self.assertEqual({h["cand_id"] for h in held}, {x["cand_id"] for x in cands})
        self.assertTrue(all(h["reason"] == "unjudged" for h in held))

    def test_a_missing_row_is_held_while_the_rest_pass(self):
        cands = self.cands()
        by = {c["cand_id"]: c for c in cands}
        drop = {cands[0]["cand_id"]}
        c = self.client(lambda ids, _: (answer([by[i] for i in ids], drop=drop),
                                        {"done_reason": "stop"}))
        good, held = judge.judge_batch(c, "j", "sys", self.render, cands)
        self.assertEqual(len(good), len(cands) - 1)
        self.assertEqual([h["cand_id"] for h in held], sorted(drop))

    def test_an_unknown_verdict_becomes_unclear_not_an_error(self):
        cands = self.cands()[:1]
        by = {c["cand_id"]: c for c in cands}
        c = self.client(lambda ids, _: (answer([by[i] for i in ids], verdict="네"),
                                        {"done_reason": "stop"}))
        good, _ = judge.judge_batch(c, "j", "sys", self.render, cands)
        self.assertEqual(good[0]["verdict"], "unclear")
        self.assertIn("알 수 없는 판정값", good[0]["note"])

    def test_the_retry_condition_is_recorded(self):
        """재시도는 조건을 바꾼다 — 더 작은 묶음은 같은 질문이 아니다."""
        cands = self.cands()
        by = {c["cand_id"]: c for c in cands}

        def responder(ids, _):
            if len(ids) > 1:
                return "{}", {"done_reason": "length"}
            return answer([by[i] for i in ids]), {"done_reason": "stop"}

        good, _ = judge.judge_batch(self.client(responder), "j", "sys", self.render,
                                    cands, batch=len(cands))
        self.assertTrue(all(g["batch"] == 1 for g in good))
        self.assertTrue(all(g["splits"] > 0 for g in good))


class IntoTheArtifact(unittest.TestCase):
    def build(self, payload):
        return assemble.build({"j": payload}, "snap", "art",
                              {"strategy": "T2-judge", "run_id": "r"}, {},
                              no_new_entity_stages=stages.NO_NEW_ENTITY_STAGES)

    def payload(self):
        cands = candidates.build(SNAP)
        js = [{"cand_id": c["cand_id"],
               "verdict": "support_software" if "EventQueue" in c["name"]
               else "simulation_target",
               "role": "none" if "EventQueue" in c["name"] else "object",
               "why": "x", "batch": 3, "splits": 0} for c in cands]
        return judge.to_payload(cands, js, [])

    def test_only_world_candidates_become_entities(self):
        names = [e["name"] for e in self.build(self.payload())["entities"]]
        self.assertIn("CollectionSite", names)
        self.assertNotIn("EventQueue", names)

    def test_the_rejected_candidate_is_held_with_its_reason(self):
        doc = self.build(self.payload())
        held = [u for u in doc["unresolved"] if u["reason_code"] == "not_an_entity"]
        self.assertTrue(any("EventQueue" in u["explanation"] for u in held))

    def test_evidence_comes_from_code_not_the_model(self):
        doc = self.build(self.payload())
        self.assertTrue(doc["evidence"])
        for e in doc["evidence"]:
            self.assertIn(e["file_path"], FILES)
            self.assertIn(e["quote"], FILES[e["file_path"]])

    def test_an_unjudged_candidate_is_held(self):
        cands = candidates.build(SNAP)
        payload = judge.to_payload(cands, [], [{"cand_id": cands[0]["cand_id"],
                                                "name": cands[0]["name"],
                                                "anchor": cands[0]["anchor"],
                                                "reason": "unjudged",
                                                "detail": "묶음에 답이 오지 않았다"}])
        doc = self.build(payload)
        self.assertEqual(len([u for u in doc["unresolved"]
                              if u["reason_code"] == "unjudged"]), 1)

    def test_entities_carry_the_anchor_so_names_do_not_merge(self):
        for e in self.build(self.payload())["entities"]:
            self.assertTrue(e.get("anchor"))


class PlanWiring(unittest.TestCase):
    def test_the_judge_stage_takes_no_prior_context(self):
        """후보는 코드가 만든다. 앞 단계 맥락이 필요 없다 — 그래서 호출이 작다."""
        self.assertEqual(stages.CONTEXT_FROM.get("j"), ())

    def test_the_plan_puts_j_where_n_was(self):
        plan = stages.PLANS["T2-judge"]
        self.assertLess(plan.index("b2"), plan.index("j"))
        self.assertLess(plan.index("j"), plan.index("c"))

    def test_the_prompt_forbids_quotes_and_line_numbers(self):
        text = stages._read(os.path.join(os.path.dirname(HERE), "sesx", "prompts",
                                         stages.PROMPT_FILE["j"]))
        self.assertIn("인용을 적지 마세요", text)
        self.assertIn("행 번호를 적지 마세요", text)


if __name__ == "__main__":
    unittest.main()
