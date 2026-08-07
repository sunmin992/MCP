package com.wastesim.model;

import java.util.List;
import java.util.Map;

public class SimulationResult {

    private SimulationConfig simulationConfig;

    private String collectionTimeLabel;
    private int totalComplaints;
    /** 적재 임계 초과로 거주민 배출 시 발생한 생활쓰레기 민원. */
    private int wasteOverflowComplaints;
    /** 임대인 점검 에이전트가 발생시킨 민원. */
    private int landlordComplaints;
    private Map<String, Integer> byOccupation;
    private Map<Integer, Integer> byDay;
    private double peakFillKg;
    private int seed;
    private Map<Integer, Double> wasteByMonth;   // 월(0-based) → 총 배출량(kg)
    private double generatedWasteKg;
    private double collectedWasteKg;
    private double residualWasteKg;
    private double availableCollectionCapacityKg;
    /** (초기 적재량 + 신규 수거량) / 경로 배정용량 합계 × 100. */
    private double truckUtilizationPercent;
    /** 신규 수거량 / 신규 수거 가능용량 합계 × 100. */
    private double collectionCapacityUtilizationPercent;

    // Multi-seed experiment summary
    private double meanComplaints;
    private double meanWasteOverflowComplaints;
    private double meanLandlordComplaints;
    private double stdComplaints;
    private List<Integer> allTotals;
    private Map<String, Object> byOccupationSummary;

    // ── 교통 레이어 (TRAFFIC_EXTENSION_DESIGN.md §4) ────────────────────────
    /** 교통 정체(RED 구간) 통과 패널티. 생활쓰레기 민원과 단위가 달라 totalComplaints에 합산하지 않는다. */
    private double trafficPenalty;
    /** 하루 수거 완료까지 평균 소요 시간(분, 첫 슬롯 시작 대비). */
    private double avgCompletionMinutes;

    public SimulationResult() {}

    public SimulationResult(String collectionTimeLabel, int totalComplaints,
                            Map<String, Integer> byOccupation, Map<Integer, Integer> byDay,
                            double peakFillKg, int seed) {
        this.collectionTimeLabel = collectionTimeLabel;
        this.totalComplaints = totalComplaints;
        this.byOccupation = byOccupation;
        this.byDay = byDay;
        this.peakFillKg = peakFillKg;
        this.seed = seed;
    }

    // Getters & setters
    public String getCollectionTimeLabel() { return collectionTimeLabel; }
    public void setCollectionTimeLabel(String v) { this.collectionTimeLabel = v; }

    public int getTotalComplaints() { return totalComplaints; }
    public void setTotalComplaints(int v) { this.totalComplaints = v; }

    public int getWasteOverflowComplaints() { return wasteOverflowComplaints; }
    public void setWasteOverflowComplaints(int v) { this.wasteOverflowComplaints = v; }

    public int getLandlordComplaints() { return landlordComplaints; }
    public void setLandlordComplaints(int v) { this.landlordComplaints = v; }

    public Map<String, Integer> getByOccupation() { return byOccupation; }
    public void setByOccupation(Map<String, Integer> v) { this.byOccupation = v; }

    public Map<Integer, Integer> getByDay() { return byDay; }
    public void setByDay(Map<Integer, Integer> v) { this.byDay = v; }

    public double getPeakFillKg() { return peakFillKg; }
    public void setPeakFillKg(double v) { this.peakFillKg = v; }

    public int getSeed() { return seed; }
    public void setSeed(int v) { this.seed = v; }

    public Map<Integer, Double> getWasteByMonth() { return wasteByMonth; }
    public void setWasteByMonth(Map<Integer, Double> v) { this.wasteByMonth = v; }

    public double getGeneratedWasteKg() { return generatedWasteKg; }
    public void setGeneratedWasteKg(double v) { this.generatedWasteKg = v; }
    public double getCollectedWasteKg() { return collectedWasteKg; }
    public void setCollectedWasteKg(double v) { this.collectedWasteKg = v; }
    public double getResidualWasteKg() { return residualWasteKg; }
    public void setResidualWasteKg(double v) { this.residualWasteKg = v; }
    public double getAvailableCollectionCapacityKg() { return availableCollectionCapacityKg; }
    public void setAvailableCollectionCapacityKg(double v) { this.availableCollectionCapacityKg = v; }
    public double getTruckUtilizationPercent() { return truckUtilizationPercent; }
    public void setTruckUtilizationPercent(double v) { this.truckUtilizationPercent = v; }
    public double getCollectionCapacityUtilizationPercent() { return collectionCapacityUtilizationPercent; }
    public void setCollectionCapacityUtilizationPercent(double v) { this.collectionCapacityUtilizationPercent = v; }

    public double getMeanComplaints() { return meanComplaints; }
    public void setMeanComplaints(double v) { this.meanComplaints = v; }

    public double getMeanWasteOverflowComplaints() { return meanWasteOverflowComplaints; }
    public void setMeanWasteOverflowComplaints(double v) { this.meanWasteOverflowComplaints = v; }

    public double getMeanLandlordComplaints() { return meanLandlordComplaints; }
    public void setMeanLandlordComplaints(double v) { this.meanLandlordComplaints = v; }

    public double getStdComplaints() { return stdComplaints; }
    public void setStdComplaints(double v) { this.stdComplaints = v; }

    public List<Integer> getAllTotals() { return allTotals; }
    public void setAllTotals(List<Integer> v) { this.allTotals = v; }

    public Map<String, Object> getByOccupationSummary() { return byOccupationSummary; }
    public void setByOccupationSummary(Map<String, Object> v) { this.byOccupationSummary = v; }

    public SimulationConfig getSimulationConfig() { return simulationConfig; }
    public void setSimulationConfig(SimulationConfig v) { this.simulationConfig = v; }

    public double getTrafficPenalty() { return trafficPenalty; }
    public void setTrafficPenalty(double v) { this.trafficPenalty = v; }

    public double getAvgCompletionMinutes() { return avgCompletionMinutes; }
    public void setAvgCompletionMinutes(double v) { this.avgCompletionMinutes = v; }
}
