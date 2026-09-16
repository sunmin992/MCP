# -*- coding: utf-8 -*-
import unittest
from sesx import shape


def ev(line, path="SimulationEngine.java"):
    return [{"file_path": path, "start_line": line, "end_line": line, "quote": "q"}]


class EdgeTests(unittest.TestCase):
    def test_boundary_는_aspect_로_집합을_갖는다(self):
        self.assertIsNone(shape.edge_error("boundary", "ASPECT", ["set", "set"]))

    def test_boundary_는_aspect_로_경계를_갖는다(self):
        self.assertIsNone(shape.edge_error("boundary", "ASPECT", ["boundary"]))

    def test_집합은_multi_로_개체_하나를_낳는다(self):
        self.assertIsNone(shape.edge_error("set", "MULTI", ["stateful"]))

    def test_개체는_spec_으로_유형을_가른다(self):
        self.assertIsNone(shape.edge_error("stateful", "SPEC", ["type", "type"]))

    def test_multi_자식이_둘이면_위반이다(self):
        self.assertIsNotNone(shape.edge_error("set", "MULTI", ["stateful", "stateful"]))

    def test_개체가_multi_부모면_위반이다(self):
        self.assertIsNotNone(shape.edge_error("stateful", "MULTI", ["stateful"]))

    def test_aspect_자식에_유형이_오면_위반이다(self):
        self.assertIsNotNone(shape.edge_error("boundary", "ASPECT", ["type"]))

    def test_집합이_spec_부모면_위반이다(self):
        self.assertIsNotNone(shape.edge_error("set", "SPEC", ["type"]))

    def test_역할을_모르면_막지_않는다(self):
        self.assertIsNone(shape.edge_error("unknown", "ASPECT", ["unknown"]))


class DeclarationTests(unittest.TestCase):
    def test_같은_선언_행을_가리키면_쌍을_낸다(self):
        me = {"건물": ev(254), "쓰레기 종류": ev(254)}
        self.assertEqual(shape.shared_declaration(me, ["건물", "쓰레기 종류"]),
                         ("건물", "쓰레기 종류"))

    def test_다른_행이면_없다(self):
        me = {"건물": ev(254), "쓰레기 종류": ev(255)}
        self.assertIsNone(shape.shared_declaration(me, ["건물", "쓰레기 종류"]))

    def test_같은_행이라도_파일이_다르면_없다(self):
        me = {"건물": ev(254, "A.java"), "쓰레기 종류": ev(254, "B.java")}
        self.assertIsNone(shape.shared_declaration(me, ["건물", "쓰레기 종류"]))

    def test_자식이_하나면_없다(self):
        self.assertIsNone(shape.shared_declaration({"건물": ev(254)}, ["건물"]))


if __name__ == "__main__":
    unittest.main()
