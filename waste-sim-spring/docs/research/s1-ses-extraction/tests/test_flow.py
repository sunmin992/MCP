# -*- coding: utf-8 -*-
import unittest
from sesx import flow


def ev(line, quote):
    return {"file_path": "SimulationEngine.java", "start_line": line,
            "end_line": line, "quote": quote}


B2 = {"subjects": [{
    "state": "fill", "classification": "entity_state", "owner_candidate": "수거지점",
    "state_evidence": [ev(454, "fill[de.building][t] += add")],
    "identity_evidence": [ev(254, "double[][] fill = new double[nB][nT];")],
    "linkage_evidence": [ev(254, "double[][] fill = new double[nB][nT];")],
    "consumption_evidence": [ev(414, "dueTotal += fill[ce.building][t]")]}]}

E1 = {"value_sites": [{
    "value": {"code_expression": "fill[b][t]",
              "declaration": ev(254, "double[][] fill = new double[nB][nT];")},
    "writes": [ev(424, "fill[ce.building][t] *= keepFraction")],
    "reads": [ev(470, "worst = max(worst, fill[ie.building][t]/cap)")],
    "corrections": []}]}


class MergeTests(unittest.TestCase):
    def test_씨앗과_새_자리가_합쳐진다(self):
        sites = flow.merge_sites(B2, E1)
        self.assertEqual(len(sites), 1)
        self.assertEqual(len(sites[0]["writes"]), 2)
        self.assertEqual(len(sites[0]["reads"]), 2)

    def test_씨앗에_출처가_붙는다(self):
        sites = flow.merge_sites(B2, E1)
        froms = {w["start_line"]: w["from"] for w in sites[0]["writes"]}
        self.assertEqual(froms[454], "b2")
        self.assertEqual(froms[424], "e1")

    def test_자리마다_식별자가_있다(self):
        sites = flow.merge_sites(B2, E1)
        ids = [w["site_id"] for w in sites[0]["writes"]]
        self.assertEqual(ids, ["e1:v0:w0", "e1:v0:w1"])

    def test_같은_행을_두_번_내면_하나로_합친다(self):
        e1 = {"value_sites": [{
            "value": {"code_expression": "fill[b][t]", "declaration": None},
            "writes": [ev(454, "fill[de.building][t] += add")],
            "reads": [], "corrections": []}]}
        sites = flow.merge_sites(B2, e1)
        self.assertEqual(len(sites[0]["writes"]), 1)
        self.assertEqual(sites[0]["writes"][0]["from"], "b2")

    def test_정정된_씨앗은_빠진다(self):
        e1 = {"value_sites": [{
            "value": {"code_expression": "fill[b][t]", "declaration": None},
            "writes": [], "reads": [],
            "corrections": [{"site_id": "e1:v0:w0", "why": "이 값의 쓰기가 아니다"}]}]}
        sites = flow.merge_sites(B2, e1)
        self.assertEqual(sites[0]["writes"], [])

    def test_entity_state_가_아닌_값은_빠진다(self):
        b2 = {"subjects": [{"state": "total", "classification": "value",
                            "state_evidence": [ev(1, "total += 1")],
                            "consumption_evidence": []}]}
        self.assertEqual(flow.merge_sites(b2, {"value_sites": []}), [])

    def test_e1_이_비어도_씨앗만으로_선다(self):
        sites = flow.merge_sites(B2, {"value_sites": []})
        self.assertEqual(len(sites[0]["writes"]), 1)
        self.assertEqual(len(sites[0]["reads"]), 1)

    def test_효과를_모르면_unknown_이다(self):
        sites = flow.merge_sites(B2, {"value_sites": []})
        self.assertEqual(sites[0]["writes"][0]["effect"], "unknown")


SITES = [{"value": "fill[b][t]",
          "declaration": ev(254, "double[][] fill = new double[nB][nT];"),
          "writes": [{"site_id": "e1:v0:w0", "file_path": "E.java", "start_line": 454,
                      "end_line": 454, "quote": "fill[de.building][t] += add",
                      "effect": "increase", "from": "b2"},
                     {"site_id": "e1:v0:w1", "file_path": "E.java", "start_line": 424,
                      "end_line": 424, "quote": "fill[ce.building][t] *= keepFraction",
                      "effect": "decrease", "from": "e1"}],
          "reads": [{"site_id": "e1:v0:r0", "file_path": "E.java", "start_line": 414,
                     "end_line": 414, "quote": "dueTotal += fill[ce.building][t]",
                     "effect": "unknown", "from": "b2"},
                    {"site_id": "e1:v0:r1", "file_path": "E.java", "start_line": 470,
                     "end_line": 470, "quote": "worst = max(...)",
                     "effect": "unknown", "from": "e1"}]}]

E2 = {"site_actors": [
    {"site_id": "e1:v0:w0", "entity": "배출", "attribute": None},
    {"site_id": "e1:v0:w1", "entity": "수거", "attribute": None},
    # 414행의 수거 판단은 수거 자신이 한다 — 별도 개체가 아니다
    {"site_id": "e1:v0:r0", "entity": "수거", "attribute": None},
    {"site_id": "e1:v0:r1", "entity": "넘침판정", "attribute": None}]}


class DeriveTests(unittest.TestCase):
    def pairs(self, result):
        return {(c["source"]["entity"], c["target"]["entity"])
                for c in result["couplings"]}

    def test_다른_주체_쌍만_결합이_된다(self):
        r = flow.derive(SITES, E2)
        self.assertEqual(self.pairs(r), {("배출", "수거"), ("배출", "넘침판정"),
                                         ("수거", "넘침판정")})

    def test_같은_주체_쌍은_상태_갱신으로_간다(self):
        r = flow.derive(SITES, E2)
        self.assertEqual(len(r["internal_updates"]), 1)
        self.assertEqual(r["internal_updates"][0]["entity"], "수거")

    def test_자기결합이_나오지_않는다(self):
        r = flow.derive(SITES, E2)
        for c in r["couplings"]:
            self.assertNotEqual(c["source"]["entity"], c["target"]["entity"])

    def test_payload_는_shared_state_로_확정된다(self):
        r = flow.derive(SITES, E2)
        self.assertEqual({c["payload"]["kind"] for c in r["couplings"]}, {"shared_state"})

    def test_같은_끝점_쌍은_결합_하나로_합쳐진다(self):
        sites = [dict(SITES[0], writes=SITES[0]["writes"] + [
            {"site_id": "e1:v0:w2", "file_path": "E.java", "start_line": 500,
             "end_line": 500, "quote": "fill[x][t] += 1", "effect": "increase",
             "from": "e1"}])]
        e2 = {"site_actors": E2["site_actors"] + [
            {"site_id": "e1:v0:w2", "entity": "배출", "attribute": None}]}
        r = flow.derive(sites, e2)
        merged = [c for c in r["couplings"]
                  if (c["source"]["entity"], c["target"]["entity"]) == ("배출", "수거")]
        self.assertEqual(len(merged), 1)
        self.assertIn("e1:v0:w2", merged[0]["derived_from"])

    def test_기여한_자리_인용이_전부_붙는다(self):
        r = flow.derive(SITES, E2)
        c = next(x for x in r["couplings"]
                 if (x["source"]["entity"], x["target"]["entity"]) == ("배출", "수거"))
        lines = sorted(e["start_line"] for e in c["evidence"])
        self.assertEqual(lines, [254, 414, 454])

    def test_주체_미해결이면_보류로_간다(self):
        e2 = {"site_actors": [{"site_id": "e1:v0:w0", "entity": None,
                               "why": "맞는 개체가 없다"},
                              {"site_id": "e1:v0:r0", "entity": "수거판정"}]}
        sites = [{"value": "fill[b][t]", "declaration": None,
                  "writes": [SITES[0]["writes"][0]], "reads": [SITES[0]["reads"][0]]}]
        r = flow.derive(sites, e2)
        self.assertEqual(r["couplings"], [])
        self.assertEqual(len(r["unresolved_pairs"]), 1)
        self.assertEqual(r["unresolved_pairs"][0]["write_site_id"], "e1:v0:w0")

    def test_주체_판정이_없는_자리도_보류다(self):
        sites = [{"value": "fill[b][t]", "declaration": None,
                  "writes": [SITES[0]["writes"][0]], "reads": [SITES[0]["reads"][0]]}]
        r = flow.derive(sites, {"site_actors": []})
        self.assertEqual(r["couplings"], [])
        self.assertEqual(len(r["unresolved_pairs"]), 1)

    def test_끝점_속성이_있으면_옮긴다(self):
        e2 = {"site_actors": [{"site_id": "e1:v0:w0", "entity": "배출", "attribute": "배출량"},
                              {"site_id": "e1:v0:r0", "entity": "수거판정",
                               "attribute": "판정"}]}
        sites = [{"value": "fill[b][t]", "declaration": None,
                  "writes": [SITES[0]["writes"][0]], "reads": [SITES[0]["reads"][0]]}]
        c = flow.derive(sites, e2)["couplings"][0]
        self.assertEqual(c["source"]["attribute"], "배출량")
        self.assertEqual(c["target"]["attribute"], "판정")


class UnmatchedTests(unittest.TestCase):
    """값 이름이 b2 의 어느 항목과도 안 맞으면 **버리지 않고 남긴다.**

    q3-flow-1 에서 e1 이 `fill[b][t]` 로 적어 `fill` 과 안 맞았고, 새로 찾은 자리
    10건이 아무 기록 없이 사라졌다. README 규칙 3 위반이었다.
    """

    def e1(self, expr):
        return {"value_sites": [{
            "value": {"code_expression": expr, "declaration": None},
            "writes": [ev(521, "if (fill[b][t] > peak[b][t])")],
            "reads": [], "corrections": []}]}

    def test_아래_첨자가_붙어도_기본_이름으로_맞춘다(self):
        sites = flow.merge_sites(B2, self.e1("fill[b][t]"))
        self.assertEqual(len(sites[0]["writes"]), 2)
        self.assertEqual(flow.unmatched_value_sites(B2, self.e1("fill[b][t]")), [])

    def test_기본_이름도_없으면_보류로_남긴다(self):
        left = flow.unmatched_value_sites(B2, self.e1("없는값[x]"))
        self.assertEqual(len(left), 1)
        self.assertEqual(left[0]["value"], "없는값[x]")
        self.assertIn("없는값", left[0]["why"])
        self.assertEqual(len(left[0]["sites"]), 1)

    def test_기본_이름이_여럿에_걸리면_맞추지_않는다(self):
        b2 = {"subjects": [
            dict(B2["subjects"][0]),
            dict(B2["subjects"][0], state="fill"),
        ]}
        b2["subjects"][1] = dict(b2["subjects"][1], owner_candidate="다른소유자")
        left = flow.unmatched_value_sites(b2, self.e1("fill[b][t]"))
        self.assertEqual(len(left), 1)

    def test_파생_payload_에_보류가_실린다(self):
        out = flow.derive(flow.merge_sites(B2, self.e1("없는값[x]")),
                          {"site_actors": []})
        out["unresolved_values"] = flow.unmatched_value_sites(B2, self.e1("없는값[x]"))
        self.assertEqual(len(out["unresolved_values"]), 1)


if __name__ == "__main__":
    unittest.main()
