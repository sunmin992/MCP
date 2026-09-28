package com.wastesim.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 서브태스크 <b>템플릿</b> — 질문이 아니라 규칙이다.
 *
 * <p>서버가 소유하는 것은 이 규칙이고, 인스턴스는 LLM이 요청마다 만든다. 그래서
 * 여기에는 "몇 번째 질문인가" 같은 순서가 없다 — 순서는 요청이 정한다.
 *
 * @param generateWhen  이 결정을 언제 만드는가. 서브태스크의 <b>존재</b>를 정한다
 * @param allowed       열거형의 닫힌 선택지. 수치형이면 비어 있고 min/max 를 쓴다
 * @param defaultValue  제안할 기본값. {@code null}이면 기본값 제안 없이 반드시 물어야 한다
 * @param configField   {@code SimulationConfig}의 필드명. 평탄화와 역검증이 이것으로 게터를 찾는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubtaskTemplate(
        String templateId,
        String answerKey,
        String valueType,
        GenerateCondition generateWhen,
        List<String> allowed,
        Double min,
        Double max,
        String unit,
        Object defaultValue,
        String defaultBasis,
        String question,
        String configField,
        String sesPath,
        String nodeKind) {

    public SubtaskTemplate {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    /** 기본값 제안 없이 반드시 물어야 하는 결정인가. */
    public boolean requiresExplicitAnswer() {
        return defaultValue == null;
    }
}
