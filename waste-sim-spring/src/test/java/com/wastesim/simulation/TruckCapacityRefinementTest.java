package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TripMetric;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TRUCK_CAPACITY_ENHANCEMENT_PLAN.md P1(빈 경로 제외)·P2(부분수거·미수거·용량소진 지표)·§4.3(질량보존).
 */
class TruckCapacityRefinementTest {

    private SimulationEngine engine() {
        return new SimulationEngine(new TrafficDataService());
    }

    /** 트럭 수가 건물 수보다 많아도 빈 경로는 운행/용량 집계에서 빠져야 한다(§3.1). */
    @Test
    void emptyRoutesDoNotInflateCapacityDenominator() {
        SimulationConfig base = new SimulationConfig();
        base.setDays(1);
        base.setNumBuildings(2);
        base.setResidentsPerBuilding(500);
        base.setWasteMeanKg(0.9);
        base.setWasteSigma(0);
        base.setLeaveSigma(0);
        base.setTruckType("LARGE_5TON");
        base.setCollectionTimeLabel("23:00");
        base.setLandlordEnabled(false);

        SimulationConfig exact = base.copy();
        exact.setNumTrucks(2);                 // 트럭 = 건물 → 빈 경로 없음
        SimulationConfig surplus = base.copy();
        surplus.setNumTrucks(4);               // 트럭 > 건물 → 빈 경로 2개 발생

        SimulationResult r2 = engine().run(exact, 1);
        SimulationResult r4 = engine().run(surplus, 1);

        // 빈 경로가 제외되므로 신규 수거 가능용량 합계와 이용률이 동일해야 한다.
        assertEquals(r2.getAvailableCollectionCapacityKg(), r4.getAvailableCollectionCapacityKg(), 1e-6,
                "빈 경로가 배정용량 분모에 포함되면 안 된다");
        assertEquals(r2.getTruckUtilizationPercent(), r4.getTruckUtilizationPercent(), 1e-6,
                "빈 경로가 트럭 이용률을 희석하면 안 된다");
        assertEquals(r2.getCollectedWasteKg(), r4.getCollectedWasteKg(), 1e-6,
                "빈 경로 추가는 실제 수거량을 바꾸지 않아야 한다");
        // 건물 2개 × LARGE_5TON(5000kg) × 1슬롯 = 10,000kg
        assertEquals(10_000.0, r2.getAvailableCollectionCapacityKg(), 1e-6);
    }

    /** 용량이 부족하면 부분수거·미수거·용량소진·미수거수요가 정확히 집계돼야 한다(§3.3). */
    @Test
    void tightCapacityRecordsPartialUnservedAndUncollectedDemand() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(1);
        cfg.setNumBuildings(2);
        cfg.setNumTrucks(1);                   // 트럭 1대가 A→B 순회
        cfg.setResidentsPerBuilding(2000);     // 건물당 1,800kg 발생
        cfg.setWasteMeanKg(0.9);
        cfg.setWasteSigma(0);
        cfg.setLeaveSigma(0);
        cfg.setTruckType("LARGE_5TON");
        cfg.setRouteAvailableCapacityKg(1000.0);   // 운행 1회 1,000kg만 실을 수 있음
        cfg.setInitialTruckLoadKg(0.0);
        cfg.setCollectionTimeLabel("23:00");
        cfg.setLandlordEnabled(false);

        SimulationResult r = engine().run(cfg, 1);

        // 건물 A: 1,800 중 1,000 수거(부분), 800 남김 → 운행 용량 소진
        // 건물 B: 잔여 용량 0 → 전혀 수거 못 함(미수거), 1,800 남김
        assertEquals(1, r.getPartialPickupCount(), "일부만 수거한 방문 1회");
        assertEquals(1, r.getUnservedPickupCount(), "전혀 수거 못 한 방문 1회");
        assertEquals(1, r.getCapacityExhaustedTripCount(), "용량 소진 운행 1건");
        assertEquals(2600.0, r.getUncollectedDemandKg(), 1e-6, "미수거 수요 = 800 + 1800");
        assertEquals(1000.0, r.getCollectedWasteKg(), 1e-6);
        assertEquals(2600.0, r.getResidualWasteKg(), 1e-6);
    }

    /** 유형별 비율 합이 1이면 질량보존 오차가 0에 수렴한다(§4.3). */
    @Test
    void massBalanceHoldsForSingleWasteType() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(2);
        cfg.setNumBuildings(2);
        cfg.setNumTrucks(1);
        cfg.setResidentsPerBuilding(2000);
        cfg.setWasteMeanKg(0.9);
        cfg.setWasteSigma(0);
        cfg.setLeaveSigma(0);
        cfg.setTruckType("LARGE_5TON");
        cfg.setRouteAvailableCapacityKg(1000.0);
        cfg.setCollectionTimeLabel("23:00");
        cfg.setLandlordEnabled(false);

        SimulationResult r = engine().run(cfg, 1);

        assertEquals(0.0, r.getMassBalanceErrorKg(), 0.01,
                "발생량 = 수거량 + 잔류량");
        assertEquals(r.getGeneratedWasteKg(),
                r.getCollectedWasteKg() + r.getResidualWasteKg(), 0.01);
    }

    /** 용량이 충분하면 진단 지표가 모두 0이어야 한다(회귀 보호). */
    @Test
    void ampleCapacityLeavesDiagnosticsZero() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(2);
        cfg.setNumBuildings(2);
        cfg.setNumTrucks(2);
        cfg.setResidentsPerBuilding(500);
        cfg.setWasteMeanKg(0.9);
        cfg.setWasteSigma(0);
        cfg.setLeaveSigma(0);
        cfg.setTruckType("LARGE_5TON");       // 5,000kg ≫ 건물당 450kg
        cfg.setCollectionTimeLabel("23:00");
        cfg.setLandlordEnabled(false);

        SimulationResult r = engine().run(cfg, 1);

        assertEquals(0, r.getPartialPickupCount());
        assertEquals(0, r.getUnservedPickupCount());
        assertEquals(0, r.getCapacityExhaustedTripCount());
        assertEquals(0.0, r.getUncollectedDemandKg(), 1e-6);
    }

    /** 운행별 상세 지표가 병목 운행을 정확히 담아야 한다(§3.4). */
    @Test
    void tripMetricsCaptureBottleneckTrip() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(1);
        cfg.setNumBuildings(2);
        cfg.setNumTrucks(1);
        cfg.setResidentsPerBuilding(2000);
        cfg.setWasteMeanKg(0.9);
        cfg.setWasteSigma(0);
        cfg.setLeaveSigma(0);
        cfg.setTruckType("LARGE_5TON");
        cfg.setRouteAvailableCapacityKg(1000.0);
        cfg.setCollectionTimeLabel("23:00");
        cfg.setLandlordEnabled(false);

        List<TripMetric> trips = engine().run(cfg, 1).getTripMetrics();

        assertEquals(1, trips.size(), "트럭 1대 × 1슬롯 × 1일 → 운행 1건");
        TripMetric t = trips.get(0);
        assertEquals("T1", t.truckId());
        assertEquals("T1-D0-S0", t.tripId());
        assertEquals(1000.0, t.allocatedCapacityKg(), 1e-6);
        assertEquals(0.0, t.initialLoadKg(), 1e-6);
        assertEquals(1000.0, t.availablePickupCapacityKg(), 1e-6);
        assertEquals(1000.0, t.collectedKg(), 1e-6);
        assertEquals(1000.0, t.finalLoadKg(), 1e-6);
        assertEquals(0.0, t.unusedCapacityKg(), 1e-6);
        assertEquals(100.0, t.utilizationPercent(), 1e-6);
        assertEquals(1, t.partialPickupCount(), "건물 A 부분수거 1회(건물 B 미수거는 부분수거 아님)");
    }

    /** 빈 경로는 운행 목록에도 나타나지 않아야 한다(P1×P3). */
    @Test
    void emptyRoutesProduceNoTripEntries() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(1);
        cfg.setNumBuildings(2);
        cfg.setNumTrucks(4);                   // 트럭 2대는 빈 경로
        cfg.setResidentsPerBuilding(500);
        cfg.setWasteMeanKg(0.9);
        cfg.setWasteSigma(0);
        cfg.setLeaveSigma(0);
        cfg.setTruckType("LARGE_5TON");
        cfg.setCollectionTimeLabel("23:00");
        cfg.setLandlordEnabled(false);

        List<TripMetric> trips = engine().run(cfg, 1).getTripMetrics();

        assertEquals(2, trips.size(), "빈 경로 2개는 운행 목록에서 제외");
        assertEquals("T1", trips.get(0).truckId());
        assertEquals("T2", trips.get(1).truckId());
    }

    /** 잔류량이 건물별·유형별로 분해되고 최대 잔류 건물이 잡혀야 한다(§3.5). */
    @Test
    void residualDistributionByBuildingAndType() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(1);
        cfg.setNumBuildings(2);
        cfg.setNumTrucks(1);
        cfg.setResidentsPerBuilding(2000);     // 건물당 1,800kg 발생
        cfg.setWasteMeanKg(0.9);
        cfg.setWasteSigma(0);
        cfg.setLeaveSigma(0);
        cfg.setTruckType("LARGE_5TON");
        cfg.setRouteAvailableCapacityKg(1000.0);   // A만 1,000 수거 → B 전량 잔류
        cfg.setCollectionTimeLabel("23:00");
        cfg.setLandlordEnabled(false);

        SimulationResult r = engine().run(cfg, 1);

        // A: 1,800−1,000 = 800 잔류, B: 1,800 전량 잔류 → 최대 잔류 건물은 B(Node_B)
        assertEquals(800.0, r.getResidualByBuilding().get("Node_A"), 1e-6);
        assertEquals(1800.0, r.getResidualByBuilding().get("Node_B"), 1e-6);
        assertEquals("Node_B", r.getMaxResidualBuilding());
        assertEquals(1800.0, r.getMaxResidualBuildingKg(), 1e-6);
        // 단일 유형(GENERAL) 잔류 = 전체 잔류 = 2,600
        assertEquals(2600.0, r.getResidualByWasteType().get("GENERAL"), 1e-6);
        // 트럭 1대가 A·B 모두 담당 → 트럭 미수거 잔류 = 2,600
        assertEquals(2600.0, r.getResidualByTruck().get("T1"), 1e-6);
    }
}
