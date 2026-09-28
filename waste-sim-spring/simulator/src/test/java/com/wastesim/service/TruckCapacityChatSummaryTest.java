package com.wastesim.service;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.simulation.SimulationEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 채팅은 {@link SimulationService#runExperiment}의 다중 시드 요약을 렌더한다.
 * P2·P3·P4 지표가 이 요약 객체에 실려야 채팅/UI에 나타나므로, 요약 단계에서
 * 지표가 채워지는지 검증한다(엔진 단위 테스트와 별개 — 시드 집계 경로 확인).
 */
class TruckCapacityChatSummaryTest {

    private SimulationService service() {
        return new SimulationService(new SimulationEngine(new TrafficDataService()));
    }

    @Test
    void multiSeedSummaryCarriesCapacityDiagnosticsTripsAndResidual() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(1);
        cfg.setSeeds(3);                       // 다중 시드 요약 경로
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

        SimulationResult summary = service().runExperiment(cfg);

        // P2 — 용량 부족 진단
        assertTrue(summary.getUnservedPickupCount() > 0, "미수거 방문이 요약에 집계돼야");
        assertTrue(summary.getUncollectedDemandKg() > 0, "미수거 수요가 요약에 집계돼야");
        // P3 — 운행별 상세(요약은 시드 평균)
        assertNotNull(summary.getTripMetrics());
        assertEquals(1, summary.getTripMetrics().size());
        assertEquals("T1", summary.getTripMetrics().get(0).truckId());
        // P4 — 잔류 분포
        assertNotNull(summary.getResidualByBuilding());
        assertEquals(2, summary.getResidualByBuilding().size());
        assertEquals("Node_B", summary.getMaxResidualBuilding());
        assertTrue(summary.getMaxResidualBuildingKg() > 0);
        assertTrue(summary.getResidualByTruck().containsKey("T1"));
    }
}
