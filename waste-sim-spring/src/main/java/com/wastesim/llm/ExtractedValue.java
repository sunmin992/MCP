package com.wastesim.llm;

/**
 * LLM이 요청에서 뽑아낸 값 하나.
 *
 * @param field 설계도의 답변 필드 이름
 * @param value 뽑은 값
 * @param span  <b>근거가 된 원문 조각.</b> 요청 문장에서 그대로 가져온 것이어야 한다 —
 *              {@link SpanVerifier}가 실제로 있는지 검사하고 없으면 값을 버린다
 */
public record ExtractedValue(String field, Object value, String span) {}
