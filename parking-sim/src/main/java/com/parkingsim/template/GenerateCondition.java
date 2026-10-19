package com.parkingsim.template;

import java.util.Collection;
import java.util.Map;

/**
 * 서브태스크를 <b>언제 만드는가</b> — 공영주차장 시뮬레이터의 생성 조건 7개.
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

    /** 무료가 아닐 때만. 기본 요금이 걸리는 조건이다 — 무료면 요금이 결과를 바꾸지 못한다. */
    NOT_FREE("pricingPolicy") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Object v = answers.get("pricingPolicy");
            if (v == null) return Activation.UNKNOWN;
            return "FREE".equalsIgnoreCase(String.valueOf(v)) ? Activation.INACTIVE : Activation.ACTIVE;
        }
    },

    /** 시간대 차등 요금일 때만. 할증률이 걸리는 조건이다. */
    TIME_OF_DAY_PRICING("pricingPolicy") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "pricingPolicy", "TIME_OF_DAY");
        }
    },

    /**
     * 거주자가 이용자 구성에 있을 때만. 거주자 우선 구획 비율이 걸리는 조건이다 — 거주자가 없으면
     * 우선 구획은 아무도 쓰지 못하는 면이 된다.
     */
    RESIDENT_IN_MIX("userMix") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Object v = answers.get("userMix");
            if (v == null) return Activation.UNKNOWN;
            if (v instanceof Collection<?> c) {
                return c.stream().anyMatch(e -> "resident".equalsIgnoreCase(String.valueOf(e)))
                        ? Activation.ACTIVE : Activation.INACTIVE;
            }
            return String.valueOf(v).toLowerCase().contains("resident")
                    ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /**
     * 주차장이 두 곳 이상일 때만. 안내 방식이 걸리는 조건이다 — 한 곳이면 다른 곳으로 안내할 수 없어
     * 안내 방식이 결과를 바꾸지 못한다.
     */
    MULTIPLE_LOTS("lotCount") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Integer lots = intOf(answers, "lotCount");
            if (lots == null) return Activation.UNKNOWN;
            return lots >= 2 ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /**
     * 주차장이 두 곳 이상이고 실시간 앱으로 안내할 때만. 갱신 주기가 걸리는 조건이다.
     *
     * <p>안내 방식 자체가 생성되지 않는 설정(주차장 한 곳)이면 이것도 생성되지 않는다. 그때 안내 방식
     * 값이 없는 것은 "아직 모름" 이 아니라 "영영 없음" 이다 — 보류로 두면 필요한 값을 다 채워도 완결이
     * 되지 않는다.
     */
    REALTIME_GUIDANCE("lotCount", "guidanceMode") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            if (MULTIPLE_LOTS.evaluate(answers) == Activation.INACTIVE) return Activation.INACTIVE;
            return equalsOrUnknown(answers, "guidanceMode", "REALTIME_APP");
        }
    },

    /** 도로 혼잡을 반영할 때만. 혼잡 프로파일이 걸리는 조건이다. */
    CONGESTION_APPLIED("congestionMode") {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "congestionMode", "APPLY");
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
