package com.wastesim.service;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.simulation.SimulationEngine;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.IntStream;

@Service
public class SimulationService {

    private final SimulationEngine engine;

    public SimulationService(SimulationEngine engine) {
        this.engine = engine;
    }

    /**
     * 단일 시드 실행
     */
    public SimulationResult runSingle(SimulationConfig cfg, int seed) {
        return engine.run(cfg, seed);
    }

    /**
     * 다중 시드 실험 — 평균/표준편차 포함 결과 반환
     */
    public SimulationResult runExperiment(SimulationConfig cfg) {
        List<Integer> totals = new ArrayList<>();
        Map<String, List<Integer>> occTotals = new LinkedHashMap<>();

        for (int seed = 1; seed <= cfg.getSeeds(); seed++) {
            SimulationResult r = engine.run(cfg, seed);
            totals.add(r.getTotalComplaints());
            r.getByOccupation().forEach((occ, cnt) ->
                    occTotals.computeIfAbsent(occ, k -> new ArrayList<>()).add(cnt));
        }

        double mean = totals.stream().mapToInt(Integer::intValue).average().orElse(0);
        double std = stddev(totals);

        SimulationResult summary = new SimulationResult();
        summary.setCollectionTimeLabel(cfg.getCollectionTimeLabel());
        summary.setMeanComplaints(Math.round(mean * 10.0) / 10.0);
        summary.setStdComplaints(Math.round(std * 10.0) / 10.0);
        summary.setAllTotals(totals);

        // 직업별 평균
        Map<String, Object> occSummary = new LinkedHashMap<>();
        occTotals.forEach((occ, vals) -> {
            double occMean = vals.stream().mapToInt(Integer::intValue).average().orElse(0);
            occSummary.put(occ, Math.round(occMean * 10.0) / 10.0);
        });
        summary.setByOccupationSummary(occSummary);

        return summary;
    }

    private double stddev(List<Integer> vals) {
        double mean = vals.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = vals.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average().orElse(0);
        return Math.sqrt(variance);
    }
}
