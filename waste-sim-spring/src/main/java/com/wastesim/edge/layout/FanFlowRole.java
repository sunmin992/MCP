package com.wastesim.edge.layout;

/**
 * 팬 하나가 맡는 역할 — 함체 안으로 불어넣는가(흡기), 밖으로 빼는가(배기).
 *
 * <p>기존 {@code FanArraySpec.FlowDirection}과 일부러 분리했다. 그쪽은 송풍 방향
 * (아래로/위로/수평)을 담는 열 시뮬레이션용 메타데이터고, 이쪽은 함체 기류의
 * 입·출구 역할이다. 같은 enum으로 묶으면 이 도구의 임시 계수가 열 스택 쪽 타입에
 * 얹혀 흘러 들어갈 통로가 생긴다(설계 §3.1 격리 규칙).
 */
public enum FanFlowRole {

    INTAKE("흡기"),
    EXHAUST("배기");

    private final String koLabel;

    FanFlowRole(String koLabel) { this.koLabel = koLabel; }

    public String koLabel() { return koLabel; }

    public String wire() { return name().toLowerCase(); }

    /** 읽을 수 없으면 null — 호출측이 거부한다(fail-closed). */
    public static FanFlowRole parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        for (FanFlowRole r : values()) {
            if (r.koLabel.equals(s) || r.name().equalsIgnoreCase(s)) return r;
        }
        return null;
    }
}
