package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * 시각 표현이 정확히 1개인 메시지가 "실행 요청"인지 결정론적으로 판정한다.
 *
 * <p>베이스라인 제약 C2("실행 여부 결정은 결정론적이고 LLM-free여야 한다")를
 * 끝까지 지키기 위해, 예전엔 LLM에 맡겼던 이 판단(순간값 조회 여부·명시적
 * 실행 거부 신호)도 정규식으로 대체한다 — LLM 의도분류는 로컬 모델(예:
 * qwen2.5:7b, gemma2:9b)이 온도 0에서도 완전히 결정론적이지 않아, "교통
 * 정체 반영해서 방문 순서까지 지정한" 것처럼 조건절이 여러 개 겹친 문장을
 * 실측으로 반복 재현되는 빈도로 오분류하는 문제가 있었다(실행 요청인데도
 * no로 판정해 결과가 아예 안 나오는 사용자 체감 버그).
 *
 * <p>기준은 원래 LLM 프롬프트가 쓰던 것과 동일하다 — 아래 두 신호 중 하나도
 * 해당하지 않으면 기본값은 무조건 실행 요청(yes)이다:
 * <ul>
 *   <li>순간값 조회: "12시 시점 배출량 알려줘"처럼 그 시각의 즉시값 자체를
 *       묻는 경우 — 수거 시각 설정이 아니라 특정 순간의 조회이므로 no.</li>
 *   <li>명시적 실행 거부: "실행하지 말고", "돌리지 말고", "상상해서",
 *       "가상의", "감으로"처럼 이번 메시지 자체가 실행을 원치 않는다는
 *       신호가 있으면 no.</li>
 * </ul>
 *
 * <p>경로 소요시간 질의("Node_A, Node_B 순서로 방문하면 얼마나 걸려?")는 이
 * 클래스가 아니라 {@link RouteDurationQueryDetector}가 별도로 담당한다 —
 * {@code ChatController}가 이 클래스보다 먼저 그 게이트를 거치므로, 여기서는
 * 신경 쓸 필요가 없다.
 */
public final class ExecutionIntentDetector {

    private ExecutionIntentDetector() {}

    private static final Pattern INSTANT_QUERY = Pattern.compile("시점|순간값|그\\s*순간");

    private static final Pattern SKIP_EXECUTION = Pattern.compile(
            "실행하지\\s*말|돌리지\\s*말|실행\\s*안\\s*하고|상상해서|가상의|감으로|정확한\\s*계산\\s*필요\\s*없");

    /** true면 실행 요청, false면 실행 요청이 아님. */
    public static boolean isExecutionRequest(String text) {
        if (text == null) return false;
        if (INSTANT_QUERY.matcher(text).find()) return false;
        if (SKIP_EXECUTION.matcher(text).find()) return false;
        return true;
    }
}
