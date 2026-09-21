# -*- coding: utf-8 -*-
"""주석은 코드가 아니다.

q3-judge-1 의 후보 목록에 `리스트로` 가 들어 있었다. 한국어 주석
`/** 직업 구성을 enum 리스트로 해석. */` 에서 `enum 리스트로` 가 유형 선언으로 잡힌
것이다. 후보 열거는 코드가 하는 일이므로 온전히 구현 결함이다.

주석 안의 중괄호도 같은 문제를 낸다 — `// class Foo {` 한 줄이 깊이를 흔들어 클래스
본문 판정을 통째로 틀리게 만든다.

대조는 주석을 지운 사본으로 하고, **행 번호와 인용은 원문에서** 가져온다.
"""
from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import axis, candidates, collection  # noqa: E402
from sesx.index import TYPE_DECL, strip_comments  # noqa: E402

REAL = "    /** 직업 구성을 enum 리스트로 해석. 미지정 시 기본 3종. */"

FILES = {
    "a/Config.java": (
        "public class Config {\n"
        + REAL + "\n"
        "    // class Hidden {\n"
        "    private List<WasteType> wasteTypes = null;\n"
        "}\n"),
    "a/WasteType.java": "public class WasteType {\n}\n",
    "a/Mode.java": (
        "public enum Mode {\n"
        "    /* PAPER_BASELINE, */\n"
        "    REAL_ONE,\n"
        "    OTHER;\n"
        "}\n"),
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


class Stripping(unittest.TestCase):
    def test_a_declaration_word_inside_a_comment_is_not_a_declaration(self):
        self.assertIsNotNone(TYPE_DECL.search(REAL))          # 원문에서는 잡힌다
        self.assertIsNone(TYPE_DECL.search(strip_comments([REAL])[0]))

    def test_line_count_and_column_positions_are_kept(self):
        src = ["abc // tail", "/* x */ def"]
        out = strip_comments(src)
        self.assertEqual(len(out), 2)
        self.assertEqual([len(a) for a in out], [len(a) for a in src])
        self.assertTrue(out[0].startswith("abc"))
        self.assertTrue(out[1].endswith("def"))

    def test_block_comments_span_lines(self):
        out = strip_comments(["/* start", "enum Ghost {", "end */ enum Real {"])
        self.assertIsNone(TYPE_DECL.search(out[1]))
        self.assertIsNotNone(TYPE_DECL.search(out[2]))

    def test_braces_in_comments_do_not_count(self):
        src = ["// class Foo {", "/* } */"]
        out = strip_comments(src)
        self.assertEqual(sum(l.count("{") + l.count("}") for l in out), 0)


class Enumeration(unittest.TestCase):
    def test_the_korean_comment_no_longer_makes_a_candidate(self):
        names = [c["name"] for c in candidates.build(SNAP)]
        self.assertNotIn("리스트로", names)
        self.assertNotIn("Hidden", names)
        self.assertIn("Config", names)

    def test_the_real_field_is_still_found(self):
        """주석을 지우면서 진짜 선언까지 잃지 않았는가."""
        fields = [c for c in candidates.build(SNAP) if c["kind"] == "collection_field"]
        self.assertEqual([c["name"] for c in fields], ["wasteTypes"])
        self.assertEqual(fields[0]["anchor"]["owner_type"], "Config")

    def test_quotes_still_come_from_the_original_line(self):
        for c in candidates.build(SNAP):
            for s in c["sites"]:
                raw = SNAP.lines(s["file_path"])[s["start_line"] - 1]
                self.assertEqual(s["quote"], raw)


class Derivers(unittest.TestCase):
    def test_collection_skips_the_commented_declaration(self):
        out = collection.derive(SNAP)
        self.assertNotIn("리스트로", [e["name"] for e in out["entities"]])
        self.assertEqual([d["parent"] for d in out["decompositions"]], ["wasteTypes"])

    def test_axis_does_not_take_a_commented_constant(self):
        payload = {"entities": [{
            "name": "Mode", "role": "type", "scope": "simulation_target",
            "siblings": ["PAPER_BASELINE", "REAL_ONE", "OTHER"],
            "declaration_evidence": [{"file_path": "a/Mode.java", "start_line": 1,
                                      "end_line": 1, "quote": "public enum Mode {"}]}]}
        out = axis.derive(payload, SNAP)
        self.assertEqual(out["pending_axes"][0]["members"], ["REAL_ONE", "OTHER"])
        self.assertEqual(out["unresolved_axes"][0]["names"], ["PAPER_BASELINE"])


if __name__ == "__main__":
    unittest.main()
