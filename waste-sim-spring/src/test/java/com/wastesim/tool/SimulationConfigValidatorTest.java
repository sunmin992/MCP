package com.wastesim.tool;

import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.TestSites;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulationConfigValidatorTest {

    private final SimulationConfigValidator v = new SimulationConfigValidator(new TrafficDataService());

    @Test
    void validConfigPasses() {              // UT-20
        assertTrue(v.validate(new SimulationConfig()).ready());
    }

    @Test
    void outOfRangeFails() {                 // UT-21
        SimulationConfig c = new SimulationConfig();
        c.setDays(0);
        c.setSeeds(999);
        c.setThreshold(2.0);
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.field().equals("days")));
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.OUT_OF_RANGE));
    }

    @Test
    void nonPositiveWasteMeanKgFails() {     // 실측 캘리브레이션 필드 검증
        SimulationConfig c = new SimulationConfig();
        c.setWasteMeanKg(0);
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.field().equals("wasteMeanKg")));
    }

    @Test
    void unknownOccupationFails() {          // UT-22
        SimulationConfig c = new SimulationConfig();
        c.setOccupationMix(List.of("Ghost"));
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertEquals(ErrorCode.INVALID_ENUM, r.errors().get(0).code());
    }

    // ── 교통 레이어 교차 검증 (TRAFFIC_EXTENSION_DESIGN.md §9) ────────────────

    @Test
    void predictOverflowRatioIsHighWhenNoTrucks() {   // UT-T2
        SimulationConfig c = new SimulationConfig();
        c.setTruckCount(0);
        assertTrue(v.predictOverflowRatio(c) >= 1.5);
    }

    @Test
    void truckCountZeroFails() {             // UT-T3 (V-T1, 시나리오 4)
        SimulationConfig c = new SimulationConfig();
        c.setTruckCount(0);
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.TRUCK_COUNT_ZERO));
    }

    @Test
    void criticalWasteAccumulationFails() {  // UT-T4 (V-T2) — 트럭은 있지만 수거 주기가 사실상 중단 수준
        SimulationConfig c = new SimulationConfig();
        c.setCollectionIntervalDays(999);    // 30일 시뮬 안에 사실상 재수거가 없음
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.CRITICAL_WASTE_ACCUMULATION));
    }

    @Test
    void overflowPredictionExcludesTrucksWithEmptyRoutes() {
        SimulationConfig c = new SimulationConfig();
        c.setNumBuildings(2);
        c.setTruckCount(4);                   // T3·T4는 방문 건물이 없어 엔진에서 운행하지 않음
        c.setResidentsPerBuilding(2000);      // 일 배출량 2 × 2000 × 0.9 = 3600kg
        c.setTruckType("LARGE_5TON");
        c.setRouteAvailableCapacityKg(1000.0);

        // 실제 운행 트럭은 2대이므로 공급량은 2000kg, 예측 적재율은 180%다.
        assertEquals(1.8, v.predictOverflowRatio(c), 1e-9);
        ValidationResult result = v.validate(c);
        assertFalse(result.ready());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.code() == ErrorCode.CRITICAL_WASTE_ACCUMULATION));
    }

    /** 요일 스케줄은 주 7회가 아니라 지정한 요일 수만큼만 공급을 만든다. */
    @Test
    void overflowPredictionUsesDayOfWeekFrequency() {
        SimulationConfig daily = new SimulationConfig();
        daily.setNumBuildings(4);
        daily.setResidentsPerBuilding(1000);
        daily.setRouteAvailableCapacityKg(5000.0);

        SimulationConfig fourDays = daily.copy();
        fourDays.setCollectionDaysOfWeek(List.of(0, 1, 3, 4));

        assertEquals(v.predictOverflowRatio(daily) * 7.0 / 4.0,
                v.predictOverflowRatio(fourDays), 1e-9,
                "월·화·목·금은 매일 수거보다 일평균 공급이 4/7이어야 한다");
    }

    /**
     * 골목 정보는 이제 교통 프로파일이 아니라 수거 지점에 있다. 운영 데이터에는 골목이
     * 하나도 없으므로(실제 네 지점이 전부 간선에 접한다) 가상 지점 집합을 물려 검증한다.
     */
    private final SimulationConfigValidator withAlleys =
            new SimulationConfigValidator(new TrafficDataService(), TestSites.withAlleys());

    @Test
    void trafficInfeasibleForLargeTruckInAlley() {   // UT-T5 (V-T3)
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("jangryang-weekday");
        c.setTruckType("LARGE_5TON");        // alleyAccess=false, 가상 지점의 Node_C/D는 골목
        ValidationResult r = withAlleys.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.TRAFFIC_INFEASIBLE));
    }

    @Test
    void smallTruckPassesAlleyCheck() {      // V-T3 대조군 — 소형 차량은 골목 통과
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("jangryang-weekday");
        c.setTruckType("SMALL_1TON");
        assertTrue(withAlleys.validate(c).ready());
    }

    /** 접근성은 지점의 물리적 성질이다 — 교통 레이어를 꺼도 골목은 골목이다. */
    @Test
    void alleyCheckAppliesEvenWithTrafficDisabled() {
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(false);
        c.setTruckType("LARGE_5TON");
        ValidationResult r = withAlleys.validate(c);
        assertFalse(r.ready(), "교통을 껐다고 5톤이 골목에 들어갈 수 있게 되지는 않는다");
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.TRAFFIC_INFEASIBLE));
    }

    /** 운영 데이터에는 골목이 없다 — 사실에 맞는 결과이므로 5톤이 막히지 않아야 한다. */
    @Test
    void operationalDataHasNoAlleysSoLargeTruckIsNotBlocked() {
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("jangryang-weekday");
        c.setTruckType("LARGE_5TON");
        assertTrue(v.validate(c).errors().stream()
                        .noneMatch(e -> e.code() == ErrorCode.TRAFFIC_INFEASIBLE),
                "확정 좌표 기준 장량동 네 지점은 모두 간선에 접한다");
    }

    @Test
    void invalidRouteSequenceFails() {       // V-T4
        SimulationConfig c = new SimulationConfig();
        c.setRouteSequence(List.of("Node_A", "Node_Z"));   // 건물 4개(A~D) 집합과 불일치
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.INVALID_ARGUMENTS));
    }

    @Test
    void redPeakTimeWarnsButDoesNotBlock() {  // V-T5 — 18:00은 TMAP 실측상 RED(퇴근 피크), 비차단 경고만
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("jangryang-weekday");
        c.setTruckType("MEDIUM_2P5T");        // alleyAccess=true — V-T3와 섞이지 않게 격리
        c.setCollectionTimeLabel("18:00");
        ValidationResult r = v.validate(c);
        assertTrue(r.ready());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void trafficEnabledRequiresProfileId() {
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTruckType("MEDIUM_2P5T");

        ValidationResult r = v.validate(c);

        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e ->
                e.code() == ErrorCode.MISSING_FIELD && e.field().equals("trafficProfileId")));
    }

    @Test
    void unknownTrafficProfileFailsClosed() {
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("does-not-exist");
        c.setTruckType("MEDIUM_2P5T");

        ValidationResult r = v.validate(c);

        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e ->
                e.code() == ErrorCode.INVALID_ARGUMENTS && e.field().equals("trafficProfileId")));
    }

    @Test
    void redJudgedByGlobalHourlyWeight() {   // DESIGN_DECISIONS.md D-10
        // 18:00(전역 hourlyWeight[18]=1.73 >= 임계 1.45) -> RED 경고 발생
        SimulationConfig peak = base();
        peak.setCollectionTimeLabel("18:00");
        assertFalse(v.validate(peak).warnings().isEmpty());
        // 16:00(전역 hourlyWeight[16]=1.38 < 임계 1.45) -> 경고 없음(정상)
        // 노드별 기준이었다면 Node_A·Node_C(16시=1.52·1.53)로 여기서도 RED가 뜨므로,
        // 이 어서션이 "전역 기준" 구현을 자동으로 검증한다. 데이터가 TMAP 실측으로
        // 바뀌며 시각은 달라졌지만 이 테스트가 가르는 것은 그대로다.
        SimulationConfig off = base();
        off.setCollectionTimeLabel("16:00");
        assertTrue(v.validate(off).warnings().isEmpty());
    }

    private SimulationConfig base() {
        SimulationConfig c = new SimulationConfig();
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("jangryang-weekday");
        c.setTruckType("MEDIUM_2P5T");   // alleyAccess=true (V-T3와 격리)
        return c;
    }
}
