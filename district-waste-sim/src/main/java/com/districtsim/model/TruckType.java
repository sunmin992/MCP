package com.districtsim.model;

import java.util.Locale;

/** 수거 차종. 적재 용량과 구역 사이 이동 속도 배수가 다르다(작은 차가 골목에서 빠르다). */
public enum TruckType {

    LARGE_5TON("5톤", 5000, 1.0),
    MEDIUM_2P5T("2.5톤", 2500, 0.85),
    SMALL_1TON("1톤", 1000, 0.7);

    public final String label;
    public final double capacityKg;
    /** 구역 사이 이동시간 배수. 1.0 이 5톤 기준. */
    public final double travelFactor;

    TruckType(String label, double capacityKg, double travelFactor) {
        this.label = label;
        this.capacityKg = capacityKg;
        this.travelFactor = travelFactor;
    }

    public static TruckType fromName(String name) {
        if (name != null) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            for (TruckType t : values()) if (t.name().equals(key)) return t;
        }
        throw new IllegalArgumentException("알 수 없는 차종: " + name
                + " (허용: LARGE_5TON, MEDIUM_2P5T, SMALL_1TON)");
    }
}
