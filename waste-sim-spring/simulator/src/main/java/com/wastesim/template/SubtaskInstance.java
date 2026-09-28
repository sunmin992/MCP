package com.wastesim.template;

/**
 * 요청 하나에 대해 LLM이 만든 서브태스크 하나.
 *
 * @param origin 값의 출처. {@code USER} / {@code MODEL_DEFAULT} / {@code DERIVED} /
 *               {@code null}(값 없음). 미승인 기본값으로 실행되면 임의 가정으로 집계한다
 */
public record SubtaskInstance(
        String templateId,
        String answerKey,
        SubtaskStatus status,
        Object value,
        String origin,
        String question) {
}
