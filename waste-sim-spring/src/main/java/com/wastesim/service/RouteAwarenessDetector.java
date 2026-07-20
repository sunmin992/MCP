package com.wastesim.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 메시지 안의 "트럭 종류 언급 여부"와 "방문 순서(routeSequence)"를
 * 결정론적으로 판정·추출한다.
 *
 * <p>{@link TrafficKeywordDetector}·{@link ExecutionIntentDetector}와 같은
 * 이유(C2: 판단 가능한 사실은 정규식으로 확정하고 LLM 판단에만 의존하지
 * 않는다)로 도입했다. {@code EXTRACTION_SYSTEM_PROMPT}에 "이번 메시지에서
 * 새로 언급된 것만 반영하라"는 지시를 넣어도, 로컬 모델이 대화 히스토리에
 * 낚여 이전 턴의 트럭 종류·경로를 그대로 이어받는 경우가 실측으로 반복
 * 확인됐다(예: "소형 트럭으로 8시반 수거해줘" 다음에 그냥 "소형 트럭으로
 * 8시반 수거해줘"만 다시 보내도 이전 턴의 trafficEnabled까지 같이
 * 새어나옴). 이번 메시지 자체에 명시적 신호가 없으면 무조건 기본값으로
 * 강제 리셋해 이 부류의 실패를 구조적으로 막는다.
 */
public final class RouteAwarenessDetector {

    private RouteAwarenessDetector() {}

    private static final Pattern TRUCK_KEYWORDS = Pattern.compile("소형|중형|대형|트럭|차량|톤");

    /** 이번 메시지에 트럭 종류 관련 언급이 있는가. 없으면 호출측이 기본값(LARGE_5TON)으로 되돌려야 한다. */
    public static boolean truckTypeMentioned(String text) {
        return text != null && TRUCK_KEYWORDS.matcher(text).find();
    }

    private static final Pattern NODE_TOKEN = Pattern.compile("(?i)Node_[A-Za-z]");

    /**
     * 메시지 안에 등장하는 순서 그대로 "Node_X" 토큰을 뽑는다. LLM이 만든
     * routeSequence는 신뢰하지 않고(환각·이력 이어받기 둘 다 실측으로 확인됨)
     * 이 결과로 완전히 대체한다. 하나도 없으면 null(라우트 미지정).
     */
    public static List<String> extractRouteSequence(String text) {
        if (text == null) return null;
        Matcher m = NODE_TOKEN.matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String token = m.group();
            out.add("Node_" + Character.toUpperCase(token.charAt(token.length() - 1)));
        }
        return out.isEmpty() ? null : out;
    }
}
