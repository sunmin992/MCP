package com.wastesim.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 이 서버가 하지 않는 일 하나.
 *
 * <p>{@code matchKeys}가 이 레코드의 존재 이유다 — 브로커와 LLM이 사용자 발화를
 * 여기에 대조해 <b>요청에 담긴 불가능한 처리</b>를 4단계에서 찾아낸다. 매칭 키가 없으면
 * 대조할 것이 없어 지원하지 않는 요청이 실행 직전까지 살아남는다.
 *
 * @param reasonType NOT_IN_MODEL / NO_DATA / OUT_OF_SCOPE / OUT_OF_RANGE.
 *                   데이터를 구하면 열리는 것과 구현이 필요한 것을 사용자가 구분해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnsupportedItem(
        String code,
        String capability,
        List<String> matchKeys,
        String reasonType,
        String reason,
        List<String> alternatives,
        String message) {

    public UnsupportedItem {
        matchKeys = matchKeys == null ? List.of() : List.copyOf(matchKeys);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
}
