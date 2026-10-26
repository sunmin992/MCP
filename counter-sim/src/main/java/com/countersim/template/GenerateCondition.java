package com.countersim.template;

import java.util.Map;

/**
 * 서브태스크를 <b>언제 만드는가</b> — 민원 창구 시뮬레이터의 생성 조건 3개.
 *
 * <p>자유 문법 대신 명명된 조건만 둔다. 상수 하나하나가 시험 대상이다.
 *
 * <p>세 갈래로 답한다. {@link Activation#ACTIVE} 는 만들고, {@link Activation#INACTIVE} 는 만들지 않고,
 * {@link Activation#UNKNOWN} 은 <b>보류</b>다. 보류를 거짓으로 뭉개면 조건이 나중에 참이 되어도 그 결정을
 * 영영 묻지 않는다.
 */
public enum GenerateCondition {

    /** 언제나 만든다. */
    ALWAYS() {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return Activation.ACTIVE;
        }
    },

    /** 창구가 두 개 이상일 때만. 줄 서는 방식이 걸리는 조건이다 — 한 개면 줄도 하나뿐이다. */
    MULTIPLE_COUNTERS("counterCount") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Integer counters = intOf(answers, "counterCount");
            if (counters == null) return Activation.UNKNOWN;
            return counters >= 2 ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /** 예약제를 쓸 때만. 예약 비율이 걸리는 조건이다. */
    RESERVATION_APPLIED("reservationMode") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "reservationMode", "APPLY");
        }
    };

    /** 이 조건이 읽는 답변키. 보류일 때 무엇을 먼저 정해야 하는지 알리는 데 쓴다 — 평가 코드와 함께 고친다. */
    private final java.util.List<String> inputs;

    GenerateCondition(String... inputs) {
        this.inputs = java.util.List.of(inputs);
    }

    public java.util.List<String> inputs() {
        return inputs;
    }

    public abstract Activation evaluate(Map<String, Object> answers);

    static Activation equalsOrUnknown(Map<String, Object> answers, String key, String expected) {
        Object v = answers.get(key);
        if (v == null) return Activation.UNKNOWN;
        return expected.equalsIgnoreCase(String.valueOf(v)) ? Activation.ACTIVE : Activation.INACTIVE;
    }

    static Integer intOf(Map<String, Object> answers, String key) {
        Object v = answers.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
