package com.wastesim.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * "Node_A, Node_C, Node_B, Node_D 순서로 방문하면 얼마나 걸려?"류의 "경로
 * 소요시간 질의"를 결정론적으로 판정한다.
 *
 * <p>이 질의는 {@link ExecutionIntentDetector}가 담당하는 "전체 시뮬레이션
 * 실행"(민원 집계) 게이트와는 다르다 — 방문 순서를 반영한 이동시간 근사값만
 * 답하면 되므로, 수거 시각이 메시지에 없어도(그러면 혼잡 가중치만 미반영)
 * 답할 수 있다. C2 원칙(판단 가능한 사실은 정규식으로 확정)을 그대로
 * 따르는 별도의 결정론적 분기다.
 */
public final class RouteDurationQueryDetector {

    private RouteDurationQueryDetector() {}

    // 실측 회귀: "14시에 수거하면 걸리는 시간 알려줘"처럼 "걸리는 시간"으로
    // 묻는 표현이 기존 패턴("소요 시간"/"얼마나 걸" 등)에 없어서 안 걸렸다 —
    // 시각이 하나 언급됐다는 이유만으로 이 게이트를 못 타고 아래의 전체
    // 시뮬레이션(민원 집계) 경로로 잘못 흘러갔다.
    private static final Pattern DURATION_PHRASE = Pattern.compile(
            "얼마나\\s*걸|소요\\s*시간|걸리는\\s*시간|이동\\s*시간이?\\s*얼마|걸리나요|걸릴까요|몇\\s*분\\s*걸");

    /**
     * true면 경로 소요시간 질의. 방문 순서(Node_X 2개 이상)와 소요시간을 묻는
     * 표현이 메시지에 함께 있어야 한다 — 둘 중 하나만 있으면 오탐(예: 순서
     * 언급 없이 "얼마나 걸려?"만 묻는 일반 대화, 소요시간 언급 없이 경로만
     * 지정한 실행 요청)이 되므로 둘 다 요구한다.
     */
    public static boolean isRouteDurationQuery(String text, List<String> routeSequence) {
        if (text == null) return false;
        if (routeSequence == null || routeSequence.size() < 2) return false;
        return DURATION_PHRASE.matcher(text).find();
    }
}
