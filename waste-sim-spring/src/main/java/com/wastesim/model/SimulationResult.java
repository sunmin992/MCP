package com.wastesim.model;

import java.util.List;
import java.util.Map;

public class SimulationResult {

    private SimulationConfig simulationConfig;

    /**
     * 이 결과가 어떤 좌표로 계산됐는가. 좌표 미사용·구역 근사는 이동시간 모드에서 정해지고,
     * OSRM 지점 계산은 행렬 파일의 provenance에서 가져온다.
     *
     * <p>결과에 이것을 실어 보내는 이유는, 같은 "38분"이라도 현장 GPS로 계산한 38분과 교통
     * 구역으로 근사한 38분이 다른 것이기 때문이다. 표시가 없으면 읽는 사람이 그 차이를 알 수
     * 없고, 근사값이 실측처럼 인용된다.
     */
    private CoordinateQuality coordinateQuality = CoordinateQuality.NOT_USED;

    /**
     * 이 계산이 기댄 가정들. 좌표 품질과 다른 축이다 — 현장 GPS로 계산하면서도 정차시간은
     * 가정일 수 있다.
     */
    private java.util.List<String> dataQualityFlags = new java.util.ArrayList<>();
    private java.util.List<String> assumptionNotes = new java.util.ArrayList<>();

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

    // ── 용량 소진·부분수거 진단 (TRUCK_CAPACITY_ENHANCEMENT_PLAN.md §3.3) ────────
    /** 요청량 중 일부만 수거한 방문 횟수. */
    private int partialPickupCount;
    /** 잔여 용량이 없어 전혀 수거하지 못한 방문 횟수. */
    private int unservedPickupCount;
    /** 적재용량을 모두 사용한 운행(trip) 수. */
    private int capacityExhaustedTripCount;
    /** 수거 시점에 용량 부족으로 남긴 폐기물 양(kg). 종료 잔류량과 달리 다음 운행에서 재수거될 수 있다. */
    private double uncollectedDemandKg;
    /** 질량보존 오차 = 발생량 − 수거량 − 잔류량. 유형별 비율 합이 1이면 0에 수렴한다. */
    private double massBalanceErrorKg;
    /** 운행(trip)별 상세 지표(§3.4). 병목 트럭·경로 식별용. */
    private List<TripMetric> tripMetrics;

    // ── 잔류량 분포 (TRUCK_CAPACITY_ENHANCEMENT_PLAN.md §3.5) ────────────────────
    /** 건물(노드)별 최종 잔류량(kg). */
    private Map<String, Double> residualByBuilding;
    /** 폐기물 유형(key)별 최종 잔류량(kg). */
    private Map<String, Double> residualByWasteType;
    /** 트럭(경로)별 미수거 잔류량(kg). */
    private Map<String, Double> residualByTruck;
    /** 잔류량이 가장 많은 건물(노드). 잔류가 없으면 null. */
    private String maxResidualBuilding;
    /** 최대 잔류 건물의 잔류량(kg). */
    private double maxResidualBuildingKg;

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

    public int getPartialPickupCount() { return partialPickupCount; }
    public void setPartialPickupCount(int v) { this.partialPickupCount = v; }
    public int getUnservedPickupCount() { return unservedPickupCount; }
    public void setUnservedPickupCount(int v) { this.unservedPickupCount = v; }
    public int getCapacityExhaustedTripCount() { return capacityExhaustedTripCount; }
    public void setCapacityExhaustedTripCount(int v) { this.capacityExhaustedTripCount = v; }
    public double getUncollectedDemandKg() { return uncollectedDemandKg; }
    public void setUncollectedDemandKg(double v) { this.uncollectedDemandKg = v; }
    public double getMassBalanceErrorKg() { return massBalanceErrorKg; }
    public void setMassBalanceErrorKg(double v) { this.massBalanceErrorKg = v; }
    public List<TripMetric> getTripMetrics() { return tripMetrics; }
    public void setTripMetrics(List<TripMetric> v) { this.tripMetrics = v; }

    public Map<String, Double> getResidualByBuilding() { return residualByBuilding; }
    public void setResidualByBuilding(Map<String, Double> v) { this.residualByBuilding = v; }
    public Map<String, Double> getResidualByWasteType() { return residualByWasteType; }
    public void setResidualByWasteType(Map<String, Double> v) { this.residualByWasteType = v; }
    public Map<String, Double> getResidualByTruck() { return residualByTruck; }
    public void setResidualByTruck(Map<String, Double> v) { this.residualByTruck = v; }
    public String getMaxResidualBuilding() { return maxResidualBuilding; }
    public void setMaxResidualBuilding(String v) { this.maxResidualBuilding = v; }
    public double getMaxResidualBuildingKg() { return maxResidualBuildingKg; }
    public void setMaxResidualBuildingKg(double v) { this.maxResidualBuildingKg = v; }

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

    public CoordinateQuality getCoordinateQuality() { return coordinateQuality; }
    public void setCoordinateQuality(CoordinateQuality v) { this.coordinateQuality = v; }

    /** 이 계산이 기댄 가정의 코드 목록(예: {@code INTRA_ZONE_TIME_ASSUMED}). */
    public java.util.List<String> getDataQualityFlags() { return dataQualityFlags; }
    public void setDataQualityFlags(java.util.List<String> v) { this.dataQualityFlags = v; }

    public java.util.List<String> getAssumptionNotes() { return assumptionNotes; }
    public void setAssumptionNotes(java.util.List<String> v) { this.assumptionNotes = v; }

    /** 가정 하나를 기록한다. 코드와 문구를 짝지어 둔다. */
    public void addDataQualityFlag(DataQualityFlag flag, Object detail) {
        dataQualityFlags.add(flag.name());
        assumptionNotes.add(flag.message(detail));
    }

    /** 좌표 품질을 사람이 읽는 이름으로 — 결과 카드에 그대로 쓸 수 있다. */
    public String getCoordinateQualityLabel() {
        return coordinateQuality == null ? null : coordinateQuality.labelKo;
    }

    /**
     * 이 결과를 읽을 때 함께 봐야 하는 경고 전부 — 좌표 품질 경고와 계산 중 얹은 가정들.
     * 경고할 것이 없으면 빈 목록이다.
     */
    public java.util.List<String> getDataQualityWarnings() {
        java.util.List<String> all = new java.util.ArrayList<>();
        if (coordinateQuality != null && coordinateQuality.hasWarning()) {
            all.add(coordinateQuality.warning);
        }
        all.addAll(assumptionNotes);
        return all;
    }

    public SimulationConfig getSimulationConfig() { return simulationConfig; }
    public void setSimulationConfig(SimulationConfig v) { this.simulationConfig = v; }

    public double getTrafficPenalty() { return trafficPenalty; }
    public void setTrafficPenalty(double v) { this.trafficPenalty = v; }

    public double getAvgCompletionMinutes() { return avgCompletionMinutes; }
    public void setAvgCompletionMinutes(double v) { this.avgCompletionMinutes = v; }
}
