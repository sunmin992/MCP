package com.wastesim.evidence;

import java.util.List;
import java.util.Map;

/**
 * 근거 검사가 한 결정에 대해 알아야 하는 것. 시뮬레이터마다 템플릿 클래스가 달라도 이 형태로 넘기면 같은 규칙으로 검사한다.
 *
 * @param key           답변키
 * @param valueType     INTEGER · NUMBER · INTEGER_LIST · ENUM · ENUM_LIST · STRING …
 * @param unit          값의 단위(minute · day · ratio · kg · won …). 단위 변환과 단위말 대조에 쓴다. 없으면 숫자를 그대로 비교한다
 * @param conditionalDefault 조건부 결정인데 기본값이 있는가 — 이런 기본값은 사용자 동의 구절이 있어야 한다
 * @param valueTerms    선택지 값 → 사용자가 그 값을 말할 때 쓰는 표현. 예: LARGE_5TON → ["5톤", "대형"]
 * @param unitWords     수를 셀 때 붙는 말. 예: 건물 수 → ["동", "채"]. 구절의 숫자 뒤 말이 다른 필드의 단위면 막는다
 */
public record EvidenceField(String key, String valueType, String unit, boolean conditionalDefault,
                            Map<String, List<String>> valueTerms, List<String> unitWords) {
    public EvidenceField {
        valueTerms = valueTerms == null ? Map.of() : Map.copyOf(valueTerms);
        unitWords = unitWords == null ? List.of() : List.copyOf(unitWords);
    }

    public boolean numeric() {
        return List.of("INTEGER", "NUMBER", "INTEGER_LIST").contains(valueType);
    }

    public boolean choice() {
        return List.of("ENUM", "STRING").contains(valueType);
    }
}
