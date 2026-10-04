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

        // 열거값 목록. 직업 구성처럼 원소마다 닫힌 선택지가 있고, 겹친 값이 비중을 뜻하므로
        // 겹쳐도 지우지 않는다. JSON 배열("[\"Student\"]")이나 쉼표 구분("Student, Housewife")을 받는다.
        if ("ENUM_LIST".equals(t.valueType())) {
            String body = v.startsWith("[") && v.endsWith("]") ? v.substring(1, v.length() - 1) : v;
            if (body.isBlank()) {
                return NormalizeResult.fail("EMPTY_ANSWER", t.answerKey() + " 에 값이 하나도 없습니다.");
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String part : body.split(",")) {
                String p = part.trim().replaceAll("^\"|\"$", "");
                String matched = t.allowed().stream()
                        .filter(c -> c.equalsIgnoreCase(p)).findFirst().orElse(null);
                if (matched == null) {
                    return NormalizeResult.fail("OUT_OF_CLOSURE",
                            t.answerKey() + " 에 허용되지 않은 값입니다: " + p
                                    + " (허용: " + String.join(", ", t.allowed()) + ")");
                }
                out.add(matched);
            }
            return NormalizeResult.pass(java.util.List.copyOf(out));
        }

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

        // 정수 목록. 하루 수거 시각처럼 몇 개인지가 답의 일부인 값이다. JSON 배열("[660,1380]")
        // 이나 쉼표 구분("660, 1380")을 받는다. 순서를 바꾸거나 겹친 값을 지워 주지 않는다 —
        // 고쳐 주면 사용자가 확인한 것과 다른 목록이 돈다.
        if ("INTEGER_LIST".equals(t.valueType())) {
            String body = v.startsWith("[") && v.endsWith("]") ? v.substring(1, v.length() - 1) : v;
            if (body.isBlank()) {
                return NormalizeResult.fail("EMPTY_ANSWER", t.answerKey() + " 에 값이 하나도 없습니다.");
            }
            java.util.List<Integer> out = new java.util.ArrayList<>();
            for (String part : body.split(",")) {
                String p = part.trim().replaceAll("^\"|\"$", "");
                int n;
                try {
                    n = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    return NormalizeResult.fail("NOT_A_NUMBER",
                            t.answerKey() + " 의 값은 정수여야 합니다. 받은 값: " + p);
                }
                if ((t.min() != null && n < t.min()) || (t.max() != null && n > t.max())) {
                    return NormalizeResult.fail("OUT_OF_RANGE",
                            t.answerKey() + " 의 값은 " + t.min().intValue() + "~" + t.max().intValue()
                                    + " 이어야 합니다. 받은 값: " + n);
                }
                if (out.contains(n)) {
                    return NormalizeResult.fail("DUPLICATE_VALUE",
                            t.answerKey() + " 에 같은 값이 두 번 있습니다: " + n);
                }
                out.add(n);
            }
            return NormalizeResult.pass(java.util.List.copyOf(out));
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
