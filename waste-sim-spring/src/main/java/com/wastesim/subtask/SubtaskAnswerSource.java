package com.wastesim.subtask;

/**
 * 답변의 구조화 값이 어디서 왔는가(SDD 2.18.2 — "정규화 출처").
 *
 * <p>사용자 입력, LLM 정규화, 서버 기본값, 계약 검사를 통과한 외부 도구 결과를
 * 구분한다. 외부 값의 호출 근거와 단위는 세션의 매개변수 결정기록에 보존한다.
 */
public enum SubtaskAnswerSource {
    /** 사용자가 형식에 맞는 값을 그대로 입력했다(프런트엔드 위젯 입력 포함). */
    USER_DIRECT,
    /** 자연어 답변을 LLM이 지정된 필드 하나로 정규화했다(FR-125). */
    LLM_NORMALIZED,
    /** 사용자가 답하지 않아 서버가 채웠다 — 반드시 assumptions에 남는다(D-53). */
    SERVER_DEFAULT,
    /** Registered external tool result, admitted against its predeclared contract. */
    MCP_RESULT
}
