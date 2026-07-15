package com.wastesim.model;

import java.util.List;
import java.util.Map;

public class SimulationResult {

    private SimulationConfig simulationConfig;

    private String collectionTimeLabel;
    private int totalComplaints;
    private Map<String, Integer> byOccupation;
    private Map<Integer, Integer> byDay;
    private double peakFillKg;
    private int seed;
    private Map<Integer, Double> wasteByMonth;   // 월(0-based) → 총 배출량(kg)

    // Multi-seed experiment summary
    private double meanComplaints;
    private double stdComplaints;
    private List<Integer> allTotals;
    private Map<String, Object> byOccupationSummary;

    // ── 교통 레이어 (TRAFFIC_EXTENSION_DESIGN.md §4) ────────────────────────
    /** 교통 정체(RED 구간) 통과로 발생한 민원 수. trafficEnabled=false면 0. */
    private int trafficComplaints;
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

    public double getMeanComplaints() { return meanComplaints; }
    public void setMeanComplaints(double v) { this.meanComplaints = v; }

    public double getStdComplaints() { return stdComplaints; }
    public void setStdComplaints(double v) { this.stdComplaints = v; }

    public List<Integer> getAllTotals() { return allTotals; }
    public void setAllTotals(List<Integer> v) { this.allTotals = v; }

    public Map<String, Object> getByOccupationSummary() { return byOccupationSummary; }
    public void setByOccupationSummary(Map<String, Object> v) { this.byOccupationSummary = v; }

    public SimulationConfig getSimulationConfig() { return simulationConfig; }
    public void setSimulationConfig(SimulationConfig v) { this.simulationConfig = v; }

    public int getTrafficComplaints() { return trafficComplaints; }
    public void setTrafficComplaints(int v) { this.trafficComplaints = v; }

    public double getAvgCompletionMinutes() { return avgCompletionMinutes; }
    public void setAvgCompletionMinutes(double v) { this.avgCompletionMinutes = v; }
}
