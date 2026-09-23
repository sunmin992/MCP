package com.wastesim.ledger;

/**
 * 답변이 어떤 경로로 들어왔는가.
 *
 * <p>{@link BasisKind}와 축이 다르다 — 이쪽은 <b>경로</b>이고 저쪽은 <b>근거</b>다.
 * 사용자가 기본값을 승인한 값은 경로가 USER, 근거가 MODEL_DEFAULT다.
 */
public enum AnswerSourceKind {

    /** 사용자 발화나 답변에서 직접 얻었다. */
    USER,

    /** 모델 기본값을 제안해 적용했다. */
    MODEL_DEFAULT,

    /** 다른 확정값에서 계산했다. 예: 실제 운행 대수 = min(건물 수, 차량 수). */
    DERIVED,

    /** 외부 자료에서 읽었다. 예: 교통 프로파일 파일. */
    EXTERNAL,

    /** 사용자가 직접 입력한 값. */
    USER_DIRECT,

    /** MCP 결과로 들어온 값. */
    MCP_RESULT,

    /** LLM이 정규화한 값. */
    LLM_NORMALIZED,

    /** 서버 기본값. */
    SERVER_DEFAULT
}
