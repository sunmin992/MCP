package com.wastesim.tool;

/** 구조화 오류 코드 — REST·MCP·채팅이 공통으로 사용 (베이스라인 ErrorCode 복원). */
public enum ErrorCode {
    OK,
    BAD_REQUEST,        // 요청 본문 파싱 불가
    UNKNOWN_TOOL,       // 알 수 없는 MCP 도구
    INVALID_ARGUMENTS,  // 인자 형식 오류
    MISSING_FIELD,      // 필수 필드 누락
    OUT_OF_RANGE,       // 허용 범위 벗어남
    INVALID_ENUM,       // 허용되지 않은 값(직업·시나리오 유형 등)
    LLM_PARSE_ERROR,    // LLM 응답 파싱 실패
    EXECUTION_ERROR,    // 시뮬레이션 실행 오류
    INTERNAL_ERROR,     // 예기치 못한 서버 오류

    // ── 교통 레이어 교차 검증 (TRAFFIC_EXTENSION_DESIGN.md §5.1) ──────────
    TRUCK_COUNT_ZERO,             // 운행 대수 0 (수거 불가)
    CRITICAL_WASTE_ACCUMULATION,  // 예측 적재율이 한계 초과(수거 중단/부족)
    TRAFFIC_INFEASIBLE            // 교통 제약상 실행 불가(골목 대형트럭 등)
}
