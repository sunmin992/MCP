package com.wastesim.broker;

/**
 * 요청을 어떻게 고치면 그 서버를 쓸 수 있는가 — 어긋난 자리 하나에 하나.
 *
 * <p>제안은 카드에 적힌 것(분석 단위 · 미지원 사유와 대안 · 답할 수 있는 질문)에서만 만든다.
 * 브로커가 지어내면 LLM 이 없는 능력을 사용자에게 약속한다.
 *
 * @param axis           요청의 어느 칸인가 — domain · spatialScale · environmentConditions · objective
 * @param current        요청이 지금 적은 값
 * @param suggestion     이렇게 바꾸면 된다
 * @param reason         왜 지금은 안 되는가. 카드가 적은 사유를 그대로 옮긴다
 * @param changesPurpose 이 조정이 요청을 고치는 게 아니라 다른 질문을 하게 만드는가. 도메인이
 *                       다르면 참이다 — 숨기면 사용자는 같은 질문의 답을 받는 줄 안다
 */
public record RequestAdjustment(
        String axis,
        String current,
        String suggestion,
        String reason,
        boolean changesPurpose) {
}
