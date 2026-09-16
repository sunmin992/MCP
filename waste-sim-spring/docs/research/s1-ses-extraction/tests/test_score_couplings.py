# -*- coding: utf-8 -*-
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import score_ses  # noqa: E402

REFERENCE = {"couplings": [
    {"from": "거주민.배출", "to": "수거지점.적재"},
    {"from": "수거지점.적재량", "to": "적재초과 판정.판정"}]}


def doc(pairs):
    entities, by_name = [], {}
    for name in sorted({n.split(".")[0] for p in pairs for n in p}):
        eid = f"E{len(entities) + 1}"
        by_name[name] = eid
        entities.append({"id": eid, "name": name})
    attrs, couplings = [], []
    for i, (src, tgt) in enumerate(pairs):
        box = []
        for side in (src, tgt):
            ename, _, aname = side.partition(".")
            aid = None
            if aname:
                aid = f"T{len(attrs) + 1}"
                attrs.append({"id": aid, "entity_id": by_name[ename], "name": aname})
            box.append({"entity_id": by_name[ename], "attribute_id": aid})
        couplings.append({"id": f"C{i}", "source": box[0], "target": box[1],
                          "status": "proposed"})
    return {"entities": entities, "attributes": attrs, "couplings": couplings}


class ScoreTests(unittest.TestCase):
    def test_정확히_맞으면_둘_다_1이다(self):
        r = score_ses.score_couplings(
            doc([("거주민.배출", "수거지점.적재"),
                 ("수거지점.적재량", "적재초과 판정.판정")]), REFERENCE)
        self.assertEqual(r["matched"], 2)
        self.assertEqual(r["precision"], 1.0)
        self.assertEqual(r["recall"], 1.0)

    def test_빠뜨린_것을_센다(self):
        r = score_ses.score_couplings(doc([("거주민.배출", "수거지점.적재")]), REFERENCE)
        self.assertEqual(r["matched"], 1)
        self.assertEqual(r["missed"], ["수거지점.적재량 → 적재초과 판정.판정"])
        self.assertEqual(r["recall"], 0.5)

    def test_없는_것을_낸_것도_센다(self):
        r = score_ses.score_couplings(
            doc([("거주민.배출", "수거지점.적재"), ("트럭.적재", "운행.적재")]), REFERENCE)
        self.assertEqual(r["extra"], ["트럭.적재 → 운행.적재"])
        self.assertEqual(r["precision"], 0.5)

    def test_결합이_없으면_0이다(self):
        r = score_ses.score_couplings(doc([]), REFERENCE)
        self.assertEqual(r["matched"], 0)
        self.assertEqual(r["precision"], 0.0)
        self.assertEqual(r["recall"], 0.0)

    def test_속성이_비면_맞은_것이_아니다(self):
        # 모르는 것을 맞았다고 세지 않는다
        r = score_ses.score_couplings(doc([("거주민", "수거지점")]), REFERENCE)
        self.assertEqual(r["matched"], 0)


if __name__ == "__main__":
    unittest.main()
