package com.wastesim.subtask;

import com.wastesim.tool.ErrorCode;

/**
 * 서브태스크 답변 하나의 거부 사유(FR-126·127).
 *
 * <p>{@code retryQuestion}을 오류 항목에 함께 싣는 것이 이 record의 요점이다. 오류
 * 코드와 사유만 돌려주면 재질문 문장을 <b>호출부가</b> 만들게 되고, 호출부가 여럿이면
 * (채팅·MCP 도구·REST) 같은 항목에 대한 재질문이 경로마다 달라진다 — FR-127이 막으려는
 * 상황 그대로다. 카탈로그의 문장을 오류에 붙여 내보내면 어느 경로로 나가든 같은 문장이 된다.
 *
 * <p>{@link ErrorCode}를 새로 만들지 않고 기존 것을 쓴다. 이 계층의 오류도 결국
 * "필수 누락·자료형 위반·범위 초과"라서, 코드를 따로 두면 같은 뜻의 코드가 두 벌이 된다.
 *
 * @param subtaskId     거부된 답변의 서브태스크 ID
 * @param code          기존 구조화 오류 코드
 * @param reason        무엇이 잘못됐는지(사람이 읽는 설명)
 * @param retryQuestion 이 항목을 다시 물을 때 쓸 카탈로그의 문장 — 서버가 새로 짓지 않는다
 */
public record SubtaskError(String subtaskId, ErrorCode code, String reason, String retryQuestion) {
}
