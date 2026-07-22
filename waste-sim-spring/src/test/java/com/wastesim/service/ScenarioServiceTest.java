package com.wastesim.service;

import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DESIGN_DECISIONS.md D-12 — 최적 수거시각이 동률이면 가장 이른 시각을 고른다. */
class ScenarioServiceTest {

    @Test
    void tiedBestMeanPicksEarliestTime() {
        SimulationService sim = mock(SimulationService.class);
        // 10:00→5.0(가장 나쁨), 11:00·12:00→3.0(동률 최선) — 이른 11:00이 선택돼야 한다.
        when(sim.runExperiment(any())).thenAnswer(inv -> {
            SimulationConfig cfg = inv.getArgument(0);
            double mean = switch (cfg.getCollectionTimeLabel()) {
                case "10:00" -> 5.0;
                case "11:00" -> 3.0;
                case "12:00" -> 3.0;
                default -> 9.0;
            };
            SimulationResult r = new SimulationResult();
            r.setMeanComplaints(mean);
            r.setStdComplaints(0.0);
            return r;
        });

        ScenarioService svc = new ScenarioService(sim);
        ScenarioResponse resp = svc.collectionSweep(new SimulationConfig(), 600, 720, 60);   // 10:00~12:00

        String bestInsight = resp.getInsights().stream()
                .filter(m -> "최적 수거시각".equals(m.get("key")))
                .map(m -> String.valueOf(m.get("value")))
                .findFirst().orElse("");
        assertTrue(bestInsight.startsWith("11:00"),
                "동률(11:00, 12:00)이면 이른 시각을 골라야 한다: " + bestInsight);
    }
}
