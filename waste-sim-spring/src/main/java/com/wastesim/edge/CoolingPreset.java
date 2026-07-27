package com.wastesim.edge;

/** 냉각 상태 요인(실험 설계 §3 "냉각 상태"). */
public enum CoolingPreset {
    /** 기본 상태 — 방열판·팬 없음. */
    BARE,
    /** 방열판만(수동 냉각). */
    PASSIVE,
    /** 팬 포함(능동 냉각). */
    ACTIVE;

    public static CoolingPreset parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        return switch (s) {
            case "BARE", "NONE", "기본" -> BARE;
            case "PASSIVE", "HEATSINK", "방열판" -> PASSIVE;
            case "ACTIVE", "FAN", "팬" -> ACTIVE;
            default -> null;
        };
    }
}
