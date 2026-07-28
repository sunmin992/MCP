package com.wastesim.edge;

/** 운용 모드(실험 설계 §3 "운용 모드"). */
public enum WorkloadMode {
    /** 동일 목표 FPS 모드 — 목표 FPS만 채우고 남는 시간은 유휴. 주 비교 대상. */
    TARGET_FPS,
    /** 최대 처리량 모드 — 클럭이 허용하는 만큼 계속 추론. 스트레스 검증용. */
    MAX_THROUGHPUT;

    public static WorkloadMode parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        return switch (s) {
            case "TARGET_FPS", "TARGETFPS", "FIXED_FPS", "목표fps" -> TARGET_FPS;
            case "MAX_THROUGHPUT", "MAXTHROUGHPUT", "MAX", "최대처리량" -> MAX_THROUGHPUT;
            default -> null;
        };
    }
}
