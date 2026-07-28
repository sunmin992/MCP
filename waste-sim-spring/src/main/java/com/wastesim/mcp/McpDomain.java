package com.wastesim.mcp;

/**
 * 이 MCP 서버가 서비스하는 <b>시뮬레이션 도메인</b>. 서버는 허브 하나로 두고
 * 도메인별로 진입점을 갈라 주기 위한 식별자다(MCP_모델_연결_방법.md §7 — 서버를
 * 물리적으로 쪼개지 않고 같은 서버 안에서 도메인을 나누는 선택).
 *
 * <p>이 enum이 세 곳에서 같은 이름으로 쓰인다 — 도메인을 하나 추가할 때 손댈
 * 곳이 흩어지지 않게 하는 것이 목적이다.
 * <ul>
 *   <li><b>MCP 엔드포인트</b> — {@code POST /mcp/{slug}} 의 경로 조각({@link #slug()})</li>
 *   <li><b>tools/list 필터</b> — {@link McpToolCatalog}가 해당 도메인 도구만 노출</li>
 *   <li><b>웹 UI 경로</b> — {@code localhost:8090/{slug}} (루트는 도메인 중립 시작화면)</li>
 * </ul>
 *
 * <p><b>왜 {@code McpToolProvider}·{@code SimulationModelProvider}에 도메인을
 * 들려 보내는가</b>: 도메인 목록을 컨트롤러나 카탈로그에 하드코딩하면 도구가
 * 늘 때마다 "이 도구는 어느 엔드포인트에 붙는가"를 중앙에서 다시 편집해야 한다.
 * 도구 자신이 자기 도메인을 선언하면 {@link McpToolRegistry}가 자동으로 모아
 * 주므로, 새 도구·새 도메인이 생겨도 컨트롤러는 그대로다(Open/Closed 원칙 —
 * 레지스트리 패턴과 같은 이유).
 */
public enum McpDomain {

    /** 장량동 생활쓰레기 수거 DEVS 시뮬레이션 — {@code SimulationConfig} 기반 모델 계열. */
    WASTE("waste", "장량동 생활쓰레기 수거 시뮬레이션",
            "수거 시각·트럭 편성·교통 정체·분리배출을 바꿔가며 민원 발생을 예측한다."),

    /** 라즈베리파이 엣지 발열·스로틀링 — 장량동 스키마와 무관한 독립 도구 계열. */
    EDGE("edge", "라즈베리파이 엣지 발열 시뮬레이션",
            "보드·워크로드·냉각 조건에 따른 SoC 온도 상승과 스로틀링 시점, 방열판 배치 효과를 예측한다.");

    private final String slug;
    private final String label;
    private final String description;

    McpDomain(String slug, String label, String description) {
        this.slug = slug;
        this.label = label;
        this.description = description;
    }

    /** URL 경로 조각. {@code POST /mcp/edge}, {@code GET /edge}. */
    public String slug() {
        return slug;
    }

    /** 사람이 읽는 도메인 이름(시작화면 카드·serverInfo에 노출). */
    public String label() {
        return label;
    }

    /** 이 도메인이 무엇을 하는지 한 줄 설명(시작화면 카드에 노출). */
    public String description() {
        return description;
    }

    /**
     * URL 경로 조각으로 도메인을 찾는다. 대소문자는 무시한다.
     *
     * @return 매칭되는 도메인, 없으면 {@code null}(호출부가 404/에러 응답을 결정)
     */
    public static McpDomain fromSlug(String slug) {
        if (slug == null) return null;
        for (McpDomain d : values()) {
            if (d.slug.equalsIgnoreCase(slug)) return d;
        }
        return null;
    }
}
