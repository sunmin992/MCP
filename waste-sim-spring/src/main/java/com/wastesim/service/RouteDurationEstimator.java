package com.wastesim.service;

import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TruckType;
import com.wastesim.simulation.TravelTimeCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 방문 순서(routeSequence)와(선택) 출발(수거) 시각만으로 경로 이동시간
 * 근사값을 계산한다 — {@link com.wastesim.simulation.SimulationEngine}
 * 전체를 돌리지 않는 가벼운 "경로 소요시간 단독 질의" 전용 계산기.
 *
 * <p>구간(hop) 공식은 {@link TravelTimeCalculator}를 통해 SimulationEngine과
 * 동일한 것을 공유한다. 다만 이 클래스는 "이 순서·이 시각이면 몇 분?"이라는
 * 단일 질문에만 답하므로, 배출·수거 이벤트 큐 없이 구간 누적만 계산한다.
 *
 * <p>순서·시각을 바꾸면 결과도 그에 맞춰 달라진다:
 * <ul>
 *   <li>routeSequence 순서가 바뀌면 각 구간의 도착 노드가 바뀌어 그 노드의
 *       시간대별 혼잡 가중치가 달라진다.</li>
 *   <li>출발 시각이 바뀌면 각 구간에 적용되는 "그 시각"의 혼잡 가중치가
 *       달라지고(구간이 누적되며 시각도 앞으로 흐른다), 자정을 넘기면
 *       24시간을 순환한다.</li>
 * </ul>
 */
public final class RouteDurationEstimator {

    private RouteDurationEstimator() {}

    public static final int DEFAULT_ROUTE_TRAVEL_MINUTES = TravelTimeCalculator.DEFAULT_ROUTE_TRAVEL_MINUTES;

    /** 한 구간(hop)의 계산 결과. */
    public static final class Hop {
        public final String from;
        public final String to;
        public final int minutes;
        public final double congestionWeight;   // 혼잡 가중치 미적용이면 1.0
        public final boolean red;                // 혼잡 가중치 미적용이면 항상 false
        public final int arriveMinuteOfDay;      // 이 구간 도착 시각(하루 중 분)

        Hop(String from, String to, int minutes, double congestionWeight, boolean red, int arriveMinuteOfDay) {
            this.from = from;
            this.to = to;
            this.minutes = minutes;
            this.congestionWeight = congestionWeight;
            this.red = red;
            this.arriveMinuteOfDay = arriveMinuteOfDay;
        }
    }

    /** 경로 전체 계산 결과. */
    public static final class Estimate {
        public final List<Hop> hops;
        public final int totalMinutes;
        public final Integer startMinuteOfDay;   // null이면 출발 시각 미지정
        public final Integer endMinuteOfDay;     // null이면 출발 시각 미지정(도착 시각 계산 불가)
        public final boolean trafficApplied;     // 혼잡 가중치를 실제로 반영했는가

        Estimate(List<Hop> hops, int totalMinutes, Integer startMinuteOfDay, Integer endMinuteOfDay, boolean trafficApplied) {
            this.hops = hops;
            this.totalMinutes = totalMinutes;
            this.startMinuteOfDay = startMinuteOfDay;
            this.endMinuteOfDay = endMinuteOfDay;
            this.trafficApplied = trafficApplied;
        }
    }

    /**
     * @param routeSequence      방문 순서(Node_X 목록). 최소 2개 필요.
     * @param startMinuteOfDay   출발(수거) 시각(하루 중 분). null이면 혼잡 가중치를
     *                           적용하지 않는다 — "몇 시 기준"인지 알 수 없으면 특정
     *                           시간대 가중치를 곱하는 게 오히려 오해를 부르기 때문에,
     *                           기준 이동시간(구간당 routeTravelMinutes/기동성)만 쓴다.
     * @param routeTravelMinutes 구간 기본 이동시간(분). 0 이하면 DEFAULT_ROUTE_TRAVEL_MINUTES.
     * @param truckType          트럭 종류. null이면 LARGE_5TON.
     * @param profile            교통 프로파일. null이거나 startMinuteOfDay가 null이면 혼잡 가중치 미적용.
     */
    public static Estimate estimate(List<String> routeSequence, Integer startMinuteOfDay,
                                     int routeTravelMinutes, TruckType truckType, TrafficProfile profile) {
        if (routeSequence == null || routeSequence.size() < 2) {
            throw new IllegalArgumentException("routeSequence는 최소 2개 노드가 필요합니다.");
        }
        int base = routeTravelMinutes > 0 ? routeTravelMinutes : DEFAULT_ROUTE_TRAVEL_MINUTES;
        TruckType tt = truckType == null ? TruckType.LARGE_5TON : truckType;
        boolean trafficApplied = profile != null && startMinuteOfDay != null;

        List<Hop> hops = new ArrayList<>();
        int clock = startMinuteOfDay == null ? 0 : startMinuteOfDay;
        int total = 0;
        for (int i = 1; i < routeSequence.size(); i++) {
            String from = routeSequence.get(i - 1);
            String to = routeSequence.get(i);

            double weight = 1.0;
            boolean red = false;
            if (trafficApplied) {
                int minuteOfDay = ((clock % 1440) + 1440) % 1440;
                weight = profile.weightAt(minuteOfDay, to);
                red = profile.isRed(minuteOfDay, to);
            }

            int mins = TravelTimeCalculator.hopMinutes(base, tt.mobilityFactor, weight);
            clock += mins;
            total += mins;
            hops.add(new Hop(from, to, mins, weight, red, ((clock % 1440) + 1440) % 1440));
        }

        Integer end = startMinuteOfDay == null ? null : ((clock % 1440) + 1440) % 1440;
        return new Estimate(Collections.unmodifiableList(hops), total, startMinuteOfDay, end, trafficApplied);
    }
}
