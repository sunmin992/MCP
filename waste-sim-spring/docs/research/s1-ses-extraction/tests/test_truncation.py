# -*- coding: utf-8 -*-
"""프롬프트 잘림 탐지.

q3-whole-2·q3-whole-3 에서 실제로 놓친 것이다. 입력이 창(`num_ctx`)을 넘으면 ollama 는
말없이 문맥을 절반 버리고 **적게 센** 토큰 수를 돌려준다. 그래서 "셈이 창에 닿았는가"만
보는 기존 검사(`prompt_tokens >= num_ctx - 8`)로는 잡히지 않았다 — 98,304 짜리 창에서
잘린 요청이 49,154 로 보고됐고, 검사는 통과했다.

모델은 질문이 잘려 나간 채 Java 코드만 받았고, 그래서 package.json·코드리뷰 문서·파일
덤프 같은 답을 냈다. 기록에 남은 요청과 모델이 실제로 본 것이 달랐다 — 재현성 구멍이다.
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from sesx import llm  # noqa: E402

# 실제 실행에서 관측한 값. 이 시험은 그 기록을 고정한다.
OBSERVED = [
    #  이름                          보낸 글자  센 토큰   잘렸는가
    ("q3-whole-3 a  (창 97% · 정상)",   245852,  95511,  False),
    ("q3-whole-3 b2 (절반 버려짐)",      245852,  49154,  True),
    ("q3-whole-3 w  (절반 버려짐)",      243730,  49154,  True),
    ("q3-whole-1 b2 (행번호 없음·정상)", 241068,  71365,  False),
    ("q3-flow-2  d  (정상)",             190000,  95684,  False),
]


class ObservedRuns(unittest.TestCase):
    def test_each_observed_stage_is_classified_correctly(self):
        for name, chars, tokens, want in OBSERVED:
            with self.subTest(name):
                self.assertEqual(llm.truncated(chars, tokens), want,
                                 f"{name}: 글자/토큰 {llm.truncation_ratio(chars, tokens)}")

    def test_the_threshold_sits_between_the_two_groups(self):
        ok = [llm.truncation_ratio(c, t) for _, c, t, w in OBSERVED if not w]
        cut = [llm.truncation_ratio(c, t) for _, c, t, w in OBSERVED if w]
        self.assertLess(max(ok), llm.MAX_CHARS_PER_TOKEN)
        self.assertGreater(min(cut), llm.MAX_CHARS_PER_TOKEN)


class Boundaries(unittest.TestCase):
    def test_short_prompts_are_not_judged_by_ratio(self):
        """짧은 글에서는 비가 요동친다. 판정하지 않는다."""
        self.assertFalse(llm.truncated(llm.MIN_CHARS_FOR_RATIO - 1, 1))

    def test_zero_tokens_is_not_a_ratio_judgement(self):
        self.assertFalse(llm.truncated(500000, 0))
        self.assertIsNone(llm.truncation_ratio(500000, 0))

    def test_the_old_ceiling_check_would_have_missed_it(self):
        """왜 새 검사가 필요한지를 고정한다 — 옛 검사는 통과시켰다."""
        num_ctx, reported = 98304, 49154
        self.assertFalse(reported >= num_ctx - 8, "옛 검사가 잡았다면 이 시험은 의미가 없다")
        self.assertTrue(llm.truncated(245852, reported))


class FailsTheAttempt(unittest.TestCase):
    """잘린 응답으로 뒤 단계를 쌓지 않는다. 시도는 보존되고 종류는 unknown 이다."""

    def call(self, prompt_eval_count, user):
        """실제 complete() 경로를 타되 ollama 대신 고정 응답을 돌려준다."""
        body = json.dumps({"message": {"content": "{}"},
                           "prompt_eval_count": prompt_eval_count,
                           "eval_count": 10, "done_reason": "stop"}).encode()

        @contextlib.contextmanager
        def fake_urlopen(req, timeout=None):
            yield io.BytesIO(body)

        # 예산 검사가 먼저 막지 않도록 창을 넉넉히 준다. 잘림 탐지는 **예산이 있는데도**
        # 모델이 적게 센 경우를 잡는 방어다 — 두 검사는 서로 다른 것을 본다.
        client = llm.OllamaClient("qwen3-coder:30b", num_ctx=262144, max_tokens=1000)
        with mock.patch.object(llm.urllib.request, "urlopen", fake_urlopen):
            return client.complete("b2", "sys", user)

    def test_a_truncated_call_raises_unknown_not_failed(self):
        with self.assertRaises(llm.LlmError) as cm:
            self.call(49154, "x" * 245852)
        self.assertEqual(cm.exception.kind, "unknown")   # README 규칙 8
        self.assertIn("잘린", str(cm.exception))

    def test_a_normal_call_passes_and_records_the_ratio(self):
        text, usage = self.call(95511, "x" * 245852)
        self.assertEqual(text, "{}")
        self.assertEqual(usage["sent_chars"], 245852 + len("sys"))
        self.assertLess(usage["chars_per_token"], llm.MAX_CHARS_PER_TOKEN)

    def test_a_short_prompt_is_never_rejected_by_the_ratio(self):
        text, usage = self.call(1, "짧다")
        self.assertEqual(text, "{}")


if __name__ == "__main__":
    unittest.main()
