package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * 실행 동사는 있는데 수거 시각이 없는 요청을 가려낸다 — 수집 경로로 보내기 위한 게이트.
 *
 * <p>"장량동 26개 동으로 한 달 돌려줘"는 갈 곳이 없었다. 즉시 실행 게이트는 시각이 정확히
 * 1개일 것을 요구하는데 {@link TimeExpressionDetector}가 0을 세고,
 * {@link SimulatorCreationDetector}는 "돌려"·"실행"을 <b>일부러</b> 제외한다(그것은 즉시 실행
 * 경로라는 판단이었다). 그래서 조건을 다 말한 문장이 일반 답변으로 떨어졌다.
 *
 * <p><b>왜 되묻고 즉시 실행하지 않는가.</b> 즉시 실행 경로의 추출 스키마
 * ({@code OpenAiService.EXTRACTION_SYSTEM_PROMPT})에는 {@code numBuildings}가 없고, 프롬프트가
 * 히스토리 이어받기를 명시적으로 금지한다. 그래서 시각만 받아 실행하면 "26개 동"이 조용히
 * 사라지고 기본값 4로 돈다 — 게다가 "한 달"→30은 기본값과 같아서 그 손실이 화면에 보이지도
 * 않는다. 수집 경로로 보내면 말한 조건은 살고, 말하지 않은 시각만 물어진다.
 *
 * <p><b>왜 {@code CREATE_VERB}에 "돌려"를 넣지 않는가.</b> 생성 판별은 즉시 실행보다
 * <b>먼저</b> 검사되므로, 거기에 실행 동사를 넣으면 "10시에 수거로 돌려줘"까지 문항 수집으로
 * 샌다. 이 판정기는 즉시 실행 게이트가 떨어진 <b>뒤</b>에 선다.
 */
public final class RunWithoutTimeDetector {

    private RunWithoutTimeDetector() {}

    /**
     * "지금 돌려라"에 해당하는 동사.
     *
     * <p>{@link ExecutionIntentDetector}를 긍정 신호로 쓸 수 없어서 따로 본다 — 그 판정기는
     * 기본값이 {@code true}라(순간값 조회·명시적 거부만 걸러낸다) "장량동 배출량 알려줘"에도
     * {@code true}를 낸다. 거부권이지 신호가 아니다.
     */
    private static final Pattern RUN_VERB = Pattern.compile("돌려|돌리|실행|구동");

    /**
     * 시나리오 규모·기간 조건. <b>수거 시각은 여기 없다</b> — 시각이 있으면 즉시 실행
     * 경로이므로, 그것을 조건으로 세면 이 판정기가 즉시 실행을 가로챈다.
     *
     * <p>이 조건을 요구하는 이유는 "이거 어떻게 실행해?" 같은 사용법 질문과 가르기 위해서다.
     * 조건이 하나도 없는 문장으로 수집을 시작하면 아무 질문에나 문항이 뜬다.
     */
    private static final Pattern SCENARIO_CONDITION = Pattern.compile(
            "\\d+\\s*(?:개\\s*동|동|일치|일|주일|주|개월|달|명|세대|가구)" +
            "|한\\s*달|일주일|이주일|한\\s*주|보름|한\\s*해");

    /** true면 "실행하려는 요청인데 시각이 없다" — 수집 경로로 보낸다. */
    public static boolean isRunWithoutTime(String text) {
        if (text == null || text.isBlank()) return false;
        if (!RUN_VERB.matcher(text).find()) return false;
        if (!SCENARIO_CONDITION.matcher(text).find()) return false;
        // "돌리지 말고"·"실행하지 말고"를 실행 준비로 읽으면 안 된다. 이미 있는 거부권을
        // 다시 쓴다 — 같은 판단이 두 곳에 살면 한쪽만 고쳐지는 날이 온다.
        return ExecutionIntentDetector.isExecutionRequest(text);
    }
}
