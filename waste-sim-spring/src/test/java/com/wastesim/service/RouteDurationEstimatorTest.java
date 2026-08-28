package com.wastesim.service;

import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TruckType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteDurationEstimatorTest {

    @Test
    void rejectsUnknownAndConsecutiveDuplicateNodes() {
        assertThrows(IllegalArgumentException.class, () -> RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_Z"), 720, 15, null, null));
        assertThrows(IllegalArgumentException.class, () -> RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_A", "Node_B"), 720, 15, null, null));
    }

    @Test
    void noProfileNoStartTimeUsesFlatBaseMinutes() {
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B", "Node_C"), null, 15, null, null);

        assertFalse(est.trafficApplied);
        assertNull(est.endMinuteOfDay);
        assertEquals(2, est.hops.size());
        assertEquals(15, est.hops.get(0).minutes);
        assertEquals(15, est.hops.get(1).minutes);
        assertEquals(30, est.totalMinutes);
    }

    @Test
    void mobilityFactorSpeedsUpSmallTruck() {
        // SMALL_1TON mobilityFactor=1.6 → round(15/1.6)=9
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), null, 15, TruckType.SMALL_1TON, null);
        assertEquals(9, est.hops.get(0).minutes);
    }

    @Test
    void rejectsRouteShorterThanTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> RouteDurationEstimator.estimate(List.of("Node_A"), null, 15, null, null));
    }

    @Test
    void routeOrderChangesWhichNodeWeightApplies() {
        TrafficProfile profile = profileWithNodeWeights();

        // 순서 A→B→C: 첫 구간 도착 노드가 B(가중치 2.0), 둘째 구간 도착 노드가 C(가중치 1.0)
        RouteDurationEstimator.Estimate abc = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B", "Node_C"), 13 * 60, 15, TruckType.LARGE_5TON, profile);
        // 순서 A→C→B: 첫 구간 도착 노드가 C(가중치 1.0), 둘째 구간 도착 노드가 B(가중치 2.0)
        RouteDurationEstimator.Estimate acb = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_C", "Node_B"), 13 * 60, 15, TruckType.LARGE_5TON, profile);

        assertTrue(abc.trafficApplied);
        assertEquals(30, abc.hops.get(0).minutes);   // A→B 구간: 13시 Node_B 가중치 2.0 → round(15*2.0)=30
        assertEquals(15, abc.hops.get(1).minutes);   // B→C 구간: Node_C 가중치 1.0 → round(15*1.0)=15
        assertEquals(15, acb.hops.get(0).minutes);   // A→C 구간: Node_C 가중치 1.0
        assertEquals(30, acb.hops.get(1).minutes);   // C→B 구간: Node_B 가중치 2.0
        // 두 순서 모두 같은 노드 집합을 같은 시각에 방문 시작하지만, "몇 번째 구간에
        // 무거운 가중치가 걸리는가"가 달라 개별 구간 소요시간 분포가 달라진다.
        assertNotEquals(abc.hops.get(0).minutes, acb.hops.get(0).minutes);
        assertNotEquals(abc.hops.get(1).minutes, acb.hops.get(1).minutes);
    }

    @Test
    void startTimeChangesCongestionWeight() {
        TrafficProfile profile = profileWithNodeWeights();

        RouteDurationEstimator.Estimate atThirteen = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), 13 * 60, 15, TruckType.LARGE_5TON, profile);
        RouteDurationEstimator.Estimate atThree = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), 3 * 60, 15, TruckType.LARGE_5TON, profile);

        assertNotEquals(atThirteen.totalMinutes, atThree.totalMinutes);
    }

    @Test
    void missingStartTimeDisablesTrafficWeighting() {
        TrafficProfile profile = profileWithNodeWeights();
        RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                List.of("Node_A", "Node_B"), null, 15, TruckType.LARGE_5TON, profile);
        assertFalse(est.trafficApplied);
        assertEquals(15, est.hops.get(0).minutes);
    }

    /** 13시 구간은 Node_B=2.0/Node_C=1.0, 3시 구간은 둘 다 1.0인 단순 테스트 프로파일. */
    private TrafficProfile profileWithNodeWeights() {
        TrafficProfile p = new TrafficProfile();
        p.setId("test-profile");
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 1.0);
        p.setHourlyWeight(flat);

        double[] bWeights = flat.clone();
        bWeights[13] = 2.0;
        double[] cWeights = flat.clone();
        // Node_C는 항상 1.0(변화 없음)

        p.setNodeHourlyWeight(Map.of("Node_B", bWeights, "Node_C", cWeights));
        p.setCongestionThresholdRed(1.9);
        return p;
    }
}
