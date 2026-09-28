package com.wastesim.simulation;

/**
 * 구간(hop) 이동시간 계산 공식 — {@link SimulationEngine}(전체 시뮬레이션)과
 * {@code RouteDurationEstimator}(경로 소요시간 단독 질의, 전체 시뮬레이션
 * 실행 없이 이동시간만 근사)가 공유하는 단일 소스.
 *
 * <p>두 호출측이 각자 공식을 따로 들고 있으면 한쪽만 고치고 다른 쪽을
 * 안 고치는 드리프트가 생길 수 있다(C1 원칙: 서버가 계산한 사실은 결정론적
 * 이고 재현 가능해야 한다) — 그래서 공식 자체를 여기 한 곳에 고정한다.
 *
 * <h2>두 가지 모드</h2>
 * {@link com.wastesim.model.TravelTimeMode#LEGACY_CONSTANT}는 v1.0부터의 계산이고,
 * {@link com.wastesim.model.TravelTimeMode#OSRM_HYBRID}는 실제 도로 자유주행시간을 쓴다.
 * 기본은 상수 모드이며 <b>기존 결과를 그대로 낸다</b>.
 *
 * <h2>정차·상차 시간</h2>
 * 상수 모드의 기본값 15분에는 이동뿐 아니라 정차·상차가 섞여 있을 수 있다 — 원본 DEVS
 * 모델에도 이 엔진에도 지점별 서비스 시간이 따로 없어서 한 값이 둘을 함께 떠맡아 왔다.
 * 혼합 모드는 그 둘을 나눈다. <b>그래서 OSRM 시간으로 상수를 그대로 갈아치우면 안 된다</b> —
 * 자유주행시간만 넣으면 정차분이 사라져 전체 결과가 과도하게 짧아진다.
 *
 * <p>서비스 시간은 <b>도착 지점에 대해</b> 더한다. 즉 경로의 첫 지점의 서비스 시간은
 * 계산에 들어가지 않는다 — 순회 시계는 트럭이 첫 지점에 도착해 일을 시작한 순간부터
 * 재기 때문이다. 기본값이 0이라 상수 모드의 결과는 예전과 완전히 같다.
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
     * 한 구간 이동에 걸리는 분(반올림) — 상수 모드.
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

    /**
     * 한 구간 이동에 걸리는 분(반올림) — 혼합 모드.
     *
     * <p>{@code round(자유주행분 / 기동성 × 혼잡) + 도착 지점 서비스분}.
     *
     * <p>기동성은 <b>주행분에만</b> 곱한다. 큰 차가 좁은 길에서 느린 것은 주행의 성질이고,
     * 상·하차에 걸리는 시간은 그 배수로 설명되지 않는다. 서비스 시간을 차종별로 달리해야
     * 한다면 그건 별도 입력이어야 하며 기동성 배수를 재사용할 자리가 아니다.
     *
     * <p>결과가 정수인 이유는 엔진의 상태 마감시각이 정수여야 하기 때문이다(VIRTUAL_TIME,
     * time_step = 1). 서비스분이 정수인 동안 {@code round(주행) + 서비스}와
     * {@code round(주행 + 서비스)}는 같은 값이므로 어느 쪽으로 써도 결과가 다르지 않다 —
     * 서비스 시간을 분 단위 실수로 열게 되면 그때 이 구분이 생긴다.
     *
     * @param freeFlowSeconds   이 구간의 실제 도로 자유주행시간(초). 정차·상차는 빠져 있다.
     * @param mobilityFactor    트럭 기동성 배수
     * @param congestionWeight  시간대 혼잡 가중치. 미적용이면 1.0.
     * @param serviceMinutes    도착 지점에서의 정차·상차 시간(분). 0 이상.
     */
    public static int hopMinutesFromFreeFlow(double freeFlowSeconds, double mobilityFactor,
                                             double congestionWeight, int serviceMinutes) {
        if (!Double.isFinite(freeFlowSeconds) || freeFlowSeconds < 0) {
            throw new IllegalArgumentException("자유주행시간은 0 이상의 유한한 값이어야 합니다: " + freeFlowSeconds);
        }
        if (serviceMinutes < 0) {
            throw new IllegalArgumentException("서비스 시간은 0 이상이어야 합니다: " + serviceMinutes);
        }
        double travel = (freeFlowSeconds / 60.0) / mobilityFactor * congestionWeight;
        return (int) Math.round(travel) + serviceMinutes;
    }
}
