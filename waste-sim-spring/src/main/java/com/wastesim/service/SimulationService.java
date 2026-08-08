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
        List<Integer> wasteComplaints = new ArrayList<>();
        List<Integer> landlordComplaints = new ArrayList<>();
        List<Double> trafficPenalties = new ArrayList<>();
        List<Double> completionMinutes = new ArrayList<>();
        List<Double> generatedWaste = new ArrayList<>();
        List<Double> collectedWaste = new ArrayList<>();
        List<Double> residualWaste = new ArrayList<>();
        List<Double> availableCapacity = new ArrayList<>();
        List<Double> truckUtilization = new ArrayList<>();
        List<Double> collectionUtilization = new ArrayList<>();
        List<Integer> partialPickups = new ArrayList<>();
        List<Integer> unservedPickups = new ArrayList<>();
        List<Integer> exhaustedTrips = new ArrayList<>();
        List<Double> uncollectedDemand = new ArrayList<>();
        List<Double> massBalanceErrors = new ArrayList<>();
        List<List<com.wastesim.model.TripMetric>> perSeedTrips = new ArrayList<>();
        List<Map<String, Double>> perSeedResidualBuilding = new ArrayList<>();
        List<Map<String, Double>> perSeedResidualType = new ArrayList<>();
        List<Map<String, Double>> perSeedResidualTruck = new ArrayList<>();

        for (int seed = 1; seed <= cfg.getSeeds(); seed++) {
            SimulationResult r = engine.run(cfg, seed);
            totals.add(r.getTotalComplaints());
            r.getByOccupation().forEach((occ, cnt) ->
                    occTotals.computeIfAbsent(occ, k -> new ArrayList<>()).add(cnt));
            wasteComplaints.add(r.getWasteOverflowComplaints());
            landlordComplaints.add(r.getLandlordComplaints());
            trafficPenalties.add(r.getTrafficPenalty());
            completionMinutes.add(r.getAvgCompletionMinutes());
            generatedWaste.add(r.getGeneratedWasteKg());
            collectedWaste.add(r.getCollectedWasteKg());
            residualWaste.add(r.getResidualWasteKg());
            availableCapacity.add(r.getAvailableCollectionCapacityKg());
            truckUtilization.add(r.getTruckUtilizationPercent());
            collectionUtilization.add(r.getCollectionCapacityUtilizationPercent());
            partialPickups.add(r.getPartialPickupCount());
            unservedPickups.add(r.getUnservedPickupCount());
            exhaustedTrips.add(r.getCapacityExhaustedTripCount());
            uncollectedDemand.add(r.getUncollectedDemandKg());
            massBalanceErrors.add(r.getMassBalanceErrorKg());
            if (r.getTripMetrics() != null) perSeedTrips.add(r.getTripMetrics());
            if (r.getResidualByBuilding() != null) perSeedResidualBuilding.add(r.getResidualByBuilding());
            if (r.getResidualByWasteType() != null) perSeedResidualType.add(r.getResidualByWasteType());
            if (r.getResidualByTruck() != null) perSeedResidualTruck.add(r.getResidualByTruck());
        }

        double mean = totals.stream().mapToInt(Integer::intValue).average().orElse(0);
        double std = stddev(totals);

        SimulationResult summary = new SimulationResult();
        summary.setCollectionTimeLabel(cfg.getCollectionTimeLabel());
        summary.setMeanComplaints(Math.round(mean * 10.0) / 10.0);
        summary.setMeanWasteOverflowComplaints(round1(wasteComplaints.stream().mapToInt(Integer::intValue).average().orElse(0)));
        summary.setMeanLandlordComplaints(round1(landlordComplaints.stream().mapToInt(Integer::intValue).average().orElse(0)));
        summary.setStdComplaints(Math.round(std * 10.0) / 10.0);
        summary.setAllTotals(totals);

        // 직업별 평균
        Map<String, Object> occSummary = new LinkedHashMap<>();
        occTotals.forEach((occ, vals) -> {
            double occMean = vals.stream().mapToInt(Integer::intValue).average().orElse(0);
            occSummary.put(occ, Math.round(occMean * 10.0) / 10.0);
        });
        summary.setByOccupationSummary(occSummary);

        // 교통 레이어 요약(§4) — 시드 평균. trafficEnabled=false면 항상 0.
        double trafficMean = trafficPenalties.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double completionMean = completionMinutes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        summary.setTrafficPenalty(Math.round(trafficMean * 100.0) / 100.0);
        summary.setAvgCompletionMinutes(Math.round(completionMean * 10.0) / 10.0);
        summary.setGeneratedWasteKg(round2(mean(generatedWaste)));
        summary.setCollectedWasteKg(round2(mean(collectedWaste)));
        summary.setResidualWasteKg(round2(mean(residualWaste)));
        summary.setAvailableCollectionCapacityKg(round2(mean(availableCapacity)));
        summary.setTruckUtilizationPercent(round2(mean(truckUtilization)));
        summary.setCollectionCapacityUtilizationPercent(round2(mean(collectionUtilization)));
        // 진단 지표(§3.3) — 횟수는 시드 평균을 반올림, 무게는 평균 그대로.
        summary.setPartialPickupCount((int) Math.round(meanInt(partialPickups)));
        summary.setUnservedPickupCount((int) Math.round(meanInt(unservedPickups)));
        summary.setCapacityExhaustedTripCount((int) Math.round(meanInt(exhaustedTrips)));
        summary.setUncollectedDemandKg(round2(mean(uncollectedDemand)));
        summary.setMassBalanceErrorKg(round2(mean(massBalanceErrors)));
        summary.setTripMetrics(averageTripMetrics(perSeedTrips));   // §3.4

        // P4(§3.5): 잔류 분포는 키(건물·유형·트럭)가 시드 무관하게 같아 키별로 평균한다.
        Map<String, Double> avgResidualBuilding = averageMaps(perSeedResidualBuilding);
        summary.setResidualByBuilding(avgResidualBuilding);
        summary.setResidualByWasteType(averageMaps(perSeedResidualType));
        summary.setResidualByTruck(averageMaps(perSeedResidualTruck));
        String maxB = null;
        double maxKg = 0.0;
        for (Map.Entry<String, Double> e : avgResidualBuilding.entrySet()) {
            if (e.getValue() > maxKg) { maxKg = e.getValue(); maxB = e.getKey(); }
        }
        summary.setMaxResidualBuilding(maxB);
        summary.setMaxResidualBuildingKg(round2(maxKg));

        return summary;
    }

    private double stddev(List<Integer> vals) {
        double mean = vals.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = vals.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double meanInt(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    /** 키별로 시드 평균을 낸다(§3.5). 키 집합은 시드와 무관하게 동일하다. */
    private static Map<String, Double> averageMaps(List<Map<String, Double>> perSeed) {
        Map<String, double[]> acc = new LinkedHashMap<>();   // key → [합, 개수]
        for (Map<String, Double> m : perSeed) {
            if (m == null) continue;
            m.forEach((k, v) -> {
                double[] a = acc.computeIfAbsent(k, x -> new double[2]);
                a[0] += v;
                a[1] += 1;
            });
        }
        Map<String, Double> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, round2(a[0] / Math.max(1.0, a[1]))));
        return out;
    }

    /**
     * 운행별 지표를 시드 평균으로 합친다(§3.4). 운행 스케줄(트럭·배정용량·초기적재)은
     * 시드와 무관하게 결정되므로 같은 인덱스끼리 정렬해 수거량·이용률만 평균낸다.
     */
    private static List<com.wastesim.model.TripMetric> averageTripMetrics(
            List<List<com.wastesim.model.TripMetric>> perSeed) {
        if (perSeed.isEmpty()) return List.of();
        List<com.wastesim.model.TripMetric> first = perSeed.get(0);
        List<com.wastesim.model.TripMetric> out = new ArrayList<>(first.size());
        for (int i = 0; i < first.size(); i++) {
            com.wastesim.model.TripMetric s = first.get(i);
            double collected = 0, finalLoad = 0, unused = 0, util = 0, partial = 0;
            int cnt = 0;
            for (List<com.wastesim.model.TripMetric> seedTrips : perSeed) {
                if (i >= seedTrips.size()) continue;
                com.wastesim.model.TripMetric t = seedTrips.get(i);
                collected += t.collectedKg();
                finalLoad += t.finalLoadKg();
                unused    += t.unusedCapacityKg();
                util      += t.utilizationPercent();
                partial   += t.partialPickupCount();
                cnt++;
            }
            int div = Math.max(1, cnt);
            out.add(new com.wastesim.model.TripMetric(s.truckId(), s.tripId(),
                    s.allocatedCapacityKg(), s.initialLoadKg(), s.availablePickupCapacityKg(),
                    round2(collected / div), round2(finalLoad / div), round2(unused / div),
                    round2(util / div), (int) Math.round(partial / div)));
        }
        return out;
    }
}
