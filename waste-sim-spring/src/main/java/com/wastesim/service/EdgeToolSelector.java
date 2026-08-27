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
    public static final String TOOL_SWEEP = "sweep_fan_rpm";
    public static final String TOOL_PTM = "simulate_ptm_control";
    public static final String TOOL_LAYOUT = "rank_fan_layouts";

    /**
     * <b>예측 냉각(PTM)</b> 요청 — 팬을 "언제" 돌릴지(제어 방식)를 묻는 질문이다.
     *
     * <p>스윕보다 먼저 검사한다. 두 도구의 어휘는 거의 겹치지만(팬·전력·최적) 답의 형태가
     * 다르다 — 스윕은 <b>고정 회전수 하나</b>를 고르고, PTM은 <b>시간에 따라 바꾸는 방식</b>을
     * 비교한다. "팬을 미리 돌리면 이득이야?"가 스윕으로 가면 고정 운전점 곡선이 돌아와,
     * 정작 물어본 '미리'가 답에서 빠진다.
     *
     * <p>제어를 가리키는 말이 실제로 있을 때만 고른다 — "예측"·"미리"·"선제"·"제어"·"PTM".
     * "팬 속도"·"가성비"처럼 스윕과 공유하는 어휘만으로는 오지 않는다.
     */
    private static final Pattern PTM = Pattern.compile(
            "(ptm|예측\\s*(냉각|제어|쿨링)|프리딕티브|predictive"
            + "|(미리|선제적?|앞서)\\s*.{0,6}(돌|켜|올려|냉각|식)"
            + "|팬\\s*.{0,4}(제어|컨트롤)"
            + "|제어\\s*(방식|방법|전략)"
            + "|(항상|계속)\\s*.{0,4}(돌|켜).{0,10}(필요|이유)"
            + ")",
            Pattern.CASE_INSENSITIVE);

    /**
     * <b>팬 배치</b> 요청 — 팬을 "어디에 어떤 방향으로" 달지 묻는 질문이다.
     *
     * <p>팬 어휘와 배치 어휘가 <b>둘 다</b> 있을 때만 고른다. 한쪽만으로 고르면 기존
     * 두 도구를 망가뜨린다.
     *
     * <p>배치 어휘만 보면 {@link #HEATSINK}의 영역을 뺏는다 — "방열판을 어디에 붙일까"가
     * 팬 배치 순위표로 새면, 이 파일에 이미 기록된 오라우팅(냉각 조건을 비교 대상으로
     * 착각하는 유형)과 같은 결함이 하나 더 생긴다.
     *
     * <p>팬 어휘만 보면 {@link #SWEEP}의 영역을 뺏는다 — "최적 팬 rpm"이 배치 랭킹으로
     * 새면 회전수 곡선이 사라진다.
     *
     * <p>반대로 <b>스윕보다는 먼저</b> 봐야 한다. SWEEP의 {@code 최적\s*(의\s*)?(…|팬|…)}가
     * "최적 팬 배치"를 먼저 잡아 버리기 때문이다. 그 문장에서 사용자가 물은 것은
     * 회전수가 아니라 배치다.
     *
     * <p>배치 어휘에 <b>맨 위치</b>·<b>맨 방향</b>을 단독으로 넣지 않는다(실측 재현) —
     * "팬 흡기 위치 데이터를 CSV로 기록했는데 모델 보정할 수 있어?"는 위치가 캘리브레이션
     * 데이터의 일부일 뿐인데 배치 랭킹으로 샜다. "최적 팬 위치가 아니라 그냥 최고
     * rpm이 궁금해"도 같은 이유로 스윕을 빼앗겼다. 둘 다 이 파일이 이미 기록한
     * "조건을 비교 대상으로 착각"하는 유형과 같은 결함이다. 그래서 위치·방향은
     * 비교/배치를 실제로 요청하는 동사·명사와 붙어 있을 때만 본다.
     *
     * <p>주의: 이 좁히기만으로는 "흡기 위치에 먼지가 쌓였는데 스로틀링 얼마나
     * 심해질까?" 같은 문장을 발열 시뮬레이션으로 되돌리지 못한다 — FAN_LAYOUT을
     * 피해가도 {@link #HEATSINK}가 맨 "위치"를 독자적으로 갖고 있어 그쪽으로 간다.
     * 그 문제는 HEATSINK 쪽 어휘를 좁혀야 풀리는 별개의 사안이다.
     */
    private static final Pattern FAN_LAYOUT = Pattern.compile(
            "(?=.*(팬|fan|쿨러|흡기|배기|흡배기))"
            + "(?=.*(배치|조합"
            + "|어디\\s*에?\\s*(달|붙|장착|부착)"
            + "|어느\\s*(위치|자리|쪽)"
            + "|어떤\\s*(방향|위치|자리)"
            + "|(위치|방향)\\s*(조합|비교|추천|고르|정하)"
            + "))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 실측 데이터로 모델을 보정하려는 요청. */
    private static final Pattern CALIBRATE = Pattern.compile(
            "(캘리브|calibrat|보정|실측\\s*(데이터|값|로그|결과)|측정\\s*(데이터|로그|결과|값)"
            + "|프로파일|profile|csv|역추정|시정수\\s*(구|추정)|피팅|fitting)",
            Pattern.CASE_INSENSITIVE);

    /**
     * <b>최적 팬 속도</b>를 찾으려는 요청 — 한 지점을 돌리는 것이 아니라 여러 회전수를
     * 훑어 비교해야 답이 나오는 질문이다.
     *
     * <p>발열 시뮬레이션보다 먼저 검사한다. "팬 몇 rpm이 가성비가 제일 좋아?"는 어휘상
     * 발열 시뮬레이션과 완전히 겹치는데(팬·rpm·온도), 한 지점만 돌리면 사용자가 물어본
     * <b>비교</b>가 통째로 빠진 답이 된다.
     *
     * <p><b>캘리브레이션보다도 먼저 검사한다</b>(FR-78의 검사 순서: 스윕 → 캘리브레이션 →
     * 방열판 배치 → 발열). "실측 프로파일 기준으로 최적 팬 rpm 찾아줘"처럼 두 어휘가
     * 함께 나오는 문장에서 캘리브레이션이 이기면 답이 아예 나오지 않는다 — 캘리브레이션은
     * 시계열을 채팅으로 실어 나를 수 없어 채팅에서 실행하지 않고 보내는 방법만 안내하기
     * 때문이다(FR-83). 스윕은 그 문장에서 실제로 실행되어 사용자가 물은 운전점을 낸다.
     *
     * <p>"최적"·"적정"은 단독으로 넣지 않고 회전수·팬·전력을 가리키는 말과 붙어 있을
     * 때만 본다 — "최적 배치"는 방열판 도구의 질문이고, 여기로 새면 배치 비교가 스윕으로
     * 바뀐다.
     */
    private static final Pattern SWEEP = Pattern.compile(
            "(스윕|sweep"
            + "|최적\\s*(의\\s*)?(rpm|알피엠|회전수|팬|속도|pwm|운전점|지점|냉각\\s*(세기|수준))"
            + "|적정\\s*(rpm|알피엠|회전수|속도|수준|지점)"
            + "|(rpm|알피엠|회전수|pwm|팬\\s*속도)[을를이가은는\\s]*(최적|스윕|얼마|몇)"
            + "|몇\\s*(rpm|알피엠|%|퍼센트)"
            + "|가성비"
            + "|(전력|에너지)[을를이가은는\\s]*(최소|가장\\s*(적|작))"
            + "|(전력|에너지)\\s*(소비\\s*)?(최소|최적)"
            + ")",
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
            + "|어디\\s*에?\\s*(붙|달|장착|부착))",
            Pattern.CASE_INSENSITIVE);

    /**
     * 재질 어휘 — 배치 비교를 가리키기도 하지만 <b>특정 방열판을 지정하는 말</b>일 수도 있다.
     *
     * <p>{@link #HEATSINK}에서 떼어낸 이유(실측 회귀): "pi5에 <b>90g 알루미늄</b> 방열판 달고
     * 버스트 부하 돌려줘"가 배치 비교로 새서, 사용자가 물은 발열 시뮬레이션 대신 후보 7종
     * 순위표가 돌아왔다. 재질을 말했다는 것만으로 "형상을 비교해 달라"고 볼 수 없다 —
     * 냉각 조건으로 쓴 "방열판"이 배치 비교로 새던 것과 같은 유형의 오라우팅이다.
     *
     * <p>구분 기준은 <b>질량이 함께 나오는가</b>다. 질량이 있으면 손에 든 특정 방열판을
     * 지정한 것이므로 발열 시뮬레이션(2노드)이고, 질량 없이 재질만 말하면
     * ("구리랑 알루미늄 차이가 커?") 형상·재질 비교 요청이다.
     */
    private static final Pattern HEATSINK_MATERIAL = Pattern.compile(
            "(구리|알루미늄|copper|aluminum|재질)", Pattern.CASE_INSENSITIVE);

    /** 방열판 질량 표기 — "90g", "90 그램", "0.12kg". 4GB·40mm 같은 무관한 표기는 걸리지 않는다. */
    static final Pattern MASS = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:kg|g(?![a-z])|그램|그람)", Pattern.CASE_INSENSITIVE);

    /** @return 호출할 MCP 도구 이름. 엣지 도메인이면 항상 여섯 중 하나를 반환한다(기본은 발열 시뮬레이션). */
    public static String select(String text) {
        if (text == null) return TOOL_THROTTLING;
        // 검사 순서: PTM(제어 방식) → 팬 배치 → 스윕(고정 운전점) → 캘리브레이션 → 방열판 배치 → 발열.
        // 어휘가 겹치는 구간이 많아, 항상 더 구체적인 쪽을 먼저 본다. 팬 배치를 스윕보다
        // 먼저 보는 이유는 FAN_LAYOUT 주석에 있다.
        if (PTM.matcher(text).find()) return TOOL_PTM;
        if (FAN_LAYOUT.matcher(text).find()) return TOOL_LAYOUT;
        if (SWEEP.matcher(text).find()) return TOOL_SWEEP;
        if (CALIBRATE.matcher(text).find()) return TOOL_CALIBRATE;
        if (HEATSINK.matcher(text).find()) return TOOL_HEATSINK;
        // 재질만으로는 배치 비교로 보내지 않는다 — 질량이 함께 있으면 특정 방열판을
        // 지정한 발열 시뮬레이션 요청이다(HEATSINK_MATERIAL 주석 참고).
        if (HEATSINK_MATERIAL.matcher(text).find() && !MASS.matcher(text).find()) return TOOL_HEATSINK;
        return TOOL_THROTTLING;
    }
}
