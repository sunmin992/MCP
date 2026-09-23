package com.wastesim.template;

import org.springframework.stereotype.Component;

/**
 * 원문 답변을 템플릿의 허용값·범위에 맞춘다.
 *
 * <p><b>보정하지 않는다.</b> 맞지 않으면 사유 코드와 함께 거절한다 — 가까운 값으로
 * 바꾸면 사용자가 요청한 것과 다른 실험이 돌아가고, 그 사실이 아무 데도 남지 않는다.
 *
 * <p>자연어 표현형(예: "1톤" → {@code SMALL_1TON})은 여기서 다루지 않는다. 그 대응은
 * LLM이 템플릿의 추출 규칙으로 하고, 이 클래스는 <b>서버가 받은 값이 계약 안에 있는지</b>만
 * 본다. 서버가 자연어를 해석하기 시작하면 LLM과 서버 어느 쪽이 틀렸는지 가릴 수 없다.
 */
@Component
public class AnswerNormalizer {

    /**
     * @param ok        계약을 통과했는가
     * @param value     정규화된 값. 실패면 {@code null}
     * @param errorCode EMPTY_ANSWER / NOT_A_NUMBER / OUT_OF_RANGE / OUT_OF_CLOSURE
     */
    public record NormalizeResult(boolean ok, Object value, String errorCode, String message) {

        static NormalizeResult pass(Object value) {
            return new NormalizeResult(true, value, null, null);
        }

        static NormalizeResult fail(String code, String message) {
            return new NormalizeResult(false, null, code, message);
        }
    }

    public NormalizeResult normalize(SubtaskTemplate t, String raw) {
        if (raw == null || raw.isBlank()) {
            return NormalizeResult.fail("EMPTY_ANSWER", t.answerKey() + " 에 답이 없습니다.");
        }
        String v = raw.trim();

        if (!t.allowed().isEmpty()) {
            for (String candidate : t.allowed()) {
                if (candidate.equalsIgnoreCase(v)) return NormalizeResult.pass(candidate);
            }
            return NormalizeResult.fail("OUT_OF_CLOSURE",
                    t.answerKey() + " 에 허용되지 않은 값입니다: " + v
                            + " (허용: " + String.join(", ", t.allowed()) + ")");
        }

        if ("INTEGER".equals(t.valueType())) {
            long n;
            try {
                n = Long.parseLong(v);
            } catch (NumberFormatException e) {
                return NormalizeResult.fail("NOT_A_NUMBER",
                        t.answerKey() + " 는 정수여야 합니다. 받은 값: " + v);
            }
            if (t.min() != null && n < t.min()) {
                return NormalizeResult.fail("OUT_OF_RANGE",
                        t.answerKey() + " 는 " + t.min().intValue() + " 이상이어야 합니다. 받은 값: " + n);
            }
            if (t.max() != null && n > t.max()) {
                return NormalizeResult.fail("OUT_OF_RANGE",
                        t.answerKey() + " 는 " + t.max().intValue() + " 이하여야 합니다. 받은 값: " + n);
            }
            return NormalizeResult.pass((int) n);
        }

        // 실수형. 경로 배정용량처럼 상한이 다른 답(차종)에 달린 값은 min/max 를 비워 두고
        // SimulationConfigValidator 가 정본으로 남는다 — 범위를 두 곳에서 정의하지 않는다.
        if ("NUMBER".equals(t.valueType())) {
            double n;
            try {
                n = Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return NormalizeResult.fail("NOT_A_NUMBER",
                        t.answerKey() + " 는 수여야 합니다. 받은 값: " + v);
            }
            if (!Double.isFinite(n)) {
                return NormalizeResult.fail("NOT_A_NUMBER",
                        t.answerKey() + " 는 유한한 수여야 합니다. 받은 값: " + v);
            }
            if (t.min() != null && n < t.min()) {
                return NormalizeResult.fail("OUT_OF_RANGE",
                        t.answerKey() + " 는 " + t.min() + " 이상이어야 합니다. 받은 값: " + n);
            }
            if (t.max() != null && n > t.max()) {
                return NormalizeResult.fail("OUT_OF_RANGE",
                        t.answerKey() + " 는 " + t.max() + " 이하여야 합니다. 받은 값: " + n);
            }
            return NormalizeResult.pass(n);
        }

        return NormalizeResult.pass(v);
    }
}
