package com.wastesim.controller;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * POST /api/simulation/run — 단일 시드 실행
     */
    @PostMapping("/run")
    public ResponseEntity<SimulationResult> run(@RequestBody SimulationConfig cfg,
                                                 @RequestParam(defaultValue = "1") int seed) {
        SimulationResult result = simulationService.runSingle(cfg, seed);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/simulation/experiment — 다중 시드 실험
     */
    @PostMapping("/experiment")
    public ResponseEntity<SimulationResult> experiment(@RequestBody SimulationConfig cfg) {
        SimulationResult result = simulationService.runExperiment(cfg);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/simulation/compare — 여러 수거 시각 비교 실험
     */
    @PostMapping("/compare")
    public ResponseEntity<List<SimulationResult>> compare(
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<String> times = (List<String>) body.getOrDefault("times",
                List.of("10:00", "12:00", "14:00"));
        int days    = (int) body.getOrDefault("days", 30);
        int seeds   = (int) body.getOrDefault("seeds", 30);
        double leaveSigma = body.containsKey("leaveSigma")
                ? ((Number) body.get("leaveSigma")).doubleValue() : 30.0;

        List<SimulationResult> results = new ArrayList<>();
        for (String t : times) {
            SimulationConfig cfg = new SimulationConfig();
            cfg.setCollectionTimeLabel(t);
            cfg.setDays(days);
            cfg.setSeeds(seeds);
            cfg.setLeaveSigma(leaveSigma);
            results.add(simulationService.runExperiment(cfg));
        }
        return ResponseEntity.ok(results);
    }
}
