package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * 메시지에 "교통/정체" 관련 언급이 있는지 결정론적으로 판정한다.
 *
 * <p>LLM 추출({@link OpenAiService#extractParamsStrict})이 {@code trafficEnabled}를
 * 놓치거나(temperature>0이라 완전히 결정론적이지 않음) 지어내는 경우의 안전망 —
 * {@link TimeExpressionDetector}와 같은 철학(C2: 판단 가능한 사실은 정규식으로
 * 확정하고 LLM 판단에만 의존하지 않는다)으로, 사용자가 명시적으로 교통을
 * 언급했는데도 트래픽 레이어가 조용히 꺼진 채 실행되는 걸 막는다.
 */
public final class TrafficKeywordDetector {

    private TrafficKeywordDetector() {}

    private static final Pattern KEYWORDS = Pattern.compile(
            "교통|정체|혼잡|막히|막힘|트래픽|체증|골목|도로");

    public static boolean mentioned(String text) {
        return text != null && KEYWORDS.matcher(text).find();
    }
}
