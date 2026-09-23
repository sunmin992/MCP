package com.wastesim.template;

import com.wastesim.ledger.Activation;

import java.util.Map;

/**
 * 서브태스크를 <b>언제 만드는가</b>.
 *
 * <p>자유 문법 대신 명명된 조건만 둔다. 식 파서를 만들면 제공자가 무엇이든 쓸 수 있게
 * 되지만, 그 식이 실제로 무엇을 읽는지 시험할 자리가 사라진다 — 여기 상수 하나하나가
 * 시험 대상이다.
 *
 * <p>세 갈래로 답한다. {@link Activation#ACTIVE}는 만들고, {@link Activation#INACTIVE}는
 * 만들지 않고, {@link Activation#UNKNOWN}은 <b>보류</b>다. 보류를 거짓으로 뭉개면 조건이
 * 나중에 참이 되어도 그 결정을 영영 묻지 않는다.
 */
public enum GenerateCondition {

    /** 언제나 만든다. */
    ALWAYS {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return Activation.ACTIVE;
        }
    },

    /**
     * 실제 운행 차량이 2대 이상일 때만. 배차 간격이 걸리는 조건이다.
     *
     * <p>실제 운행 대수는 {@code min(건물 수, 투입 대수)}다 — 건물보다 차량이 많으면
     * 엔진이 빈 경로의 운행을 만들지 않는다. 1대면 차량 간 시간차가 없어 배차 간격이
     * 결과를 바꾸지 못한다.
     */
    EFFECTIVE_TRUCKS_AT_LEAST_2 {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Integer buildings = intOf(answers, "numBuildings");
            Integer trucks = intOf(answers, "truckCount");
            if (buildings == null || trucks == null) return Activation.UNKNOWN;
            return Math.min(buildings, trucks) >= 2 ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /** 교통을 반영할 때만. 교통 프로파일 결정이 걸리는 조건이다. */
    TRAFFIC_APPLIED {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "trafficMode", "APPLY");
        }
    },

    /**
     * 교통구역 근사이면서 건물이 5동 이상일 때만. 구역 배정 규칙이 걸리는 조건이다.
     *
     * <p>4동까지는 지점 id를 구역 id로 보는 폴백이 이름이 겹쳐 통한다. 5동부터는
     * {@code Node_E}라는 구역이 없어 막힌다.
     */
    ZONE_PROXY_OVER_4_BUILDINGS {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            Object mode = answers.get("travelTimeMode");
            Integer buildings = intOf(answers, "numBuildings");
            if (mode == null || buildings == null) return Activation.UNKNOWN;
            boolean zoneProxy = "ZONE_PROXY_HYBRID".equals(String.valueOf(mode));
            return zoneProxy && buildings > 4 ? Activation.ACTIVE : Activation.INACTIVE;
        }
    },

    /**
     * 구역 배정이 덩어리 방식일 때만. 구역 내 이동시간이 걸리는 조건이다.
     *
     * <p>덩어리로 나누면 같은 구역 안의 이동이 생기는데, 구역 간 행렬에는 대각 성분이
     * 없다 — 구역은 점이 아니라 영역이라 자기 자신까지의 거리가 정의되지 않는다.
     * 번갈아 나누면 모든 구간이 구역을 넘으므로 이 값이 필요 없다.
     */
    CONTIGUOUS_ZONE_RULE {
        @Override
        public Activation evaluate(Map<String, Object> answers) {
            return equalsOrUnknown(answers, "zoneAssignmentRule", "CONTIGUOUS");
        }
    };

    public abstract Activation evaluate(Map<String, Object> answers);

    static Activation equalsOrUnknown(Map<String, Object> answers, String key, String expected) {
        Object v = answers.get(key);
        if (v == null) return Activation.UNKNOWN;
        return expected.equals(String.valueOf(v)) ? Activation.ACTIVE : Activation.INACTIVE;
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
