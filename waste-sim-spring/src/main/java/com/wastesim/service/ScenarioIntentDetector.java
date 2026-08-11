package com.wastesim.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 메시지가 사이드바 "시나리오 실험" 버튼 12종({@code SimulationTool#runScenario}가
 * 받는 type 문자열) 중 어느 것을 가리키는지 결정론적으로 판정한다.
 *
 * <p>지금까지 채팅은 단일 실행(run_waste_simulation)만 자연어로 라우팅하고,
 * 시나리오 실험은 버튼 클릭 전용이었다 — 이 클래스가 그 간극을 메운다.
 * {@link ExecutionIntentDetector}·{@link TrafficKeywordDetector}와 같은 이유
 * (C2: 판단 가능한 사실은 정규식으로 확정하고 LLM 판단에 의존하지 않는다)로
 * LLM을 쓰지 않는다 — 어느 시나리오인지는 사이드바 버튼 문구와 거의 같은
 * 키워드 조합으로 이미 충분히 구분되고, 잘못 판정되면 엉뚱한 실험이 자동
 * 실행되므로 더더욱 결정론이 필요하다.
 *
 * <p>각 유형은 "이 키워드들이 전부 있어야 매칭"(AND) 조건으로 정의한다 —
 * 단일 키워드만으로는 오탐(예: "밀도"만으로 매칭되면 무관한 질문도 걸림)
 * 위험이 있어, 최소 두 개 이상의 조합으로 좁힌다. 먼저 매칭되는 유형을
 * 반환하므로 순서가 특이성(specific) 높은 것부터 오도록 배치했다.
 */
public final class ScenarioIntentDetector {

    private ScenarioIntentDetector() {}

    // LinkedHashMap: 삽입 순서 = 검사 순서(먼저 매칭되는 것을 채택)
    private static final Map<String, Pattern[]> RULES = new LinkedHashMap<>();
    static {
        // 차종×순서 탐색은 multi-truck(트럭 대수)보다 먼저 본다 — "트럭"이 두 규칙에
        // 함께 걸리는데, 차종·순서를 말한 쪽이 더 구체적인 요청이다.
        RULES.put("truck-route", pats(
                "(차종|트럭\\s*(종류|크기)|[512]\\s*톤|방문\\s*순서|수거\\s*순서|경로\\s*순서)",
                "(탐색|최적|가장\\s*(적|나은|좋)|찾아|조합|비교)"));
        RULES.put("monthly-waste", pats("월별", "배출"));
        RULES.put("waste-separation", pats("분리배출"));
        RULES.put("new-occupations", pats("(야간\\s*근무|1인\\s*직장인|확장\\s*거주민)"));
        RULES.put("coupling-variants", pats("(결합\\s*모델|임대인|귀가.*모델)"));
        RULES.put("collection-schedule", pats("(스케줄|격일|주말\\s*수거|다회\\s*수거)"));
        RULES.put("multi-truck", pats("(다중\\s*트럭|트럭.*(여러|분할)|구역\\s*분할)"));
        RULES.put("density", pats("밀도", "(빌라촌|원룸촌|vs)"));
        RULES.put("infra-grid", pats("인프라", "(트레이드오프|용량|임계)"));
        RULES.put("behavior-grid", pats("(행동\\s*변동|외출.*분산)", "민감도"));
        RULES.put("occupation-mix", pats("(구성별|거주민\\s*구성)", "(최적|비교)"));
        RULES.put("collection-sweep", pats("(sweep|스윕|(수거\\s*시각|수거시각).*(전체|하루|종일)|06.{0,3}18)"));
    }

    private static Pattern[] pats(String... regexes) {
        Pattern[] out = new Pattern[regexes.length];
        for (int i = 0; i < regexes.length; i++) out[i] = Pattern.compile(regexes[i]);
        return out;
    }

    /** @return 매칭되는 시나리오 type 문자열, 없으면 {@code null} */
    public static String detect(String text) {
        if (text == null) return null;
        for (Map.Entry<String, Pattern[]> rule : RULES.entrySet()) {
            boolean allMatch = true;
            for (Pattern p : rule.getValue()) {
                if (!p.matcher(text).find()) { allMatch = false; break; }
            }
            if (allMatch) return rule.getKey();
        }
        return null;
    }
}
