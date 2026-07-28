package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * 엣지 도메인으로 확정된 요청이 <b>세 도구 중 무엇을 부르는지</b> 결정론적으로 고른다
 * ({@link DomainIntentDetector}가 도메인을, 이 클래스가 도구를 정한다 — 둘 다 LLM 없이).
 *
 * <p>순서가 중요하다. "방열판을 어디에 붙여야 스로틀링이 덜 걸릴까"처럼 두 도구의
 * 어휘가 함께 나오는 문장이 흔한데, 이때는 <b>더 구체적인 요청</b>(방열판 배치)을
 * 골라야 사용자가 기대한 답이 나온다. 그래서 특이성이 높은 순서로 검사한다.
 *
 * <p>단, 특이성 판단의 기준은 "방열판"이라는 단어가 아니라 <b>배치·형상을 가리키는
 * 어휘</b>다({@link #HEATSINK} 주석 참고) — 방열판은 배치 비교의 대상이기도 하지만
 * 발열 실험의 냉각 조건("방열판만 상태에서")이기도 해서, 단어만 보고 고르면 조건을
 * 대상으로 착각한다.
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

    /**
     * 방열판의 <b>형상·배치</b>를 비교하려는 요청.
     *
     * <p>"방열판"·"히트싱크"·"쿨러" 같은 단어 자체는 여기 넣지 않는다. 그 단어는
     * 배치 비교의 대상일 수도 있지만 발열 실험의 냉각 조건일 수도 있어서다 —
     * 실측 재현된 버그: "라즈베리파이 5를 <b>방열판만 상태에서</b> 목표 FPS 고정으로
     * 50분 돌리면 언제 스로틀링이 걸리는지 시뮬레이션해줘"가 배치 비교로 새서,
     * 사용자가 물은 TTT 대신 후보 7종 순위표가 돌아왔다. 같은 문장에서 "방열판만"을
     * "팬 냉각"으로 바꾸면 정상 동작했으므로 원인이 이 단어임이 확인됐다.
     *
     * <p>또 이 오라우팅은 보드 비교까지 망가뜨렸다. "라즈베리파이 4와 5를 방열판
     * 상태에서 비교해줘"가 배치 도구로 가면, 그쪽은 보드가 하나로 확정돼야 하므로
     * 비교 요청이 "어느 보드인지 알려주세요"라는 되물음으로 바뀐다.
     *
     * <p>그래서 배치·형상을 실제로 가리키는 어휘가 있을 때만 이 도구를 고른다.
     * 붙이다·장착·부착도 단독으로는 조건 표현("방열판만 붙이면")이라 "어디에"가
     * 함께 있을 때만 배치로 본다.
     */
    private static final Pattern HEATSINK = Pattern.compile(
            "(배치|형상|모양|오프셋|어긋|치우|정렬|위치"
            + "|핀\\s*(방향|개수|높이|간격|수)"
            + "|서멀\\s*(패드|구리스|그리스)"
            + "|어디\\s*에?\\s*(붙|달|장착|부착)"
            + "|구리|알루미늄|재질)",
            Pattern.CASE_INSENSITIVE);

    /** @return 호출할 MCP 도구 이름. 엣지 도메인이면 항상 셋 중 하나를 반환한다(기본은 발열 시뮬레이션). */
    public static String select(String text) {
        if (text == null) return TOOL_THROTTLING;
        if (CALIBRATE.matcher(text).find()) return TOOL_CALIBRATE;
        if (HEATSINK.matcher(text).find()) return TOOL_HEATSINK;
        return TOOL_THROTTLING;
    }
}
