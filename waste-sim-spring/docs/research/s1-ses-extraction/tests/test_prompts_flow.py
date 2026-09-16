# -*- coding: utf-8 -*-
import os
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROMPTS = os.path.join(HERE, "sesx", "prompts", "T2")


def read(name):
    with open(os.path.join(PROMPTS, name), encoding="utf-8") as f:
        return f.read()


class PromptTests(unittest.TestCase):
    def test_e1_이_있고_개체_이름을_금한다(self):
        t = read("e1.md")
        self.assertIn("개체 이름을 적지 않습니다", t)
        self.assertIn("value_sites", t)
        self.assertIn("corrections", t)

    def test_e2_가_확정_개체만_고르게_한다(self):
        t = read("e2.md")
        self.assertIn("site_actors", t)
        self.assertIn("확정 개체 목록에서만", t)
        self.assertIn("별개의 자리", t)

    def test_d_가_축_이름을_묻는다(self):
        self.assertIn("label", read("d.md"))

    def test_d_가_배열_차원_규칙을_적는다(self):
        self.assertIn("같은 선언", read("d.md"))


if __name__ == "__main__":
    unittest.main()
