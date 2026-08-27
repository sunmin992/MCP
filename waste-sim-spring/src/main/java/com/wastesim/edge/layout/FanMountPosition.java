package com.wastesim.edge.layout;

/**
 * 40 mm 팬을 달 수 있는 함체 장착 위치 6곳.
 *
 * <p>{@code efficiency}는 통풍구 접근성과 예상 유로를 반영한 <b>임시 계수</b>다
 * (출처: dual_fan_all_layouts_preliminary.xlsx "가정" 시트, 2026-08-27).
 * CFD도 실측도 아니며, 배치 후보를 줄이는 상대 비교에만 쓴다.
 *
 * <p>{@code level}은 높이 코드다 — 하단 0, 상단 2. 자연대류가 아래에서 위로 흐르므로
 * 흡기가 배기보다 낮은지(같은 방향인지 거스르는지)를 이 값으로 판정한다.
 *
 * <p>{@code side}는 좌·우·중앙이다. 흡기와 배기가 <b>같은 측면</b>에 있으면 들어온
 * 공기가 보드를 지나지 않고 곧장 빠져나가는 단락(short circuit)이 생긴다.
 */
public enum FanMountPosition {

    BOTTOM("하단", 0, Side.CENTER, 0.95),
    TOP("상단", 2, Side.CENTER, 0.90),
    LEFT_BOTTOM("좌측 하단", 0, Side.LEFT, 0.78),
    LEFT_TOP("좌측 상단", 2, Side.LEFT, 0.82),
    RIGHT_BOTTOM("우측 하단", 0, Side.RIGHT, 0.78),
    RIGHT_TOP("우측 상단", 2, Side.RIGHT, 0.82);

    /** 함체에서 팬이 붙은 면. 단락 판정에 쓰이므로 CENTER를 따로 둔다. */
    public enum Side { CENTER, LEFT, RIGHT }

    private final String koLabel;
    private final int level;
    private final Side side;
    private final double efficiency;

    FanMountPosition(String koLabel, int level, Side side, double efficiency) {
        this.koLabel = koLabel;
        this.level = level;
        this.side = side;
        this.efficiency = efficiency;
    }

    public String koLabel() { return koLabel; }
    public int level() { return level; }
    public Side side() { return side; }
    public double efficiency() { return efficiency; }

    /** MCP 응답에 쓰는 소문자 키. */
    public String wire() { return name().toLowerCase(); }

    /**
     * 영문 키("bottom")와 한글 라벨("하단")을 모두 받는다 — 이 도구는 MCP로도,
     * 한국어 채팅으로도 불린다. 읽을 수 없으면 <b>추측하지 않고 null</b>을 돌려
     * 호출측이 거부하게 한다(fail-closed).
     */
    public static FanMountPosition parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (FanMountPosition p : values()) {
            if (p.koLabel.equals(s)) return p;
        }
        String key = s.toUpperCase().replace("-", "_").replace(" ", "_");
        for (FanMountPosition p : values()) {
            if (p.name().equals(key)) return p;
        }
        return null;
    }
}
