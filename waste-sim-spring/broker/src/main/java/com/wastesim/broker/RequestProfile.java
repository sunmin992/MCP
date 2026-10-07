package com.wastesim.broker;

import java.util.List;

/**
 * LLM이 발화에서 뽑은 요청 조건 — 명세 2단계가 내는 것.
 *
 * <p>서버는 자연어를 해석하지 않는다. 해석은 LLM이 하고 이 형식으로 넘긴다 — 서버가
 * 자연어를 읽기 시작하면 LLM과 서버 어느 쪽이 잘못 읽었는지 가릴 수 없다.
 *
 * <p><b>미제시는 {@code null}로 남는다.</b> 명세 규칙 1이 "2단계에서 추정하지 않는다"인데,
 * 모르는 것을 빈 문자열이나 빈 목록으로 뭉개면 그 규칙이 형식에서 사라진다. 빈 목록은
 * "그런 조건이 없다고 말한 것"이고 {@code null}은 "묻지 않아 모르는 것"이다 — 매칭이
 * 둘을 다르게 다뤄야 한다.
 *
 * @param domain                도메인. 유일한 필수 항목이다
 * @param spatialScale          공간 규모. 예: "한 동네"
 * @param environmentConditions 환경 조건. 예: ["평일 교통량"]
 * @param objective             목적. 예: "민원이 가장 적은 수거 시각"
 * @param comparisonAxes        무엇을 바꿔 가며 비교할 것인가. 예: ["수거 시각"]
 * @param population            거주민에 관한 구절. 예: ["학생이 많음", "주부가 많음"]. 해석하지 않고 카드의
 *                              거주민 유형 matchKeys 와 대조만 한다
 */
public record RequestProfile(
        String domain,
        String spatialScale,
        List<String> environmentConditions,
        String objective,
        List<String> comparisonAxes,
        List<String> population) {

    public RequestProfile {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException(
                    "도메인이 없습니다 — 도메인 없이는 후보를 거를 축이 하나도 없습니다");
        }
        domain = domain.trim();
        spatialScale = blankToNull(spatialScale);
        objective = blankToNull(objective);
        environmentConditions = copyOrNull(environmentConditions);
        comparisonAxes = copyOrNull(comparisonAxes);
        // 빈칸 구절은 버린다 — 남기면 어느 카드와도 맞지 않아 모든 서버가 못 하는 것으로 둔갑한다.
        population = population == null ? null
                : population.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }

    /** 거주민 조건이 없던 시절의 호출부를 그대로 두는 생성자. 거주민은 묻지 않은 것({@code null})이다. */
    public RequestProfile(String domain, String spatialScale, List<String> environmentConditions,
                          String objective, List<String> comparisonAxes) {
        this(domain, spatialScale, environmentConditions, objective, comparisonAxes, null);
    }

    /** 공백만 있는 값은 미제시다. 그대로 두면 매칭이 빈 문자열과 대조한다. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** {@code null}은 {@code null}로 남기고, 있으면 밖에서 못 바꾸게 복사한다. */
    private static List<String> copyOrNull(List<String> list) {
        return list == null ? null : List.copyOf(list);
    }
}
