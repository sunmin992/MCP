package com.wastesim.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 메시지에 "파싱 가능한 수거 시각"이 몇 개 있는지 결정론적으로 센다.
 *
 * <p>베이스라인 제약 C2("시뮬레이션 실행 여부 결정은 결정론적이고 LLM-free여야
 * 한다")를 지키기 위한 1차 게이트. {@link ChatController}는 이 카운트가
 * 정확히 1일 때만 LLM 의도 분류를 호출한다 — 0개(시각 없음)나 2개 이상
 * (순간값 조회 등)은 정규식만으로 이미 "실행 아님"이 확정되므로 LLM을 아예
 * 부르지 않는다. 이렇게 하면 "메시지에 시각이 하나도 없는데 LLM이 대화
 * 히스토리에서 시각을 끌어와 실행해버리는" 부류의 실패가 구조적으로
 * 불가능해진다(정규식이 0을 세면 LLM 호출 자체가 없으므로).
 */
public final class TimeExpressionDetector {

    private TimeExpressionDetector() {}

    // "시" 단독 매칭은 "시간"(간=지속시간 단위)·"시드"(드=seed 파라미터)처럼
    // 실제 시각이 아닌 복합어의 일부를 잘못 집어내는 걸 막기 위해 두 음절을
    // 제외한다. "시 반"/"시 30분" 형태는 그 자체로 이미 "시각"이 명확하므로
    // 별도 분기로 먼저 처리한다.
    private static final Pattern TIME_EXPR = Pattern.compile(
            "(?:오전|오후|아침|점심|저녁|밤|새벽|낮)?\\s*(?:[01]?\\d|2[0-3])\\s*" +
            "(?:시\\s*반|시\\s*[0-5]?\\d\\s*분|시(?!간|드)|:[0-5]\\d)");

    /** 텍스트 안의 시각 표현 개수(0, 1, 2 이상)를 센다. */
    public static int count(String text) {
        if (text == null) return 0;
        Matcher m = TIME_EXPR.matcher(text);
        int c = 0;
        while (m.find()) c++;
        return c;
    }
}
