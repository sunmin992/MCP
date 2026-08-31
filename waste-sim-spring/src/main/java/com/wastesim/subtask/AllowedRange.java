package com.wastesim.subtask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 한 서브태스크가 받아들이는 값의 범위(SDD 2.18.2 — {@code allowedRange}).
 *
 * <p>자료형마다 의미 있는 축이 달라서 한 record에 모아 두고 해당 없는 축은 {@code null}로
 * 둔다 — 숫자는 {@code min}/{@code max}, 문자열은 {@code minLength}/{@code maxLength},
 * 배열은 {@code minItems}/{@code maxItems}, 선택형은 {@code values}다. 자료형별로 클래스를
 * 쪼개면 리소스 JSON도 자료형별로 갈라져야 하고, 카탈로그가 "이 서브태스크는 어느 하위형인가"를
 * 먼저 판정해야 한다 — 고정 세트 한 벌을 읽는 데 필요 없는 분기다.
 *
 * <p>{@link #description}은 <b>필수</b>다. UT-298이 "허용 범위가 비면 실패"를 요구하는데,
 * 축이 전부 null인 range도 형식상으로는 객체이므로 그것만으로는 "범위를 선언했다"고 볼 수
 * 없다. 사람이 읽을 수 있는 범위 설명을 반드시 적게 해서, 범위 없는 질문이 세트에 들어오는
 * 것을 리소스 단계에서 막는다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AllowedRange(
        String description,
        Double min,
        Double max,
        Integer minLength,
        Integer maxLength,
        Integer minItems,
        Integer maxItems,
        /** 맵형 답변의 값 합계가 정확히 이 값이어야 한다(비율 항목). null이면 검사하지 않는다. */
        Double sumTo,
        List<String> values) {

    public AllowedRange {
        values = values == null ? null : List.copyOf(values);
    }

    /** 범위 선언이 실제로 채워져 있는가 — UT-298의 "하나라도 비면 실패" 판정에 쓴다. */
    public boolean isDeclared() {
        return description != null && !description.isBlank();
    }

    /** {@code values}가 있으면 그 목록, 없으면 빈 목록(호출측 null 검사 제거). */
    public List<String> valuesOrEmpty() {
        return values == null ? List.of() : values;
    }
}
