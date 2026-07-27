package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * 엣지 도메인으로 확정된 요청이 <b>세 도구 중 무엇을 부르는지</b> 결정론적으로 고른다
 * ({@link DomainIntentDetector}가 도메인을, 이 클래스가 도구를 정한다 — 둘 다 LLM 없이).
 *
 * <p>순서가 중요하다. "방열판을 어디에 붙여야 스로틀링이 덜 걸릴까"처럼 두 도구의
 * 어휘가 함께 나오는 문장이 흔한데, 이때는 <b>더 구체적인 요청</b>(방열판 배치)을
 * 골라야 사용자가 기대한 답이 나온다. 그래서 특이성이 높은 순서로 검사한다.
 */
public final class EdgeToolSelector {

    private EdgeToolSelector() {}

    public static final String TOOL_THROTTLING = "simulate_edge_throttling";
    public static final String TOOL_HEATSINK = "simulate_heatsink_layout";
    public static final String TOOL_CALIBRATE = "calibrate_edge_thermal_model";

    /** 실측 데이터로 모델을 보정하려는 요청. */
    private static final Pattern CALIBRATE = Pattern.compile(
            "(캘리브|calibrat|보정|실측\\s*(데이터|값|로그|결과)|측정\\s*(데이터|로그|결과|값)"
            + "|프로파일|profile|csv|역추정|시정수\\s*(구|추정)|피팅|fitting)",
            Pattern.CASE_INSENSITIVE);

    /** 방열판 형상·배치를 비교하려는 요청. */
    private static final Pattern HEATSINK = Pattern.compile(
            "(방열판|히트\\s*싱크|heatsink|쿨러|핀\\s*(방향|개수|높이)|서멀\\s*(패드|구리스|그리스)"
            + "|배치|위치|오프셋|어긋|정렬|붙(이|여)|장착|부착)",
            Pattern.CASE_INSENSITIVE);

    /** @return 호출할 MCP 도구 이름. 엣지 도메인이면 항상 셋 중 하나를 반환한다(기본은 발열 시뮬레이션). */
    public static String select(String text) {
        if (text == null) return TOOL_THROTTLING;
        if (CALIBRATE.matcher(text).find()) return TOOL_CALIBRATE;
        if (HEATSINK.matcher(text).find()) return TOOL_HEATSINK;
        return TOOL_THROTTLING;
    }
}
