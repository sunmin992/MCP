package com.wastesim.model;

/**
 * 수거 차량 종류 — 용량·기동성·골목 진입 가능 여부의 트레이드오프.
 * (TRAFFIC_EXTENSION_DESIGN.md §2.2)
 */
public enum TruckType {
    LARGE_5TON  ("5톤", 5000.0, 1.0, false),   // 용량 큰 대신 골목 진입 불가, 정체 유발 큼
    MEDIUM_2P5T ("2.5톤", 2500.0, 1.2, true),
    SMALL_1TON  ("1톤",  1000.0, 1.6, true);   // 용량 작지만 기동성·골목 진입 우수

    public final String labelKo;
    public final double capacityKg;      // 트럭 1대 수거 용량
    public final double mobilityFactor;  // 이동속도 배수(클수록 정체 영향 적음)
    public final boolean alleyAccess;    // 이면도로(골목) 진입 가능 여부

    TruckType(String labelKo, double capacityKg, double mobilityFactor, boolean alleyAccess) {
        this.labelKo = labelKo;
        this.capacityKg = capacityKg;
        this.mobilityFactor = mobilityFactor;
        this.alleyAccess = alleyAccess;
    }

    /** 이름 → enum (대소문자 무관). 알 수 없으면 기본값 LARGE_5TON. */
    public static TruckType fromName(String name) {
        if (name == null || name.isBlank()) return LARGE_5TON;
        for (TruckType t : values()) {
            if (t.name().equalsIgnoreCase(name.trim())) return t;
        }
        throw new IllegalArgumentException("Unknown truck type: " + name);
    }
}
