package com.districtsim.template;

import java.util.Collection;
import java.util.Map;

/**
 * 서브태스크를 <b>언제 만드는가</b> — 행정동 수거 시뮬레이터의 생성 조건.
 *
 * <p>자유 문법 대신 명명된 조건만 둔다. 상수 하나하나가 시험 대상이다. 보류({@link Activation#UNKNOWN})를
 * 거짓으로 뭉개지 않는다 — 그러면 조건이 나중에 참이 되어도 그 결정을 영영 묻지 않는다.
 */
public enum GenerateCondition {

    ALWAYS {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return Activation.ACTIVE;
        }
    },

    /** 상가가 주거 유형 구성에 있을 때만. 상가 처리 방식이 걸리는 조건이다. */
    COMMERCIAL_IN_MIX {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Object v = answers.get("housingMix");
            if (v == null) return Activation.UNKNOWN;
            if (v instanceof Collection<?> c) {
                return c.stream().anyMatch(e -> "commercial".equalsIgnoreCase(String.valueOf(e)))
                        ? Activation.ACTIVE : Activation.INACTIVE;
            }
            return String.valueOf(v).toLowerCase().contains("commercial") ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /**
     * 실제로 나가는 차량이 2대 이상일 때만. 구역 배정 방식이 걸리는 조건이다 — 실제 운행 대수는
     * {@code min(구역 수, 차량 수)} 이고, 1대면 나눌 것이 없다.
     */
    EFFECTIVE_TRUCKS_AT_LEAST_2 {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Integer zones = intOf(answers, "zoneCount");
            Integer trucks = intOf(answers, "truckCount");
            if (zones == null || trucks == null) return Activation.UNKNOWN;
            return Math.min(zones, trucks) >= 2 ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /** 요일 지정 수거일 때만. 수거 요일 목록이 걸리는 조건이다. */
    WEEKDAYS_SCHEDULE {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "scheduleMode", "WEEKDAYS");
        }
    },

    /**
     * 공휴일을 반영하고 매일 수거가 아닐 때만. 공휴일 정책이 걸리는 조건이다 — 매일 수거면 공휴일을 건너뛰어도
     * 다음 날이 어차피 수거일이라 정책이 결과를 바꾸지 못한다.
     *
     * <p>공휴일을 반영하지 않으면 일정을 몰라도 생성하지 않는다 — 그때 정책이 없는 것은 "아직 모름" 이 아니라
     * "영영 없음" 이다.
     */
    HOLIDAY_POLICY_NEEDED {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Activation holiday = equalsOrUnknown(answers, "holidayMode", "APPLY");
            if (holiday == Activation.INACTIVE) return Activation.INACTIVE;
            Object mode = answers.get("scheduleMode");
            if (holiday == Activation.UNKNOWN || mode == null) return Activation.UNKNOWN;
            return "DAILY".equalsIgnoreCase(String.valueOf(mode)) ? Activation.INACTIVE : Activation.ACTIVE;
        }
    },

    /** 적환장을 쓸 때만. 적환장 왕복 시간이 걸리는 조건이다. */
    TRANSFER_APPLIED {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "transferMode", "APPLY");
        }
    };

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
