# -*- coding: utf-8 -*-
"""출력 예산을 남은 자리에서 계산한다.

고정 상한은 두 방향으로 다 틀린다. 입력이 작으면 답할 자리를 안 주고, 입력이 크면
끝낼 수 없는 요청을 보낸다 — q3-whole-4 의 d 단계가 입력 118,142 에 상한 24,000 을
받았는데 창이 131,072 라 실제로는 12,930 뿐이었다.

그리고 **추정이 보수적이면 그 자체로 해롭다.** 글자/토큰 비를 2.0 으로 못 박았더니
실제(2.45~2.67) 대비 22~32% 많게 세서 예산을 깎았고, 반복 실행 5회가 전부 죽었다.
그래서 비는 상수가 아니라 **이 실행에서 배운다.**
"""
from __future__ import annotations

import contextlib
import io
import json
import math
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import llm  # noqa: E402

# 반복 실행 5회의 실측값. (단계, 보낸 글자, 실제 토큰)
OBSERVED = [("a", 162132, 66129), ("b2", 189899, 75059),
            ("c", 225319, 86329), ("d", 251389, 95223)]


def call(client, chars, prompt_eval_count):
    body = json.dumps({"message": {"content": "{}"},
                       "prompt_eval_count": prompt_eval_count,
                       "eval_count": 5, "done_reason": "stop"}).encode()

    @contextlib.contextmanager
    def fake(req, timeout=None):
        yield io.BytesIO(body)

    with mock.patch.object(llm.urllib.request, "urlopen", fake):
        return client.complete("s", "", "u" * chars)


class TheRatioIsLearned(unittest.TestCase):
    def client(self):
        return llm.OllamaClient("m", num_ctx=131072, max_tokens=36000)

    def test_the_first_call_uses_the_floor(self):
        c = self.client()
        _, u = call(c, 162132, 66129)
        self.assertEqual(u["estimate_source"], "first_call_floor")
        self.assertEqual(u["estimate_ratio"], llm.FIRST_CALL_CHARS_PER_TOKEN)

    def test_later_calls_use_what_was_observed(self):
        c = self.client()
        call(c, 162132, 66129)
        _, u = call(c, 225319, 86329)
        self.assertEqual(u["estimate_source"], "observed")
        self.assertAlmostEqual(u["estimate_ratio"], round(162132 / 66129 * 0.95, 3), places=3)

    def test_learning_shrinks_the_overestimate(self):
        """5회 실행을 죽인 것이 바로 이 과대추정이다."""
        c = self.client()
        for st, chars, actual in OBSERVED:
            _, u = call(c, chars, actual)
            over = (u["estimated_prompt_tokens"] - actual) / actual
            if st == "a":
                self.assertGreater(over, 0.2)      # 첫 호출은 바닥값이라 크게 잡는다
            else:
                self.assertLess(over, 0.2, st)     # 배운 뒤에는 20% 안쪽

    def test_the_estimate_is_never_below_the_truth(self):
        """예산을 과대평가하면 끝낼 수 없는 요청을 보내게 된다. 넘치는 쪽으로만 틀려야 한다."""
        c = self.client()
        for _, chars, actual in OBSERVED:
            _, u = call(c, chars, actual)
            self.assertGreaterEqual(u["estimated_prompt_tokens"], actual)

    def test_the_d_stage_now_has_room(self):
        c = self.client()
        for _, chars, actual in OBSERVED[:2]:
            call(c, chars, actual)
        _, u = call(c, 251389, 95223)
        self.assertGreater(u["output_budget"], 20000)   # 옛 계산으로는 4,350 이었다


class NoRoomIsRefused(unittest.TestCase):
    def test_a_request_that_cannot_be_answered_is_not_sent(self):
        c = llm.OllamaClient("m", num_ctx=32768, max_tokens=8000)
        with self.assertRaises(llm.LlmError) as cm:
            call(c, 300000, 30000)
        self.assertEqual(cm.exception.kind, "unknown")
        self.assertIn("답할 자리가 없다", str(cm.exception))

    def test_a_small_ceiling_is_the_callers_choice_not_a_refusal(self):
        """막을 것은 창에 자리가 없는 경우뿐이다. 상한이 낮은 것은 부르는 쪽 선택이다."""
        c = llm.OllamaClient("m", num_ctx=131072, max_tokens=100)
        _, u = call(c, 162132, 66129)
        self.assertEqual(u["num_predict"], 100)
        self.assertGreater(u["output_budget"], llm.MIN_OUTPUT_TOKENS)

    def test_the_ceiling_still_caps_a_large_budget(self):
        c = llm.OllamaClient("m", num_ctx=131072, max_tokens=36000)
        _, u = call(c, 20000, 8000)
        self.assertEqual(u["num_predict"], 36000)
        self.assertGreater(u["output_budget"], 36000)


if __name__ == "__main__":
    unittest.main()
