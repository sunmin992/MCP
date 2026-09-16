# -*- coding: utf-8 -*-
import unittest
from sesx import contract, review

EV = [{"file_path": "설계문서.md", "start_line": 1, "end_line": 1,
       "quote": "장량동 생활쓰레기 수거 시뮬레이터"}]


def forest():
    """루트 없는 산출물. 상위 개체 둘이 서로 부모가 아니다."""
    doc = contract.new_artifact("a1")
    doc["entities"] = [
        {"id": "E1", "name": "생활쓰레기 수거 모델", "kind": "boundary",
         "scope": "simulation_target", "status": "proposed", "evidence_ids": []},
        {"id": "E2", "name": "시뮬레이션 결과", "kind": "boundary",
         "scope": "simulation_target", "status": "proposed", "evidence_ids": []},
    ]
    return doc


DECISION = {"decision": "root_designation",
            "root": {"name": "장량동 생활쓰레기 수거 시뮬레이터",
                     "why": "ref-v8 의 루트. 코드에 구문이 없어 사람이 넣는다",
                     "evidence": EV},
            "label": "시스템 구성",
            "members": ["E1", "E2"],
            "reason": "루트 지정"}


class RootDesignationTests(unittest.TestCase):
    def applied(self):
        return review.apply_decisions(forest(), [DECISION], "a2", reviewer="검토자")

    def test_루트_개체가_생긴다(self):
        out = self.applied()
        names = [e["name"] for e in out["entities"]]
        self.assertIn("장량동 생활쓰레기 수거 시뮬레이터", names)

    def test_루트에_사람_출처가_박힌다(self):
        out = self.applied()
        root = next(e for e in out["entities"]
                    if e["name"] == "장량동 생활쓰레기 수거 시뮬레이터")
        self.assertEqual(root["origin"], "human")
        self.assertEqual(root["kind"], "boundary")

    def test_분해가_생기고_축_이름이_붙는다(self):
        out = self.applied()
        self.assertEqual(len(out["decompositions"]), 1)
        d = out["decompositions"][0]
        self.assertEqual(d["label"], "시스템 구성")
        self.assertEqual(d["kind"], "ASPECT")
        self.assertEqual(d["origin"], "human")
        self.assertEqual([m["entity_id"] for m in d["members"]], ["E1", "E2"])

    def test_원본은_바뀌지_않는다(self):
        doc = forest()
        review.apply_decisions(doc, [DECISION], "a2")
        self.assertEqual(len(doc["entities"]), 2)
        self.assertEqual(doc["decompositions"], [])

    def test_없는_구성원을_가리키면_거부한다(self):
        bad = dict(DECISION, members=["E1", "E99"])
        with self.assertRaises(ValueError):
            review.apply_decisions(forest(), [bad], "a2")

    def test_근거_없는_루트를_거부한다(self):
        bad = dict(DECISION, root={"name": "시스템", "why": "", "evidence": []})
        with self.assertRaises(ValueError):
            review.apply_decisions(forest(), [bad], "a2")

    def test_판정이_기록에_남는다(self):
        out = self.applied()
        kinds = [x["decision"] for x in out["review_status"]["log"]]
        self.assertIn("root_designation", kinds)

    def test_리비전이_계약을_통과한다(self):
        out = self.applied()
        codes = [f["code"] for f in contract.validate_artifact(out)]
        self.assertEqual([c for c in codes if "REF_BROKEN" in c or c == "SELF_REFERENCE"],
                         [])


if __name__ == "__main__":
    unittest.main()
