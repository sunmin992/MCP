package com.wastesim.service;

import java.util.HashSet;
import java.util.Set;
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
    //
    // 숫자(아라비아) 표기 외에 "아홉시", "열두시"처럼 순우리말 수사로 쓴
    // 시각도 인식해야 한다 — 실측(라이브 브라우저 테스트)으로 "아홉시에
    // 수거하는 걸로 실행해줘"가 숫자 전용 정규식에 안 걸려 count=0으로
    // 처리되고, 실행 요청인데도 일반 답변(answerPlain) 경로로 빠져 LLM이
    // 실행 없이 가짜 시뮬레이션 결과를 지어내는 것을 확인했다(0단계 게이트가
    // 시각을 못 보면 이후 단계를 아예 안 밟으므로 근본 원인은 여기 있다).
    // 11·12시는 "열한/열두"가 "열"의 접두라 알아야 알아서 먼저 와야 한다.
    private static final Pattern TIME_EXPR = Pattern.compile(
            "(?:오전|오후|아침|점심|저녁|밤|새벽|낮)?\\s*" +
            "(?:[01]?\\d|2[0-3]|열한|열두|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*" +
            "(?:시\\s*반|시\\s*[0-5]?\\d\\s*분|시(?!간|드)|:[0-5]\\d)");

    /**
     * 텍스트 안의 서로 다른 시각 표현 개수(0, 1, 2 이상)를 센다.
     *
     * <p>DESIGN_DECISIONS.md D-01: 같은 시각이 문자 그대로 중복 언급되면
     * ("12시 12시에 수거해줘") 비교 요청이 아니라 강조로 보고 1개로 센다 —
     * 서로 다른 시각이 여러 개(13시·3시 비교 등)일 때만 "실행 아님(비교
     * 요청)"으로 판단해야 하므로, 표기가 같은 매칭은 중복 제거한다.
     */
    public static int count(String text) {
        if (text == null) return 0;
        Matcher m = TIME_EXPR.matcher(text);
        Set<String> distinct = new HashSet<>();
        while (m.find()) distinct.add(normalize(m.group()));
        return distinct.size();
    }

    private static String normalize(String raw) {
        return raw.replaceAll("\\s+", "");
    }
}
