package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TrafficProfile;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 교통 레이어 엔진 통합 테스트 (TRAFFIC_EXTENSION_DESIGN.md §9). */
class SimulationEngineTrafficTest {

    @Test
    void peakHourIncreasesCompletionAndComplaints() {   // UT-T6
        TrafficDataService data = new TrafficDataService();
        SimulationEngine engine = new SimulationEngine(data);

        SimulationConfig base = new SimulationConfig();
        base.setDays(3);
        base.setSeeds(1);
        base.setNumBuildings(4);
        base.setRouteTravelMinutes(30);
        base.setTrafficEnabled(true);
        base.setTrafficProfileId("jangryang-weekday");   // 13시경 점심 피크(RED), 03시경 한산

        SimulationConfig peak = base.copy();
        peak.setCollectionTimeLabel("08:30");
        SimulationConfig offpeak = base.copy();
        offpeak.setCollectionTimeLabel("03:00");

        SimulationResult peakResult = engine.run(peak, 1);
        SimulationResult offpeakResult = engine.run(offpeak, 1);

        assertTrue(peakResult.getAvgCompletionMinutes() > offpeakResult.getAvgCompletionMinutes(),
                "피크 시각 수거의 완료 시간이 더 길어야 함");
        assertTrue(peakResult.getTrafficComplaints() >= offpeakResult.getTrafficComplaints(),
                "피크 시각 수거의 교통 민원이 더 많거나 같아야 함");
    }

    @Test
    void reorderingRouteAvoidsCongestionWindow() {   // UT-T7
        // Node_B만 07시대에 극심히 정체되고(가중치 5.0), 나머지 시간·나머지 노드는
        // 전부 평시(1.0)인 통제된 프로파일을 직접 등록해 순서 재편성 효과를 명확히 검증한다.
        TrafficDataService data = new TrafficDataService();
        TrafficProfile p = new TrafficProfile();
        p.setId("test-b-jam");
        p.setCongestionThresholdRed(2.0);
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 1.0);
        p.setHourlyWeight(flat);
        double[] bJam = flat.clone();
        bJam[7] = 5.0;   // 07시대에만 Node_B 극심 정체
        p.setNodeHourlyWeight(Map.of("Node_B", bJam));
        data.register(p);

        SimulationEngine engine = new SimulationEngine(data);
        SimulationConfig base = new SimulationConfig();
        base.setDays(1);
        base.setSeeds(1);
        base.setNumBuildings(3);      // Node_A, Node_B, Node_C
        base.setRouteTravelMinutes(60);
        base.setTrafficEnabled(true);
        base.setTrafficProfileId("test-b-jam");
        base.setCollectionTimeLabel("07:00");

        SimulationConfig natural = base.copy();   // [A,B,C] — B를 정체 시간대(07시)에 바로 통과
        SimulationConfig reordered = base.copy();
        reordered.setRouteSequence(List.of("Node_A", "Node_C", "Node_B"));   // B를 뒤로 미룸

        double naturalCompletion = engine.run(natural, 1).getAvgCompletionMinutes();
        double reorderedCompletion = engine.run(reordered, 1).getAvgCompletionMinutes();

        assertTrue(reorderedCompletion < naturalCompletion,
                "정체 노드를 후순위로 미루면 완료 시간이 단축돼야 함 (natural=" + naturalCompletion +
                        ", reordered=" + reorderedCompletion + ")");
    }

    @Test
    void dispatchIntervalChangesEngineTiming() {   // UT-T8
        // 시차 배차는 트럭별 실제 수거 "시각"(절대 시각)을 어긋나게 만들어, 같은
        // 건물이라도 그날 배출 이벤트 대비 수거가 더 일찍/늦게 일어나 적재량이
        // 달라진다 — dispatchIntervalMinutes가 엔진에 실제로 반영됨을 peakFillKg
        // 변화로 검증한다(완료시간 지표는 단일 정거장 트럭엔 항상 0이라 부적합).
        TrafficDataService data = new TrafficDataService();
        SimulationEngine engine = new SimulationEngine(data);

        SimulationConfig base = new SimulationConfig();
        base.setDays(5);
        base.setSeeds(1);
        base.setNumBuildings(2);
        base.setNumTrucks(2);
        base.setCollectionTimeLabel("13:00");
        base.setTruckType("SMALL_1TON");

        SimulationConfig noStagger = base.copy();
        noStagger.setDispatchIntervalMinutes(0);
        SimulationConfig staggered = base.copy();
        staggered.setDispatchIntervalMinutes(60);

        double a = engine.run(noStagger, 1).getPeakFillKg();
        double b = engine.run(staggered, 1).getPeakFillKg();

        assertNotEquals(a, b, 0.001, "시차 배차(dispatchIntervalMinutes)가 엔진 타이밍에 반영돼야 함");
    }
}
