package com.wastesim.simulation;

/**
 * 구간(hop) 이동시간 계산 공식 — {@link SimulationEngine}(전체 시뮬레이션)과
 * {@code RouteDurationEstimator}(경로 소요시간 단독 질의, 전체 시뮬레이션
 * 실행 없이 이동시간만 근사)가 공유하는 단일 소스.
 *
 * <p>두 호출측이 각자 공식을 따로 들고 있으면 한쪽만 고치고 다른 쪽을
 * 안 고치는 드리프트가 생길 수 있다(C1 원칙: 서버가 계산한 사실은 결정론적
 * 이고 재현 가능해야 한다) — 그래서 공식 자체를 여기 한 곳에 고정한다.
 */
public final class TravelTimeCalculator {

    private TravelTimeCalculator() {}

    /**
     * 경로 인식(routeSequence·trafficEnabled·차종 지정)이 감지됐지만 이동시간이
     * 명시되지 않았을 때 쓰는 기본 구간 이동시간(분). {@code ChatController}의
     * 전체 실행 경로와 동일한 기본값을 쓴다.
     */
    public static final int DEFAULT_ROUTE_TRAVEL_MINUTES = 15;

    /**
     * 한 구간 이동에 걸리는 분(반올림).
     *
     * @param baseMinutes       기본 구간 이동시간(분, routeTravelMinutes)
     * @param mobilityFactor    트럭 기동성 배수(TruckType.mobilityFactor, 클수록 정체 영향 적음)
     * @param congestionWeight  혼잡 가중치(TrafficProfile.weightAt 결과). 교통 레이어
     *                          미적용이면 1.0(중립)을 넘긴다.
     */
    public static int hopMinutes(int baseMinutes, double mobilityFactor, double congestionWeight) {
        double effTravel = baseMinutes / mobilityFactor;
        effTravel *= congestionWeight;
        return (int) Math.round(effTravel);
    }
}
