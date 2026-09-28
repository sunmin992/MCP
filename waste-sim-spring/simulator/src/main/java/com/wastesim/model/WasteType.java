package com.wastesim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 분리배출 쓰레기 종류. 종류별 수거장(용량)·민원 임계·수거 주기를 가진다.
 *  - fraction   : 거주민 일일 배출량 중 이 종류의 비율
 *  - capacity   : 종류별 수거장 용량(kg)
 *  - threshold  : 민원 임계(적재율)
 *  - intervalDays: 수거 주기(1=매일, 2=격일)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WasteType {

    private String key;
    private String labelKo;
    private double fraction;
    private double capacity;
    private double threshold;
    private int intervalDays;

    /**
     * 이 종류를 수거하는 요일 집합(0=월 … 6=일). {@code null}이면 {@link #intervalDays}를 쓴다.
     *
     * <p>종류마다 요일이 다른 것이 실제다 — 포항시 북구는 생활쓰레기 월·화·목·금,
     * 음식물 화·목·토, 재활용 수·토다({@code schedule/pohang-bukgu-collection-schedule.json}).
     */
    private List<Integer> collectionDaysOfWeek;

    public WasteType() {}

    public WasteType(String key, String labelKo, double fraction,
                     double capacity, double threshold, int intervalDays) {
        this.key = key;
        this.labelKo = labelKo;
        this.fraction = fraction;
        this.capacity = capacity;
        this.threshold = threshold;
        this.intervalDays = Math.max(1, intervalDays);
    }

    /** 분리배출 기본 3종: 일반 / 음식물 / 재활용 */
    public static List<WasteType> defaultSeparated() {
        return Arrays.asList(
                new WasteType("GENERAL",   "일반",   0.5, 30, 0.8, 1),  // 매일
                new WasteType("FOOD",      "음식물", 0.3, 10, 0.7, 1),  // 냄새 → 낮은 임계, 매일
                new WasteType("RECYCLING", "재활용", 0.2, 50, 0.9, 2)); // 격일
    }

    /** 통합 수거(단일 종류) — 기존 동작과 동일하게 cfg의 용량/임계/주기를 사용 */
    public static WasteType single(double capacity, double threshold, int intervalDays) {
        return new WasteType("GENERAL", "일반", 1.0, capacity, threshold, intervalDays);
    }

    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public String getLabelKo() { return labelKo; }
    public void setLabelKo(String v) { this.labelKo = v; }
    public double getFraction() { return fraction; }
    public void setFraction(double v) { this.fraction = v; }
    public double getCapacity() { return capacity; }
    public void setCapacity(double v) { this.capacity = v; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double v) { this.threshold = v; }
    public List<Integer> getCollectionDaysOfWeek() { return collectionDaysOfWeek; }
    public void setCollectionDaysOfWeek(List<Integer> v) { this.collectionDaysOfWeek = v; }

    /** 이 종류가 요일 집합으로 스케줄을 정하는가. */
    public boolean usesDaysOfWeek() {
        return collectionDaysOfWeek != null && !collectionDaysOfWeek.isEmpty();
    }

    /** 요일 집합을 지정한 사본. 기존 생성자를 건드리지 않으려고 별도로 둔다. */
    public WasteType withDaysOfWeek(List<Integer> days) {
        WasteType t = new WasteType(key, labelKo, fraction, capacity, threshold, intervalDays);
        t.collectionDaysOfWeek = days;
        return t;
    }

    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int v) { this.intervalDays = Math.max(1, v); }
}
