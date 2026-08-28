package com.wastesim.service;

import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TruckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 경로 소요시간 단독 질의(RT)의 계약을 고정한다 —
 * {@link RouteDurationEstimatorTest}가 다루지 않는 시각 흐름·경계 조건 쪽이다.
 *
 * <p>이 계산기의 핵심은 "구간을 지날 때마다 시계도 함께 흐른다"는 것이다. 그래서 뒤쪽
 * 구간은 앞 구간이 걸린 만큼 늦은 시각의 혼잡도를 받고, 자정을 넘기면 하루를 순환한다.
 * 이 두 가지가 깨지면 결과는 그럴듯한 숫자를 유지한 채 조용히 틀린다.
 */
class RouteDurationScenarioTest {

    // ── RT-06: 최소 경로 ──────────────────────────────────────────────────

    @Test
    @DisplayName("RT-06 노드 2개면 구간 하나만 계산한다")
    void twoNodesProduceSingleHop() {
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), 8 * 60, 12, TruckType.LARGE_5TON, null);

        assertEquals(1, est.hops.size());
        assertEquals("Node_A", est.hops.get(0).from);
        assertEquals("Node_B", est.hops.get(0).to);
        assertEquals(12, est.totalMinutes);
    }

    @Test
    @DisplayName("RT-01 노드 4개면 구간 3개와 총 이동시간·완료시각이 모두 나온다")
    void fourNodesProduceThreeHopsAndCompletionTime() {
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_B", "Node_C", "Node_A", "Node_D"), 12 * 60, 15, TruckType.LARGE_5TON, null);

        assertEquals(3, est.hops.size());
        assertEquals(45, est.totalMinutes);
        assertEquals(12 * 60, est.startMinuteOfDay);
        assertEquals(12 * 60 + 45, est.endMinuteOfDay, "12:00 출발 + 45분 = 12:45");
        // 구간별 도착 시각이 누적된다 — 화면이 "각 구간 도착 시각"을 표시할 근거다.
        assertEquals(12 * 60 + 15, est.hops.get(0).arriveMinuteOfDay);
        assertEquals(12 * 60 + 30, est.hops.get(1).arriveMinuteOfDay);
        assertEquals(12 * 60 + 45, est.hops.get(2).arriveMinuteOfDay);
    }

    // ── RT-13: 자정 순환 ─────────────────────────────────────────────────

    @Test
    @DisplayName("RT-13 자정을 넘겨 끝나면 완료시각이 다음 날 시각으로 순환한다")
    void completionWrapsPastMidnight() {
        // 23:30 출발, 구간 2개 × 40분 = 80분 → 00:50
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B", "Node_C"), 23 * 60 + 30, 40, TruckType.LARGE_5TON, null);

        assertEquals(80, est.totalMinutes, "총 이동시간은 순환하지 않고 그대로 80분");
        assertEquals(50, est.endMinuteOfDay, "완료 시각만 00:50으로 순환한다");
        assertTrue(est.endMinuteOfDay < est.startMinuteOfDay,
                "완료 시각이 출발 시각보다 작다는 것이 곧 날짜가 넘어갔다는 신호다");
    }

    // ── RT-16: 구간마다 그 시각의 혼잡도 ──────────────────────────────────

    @Test
    @DisplayName("RT-16 뒤쪽 구간은 앞 구간이 걸린 만큼 늦은 시각의 혼잡도를 받는다")
    void laterHopsUseLaterClock() {
        // Node_X는 8시대 1.0, 9시대 3.0. 08:40 출발이면
        //   1구간: 08:40 진입 → 8시대 가중치 1.0 → 30분 → 09:10 도착
        //   2구간: 09:10 진입 → 9시대 가중치 3.0 → 90분
        TrafficProfile profile = new TrafficProfile();
        profile.setId("rt-16");
        double[] flat = new double[24];
        Arrays.fill(flat, 1.0);
        profile.setHourlyWeight(flat);
        double[] xWeights = flat.clone();
        xWeights[9] = 3.0;
        profile.setNodeHourlyWeight(Map.of("Node_X", xWeights));
        profile.setCongestionThresholdRed(2.5);

        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_S", "Node_X", "Node_X"), 8 * 60 + 40, 30, TruckType.LARGE_5TON, profile);

        assertTrue(est.trafficApplied);
        assertEquals(30, est.hops.get(0).minutes, "1구간은 8시대라 가중치 1.0");
        assertEquals(90, est.hops.get(1).minutes, "2구간은 이미 9시대로 넘어가 가중치 3.0");
        // 시계가 흐르지 않는 구현이라면 두 구간이 같은 시간이 나온다.
        assertNotEquals(est.hops.get(0).minutes, est.hops.get(1).minutes);
    }

    @Test
    @DisplayName("RT-16 혼잡 임계를 넘긴 구간은 red로 표시된다")
    void congestedHopIsFlaggedRed() {
        TrafficProfile profile = new TrafficProfile();
        profile.setId("rt-red");
        double[] flat = new double[24];
        Arrays.fill(flat, 1.0);
        profile.setHourlyWeight(flat);
        double[] jam = flat.clone();
        jam[8] = 2.5;
        profile.setNodeHourlyWeight(Map.of("Node_JAM", jam));
        profile.setCongestionThresholdRed(2.0);

        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_JAM"), 8 * 60, 10, TruckType.LARGE_5TON, profile);

        assertTrue(est.hops.get(0).red, "가중치 2.5가 임계 2.0을 넘으면 혼잡 구간으로 표시된다");
        assertEquals(2.5, est.hops.get(0).congestionWeight, 1e-9);
    }

    // ── RT-14·02: 가정 공개 ───────────────────────────────────────────────

    @Test
    @DisplayName("RT-02·14 출발 시각이 없으면 혼잡도를 적용하지 않고 완료시각도 만들지 않는다")
    void withoutStartTimeNoTrafficAndNoCompletion() {
        TrafficProfile profile = new TrafficProfile();
        profile.setId("rt-14");
        double[] flat = new double[24];
        Arrays.fill(flat, 5.0);   // 모든 시간대가 혼잡해도
        profile.setHourlyWeight(flat);

        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), null, 20, TruckType.LARGE_5TON, profile);

        // "몇 시 기준"인지 모르는 채 특정 시간대 가중치를 곱하면 오해를 부른다.
        assertFalse(est.trafficApplied);
        assertNull(est.endMinuteOfDay, "출발 시각을 모르면 완료 시각도 만들지 않는다");
        assertEquals(20, est.hops.get(0).minutes, "기준 이동시간만 쓴다");
        assertEquals(1.0, est.hops.get(0).congestionWeight, 1e-9);
        assertFalse(est.hops.get(0).red);
    }

    @Test
    @DisplayName("RT-14 구간 기본 이동시간을 주지 않으면 기본값으로 채우고 그 값을 드러낸다")
    void nonPositiveTravelMinutesFallsBackToDefault() {
        RouteDurationEstimator.Estimate zero = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), null, 0, TruckType.LARGE_5TON, null);
        RouteDurationEstimator.Estimate negative = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), null, -5, TruckType.LARGE_5TON, null);

        assertEquals(RouteDurationEstimator.DEFAULT_ROUTE_TRAVEL_MINUTES, zero.hops.get(0).minutes);
        assertEquals(RouteDurationEstimator.DEFAULT_ROUTE_TRAVEL_MINUTES, negative.hops.get(0).minutes);
    }

    // ── RT-05·09: 경계 ───────────────────────────────────────────────────

    @Test
    @DisplayName("RT-05·09 노드가 2개 미만이면 계산하지 않고 거부한다")
    void routeShorterThanTwoIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RouteDurationEstimator.estimate(List.of("Node_A"), 9 * 60, 15, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RouteDurationEstimator.estimate(List.of(), 9 * 60, 15, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RouteDurationEstimator.estimate(null, 9 * 60, 15, null, null));
    }

    @Test
    @DisplayName("RT 결과의 구간 목록은 불변이라 호출측이 뒤에서 바꿀 수 없다")
    void hopsAreImmutable() {
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), null, 15, null, null);

        assertThrows(UnsupportedOperationException.class, () -> est.hops.clear());
    }
}
