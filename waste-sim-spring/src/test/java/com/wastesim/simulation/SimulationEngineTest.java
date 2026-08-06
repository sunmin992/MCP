package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.SimulationService;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** DESIGN_DECISIONS.md D-11 — 민원이 0건이어도 필드가 존재하고 "0건"으로 렌더되어야 한다. */
class SimulationEngineTest {

    @Test
    void zeroComplaintRunKeepsAllOccupationKeysAtZero() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(5);
        cfg.setCapacity(100_000);   // 사실상 절대 차지 않는 용량 → 민원 0건 보장

        SimulationEngine engine = new SimulationEngine(new TrafficDataService());
        SimulationResult r = engine.run(cfg, 1);

        assertEquals(0, r.getTotalComplaints());
        assertNotNull(r.getByOccupation());
        // 기본 구성(생산직·학생·전업주부) 전원이 0건으로라도 키가 남아 있어야
        // "생산직 항목 자체가 사라져 보이는" 프론트엔드 누락(실측 재현)이 재발하지 않는다.
        assertEquals(3, r.getByOccupation().size());
        r.getByOccupation().values().forEach(v -> assertEquals(0, v));
    }

    @Test
    void zeroComplaintExperimentSummaryHasAllOccupationsAtZero() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(5);
        cfg.setSeeds(3);
        cfg.setCapacity(100_000);

        SimulationService svc = new SimulationService(new SimulationEngine(new TrafficDataService()));
        SimulationResult r = svc.runExperiment(cfg);

        assertEquals(0.0, r.getMeanComplaints());
        assertEquals(3, r.getByOccupationSummary().size());
        r.getByOccupationSummary().values().forEach(v -> assertEquals(0.0, ((Number) v).doubleValue()));
    }

    @Test
    void truckCapacityLimitsCollectionAcrossDays() {
        SimulationConfig base = new SimulationConfig();
        base.setDays(2);
        base.setNumBuildings(1);
        base.setResidentsPerBuilding(2000);
        base.setWasteMeanKg(0.9);
        base.setWasteSigma(0);
        base.setLeaveSigma(0);
        base.setCapacity(10_000);
        base.setThreshold(0.15);       // 1,500kg부터 민원
        base.setCollectionTimeLabel("23:00");

        SimulationConfig small = base.copy();
        small.setTruckType("SMALL_1TON");
        SimulationConfig large = base.copy();
        large.setTruckType("LARGE_5TON");

        SimulationEngine engine = new SimulationEngine(new TrafficDataService());
        SimulationResult smallResult = engine.run(small, 1);
        SimulationResult largeResult = engine.run(large, 1);

        assertTrue(smallResult.getWasteOverflowComplaints() > largeResult.getWasteOverflowComplaints(),
                "1톤 트럭은 첫날 폐기물을 전부 싣지 못해 다음 날 민원이 더 많아야 함");
    }
}
