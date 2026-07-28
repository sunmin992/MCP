package com.wastesim.edge;

/** 회복 정책(실험 설계 §3 "회복 정책 (TRT 실험)"). */
public enum RecoveryPolicy {
    /** R1 — 완전 중지: AI 추론 일시 정지(소프트웨어). */
    R1_STOP,
    /** R2 — 저부하 유지: 목표 FPS를 25% 수준으로 제어(소프트웨어). */
    R2_LOW_LOAD,
    /** R3 — 능동 냉각: 팬 100%로 돌리며 서비스 유지(하드웨어). */
    R3_ACTIVE_COOLING,
    /** 대조군 — 아무 조치 없이 부하를 그대로 유지(자연 회복 TED만 관찰). */
    NONE;

    public static RecoveryPolicy parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        return switch (s) {
            case "R1", "R1_STOP", "STOP" -> R1_STOP;
            case "R2", "R2_LOW_LOAD", "LOW_LOAD", "LOWLOAD" -> R2_LOW_LOAD;
            case "R3", "R3_ACTIVE_COOLING", "ACTIVE_COOLING", "FAN" -> R3_ACTIVE_COOLING;
            case "NONE", "CONTROL", "없음" -> NONE;
            default -> null;
        };
    }
}
