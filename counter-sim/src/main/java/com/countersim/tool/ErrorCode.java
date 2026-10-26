package com.countersim.tool;

/** 구조화 오류 코드 — 도구 실패와 설정 검증이 함께 쓴다. */
public enum ErrorCode {
    OK,
    BAD_REQUEST,          // 요청 본문 파싱 불가
    UNKNOWN_TOOL,         // 알 수 없는 MCP 도구
    INVALID_ARGUMENTS,    // 인자 형식 · 조합 오류
    MISSING_FIELD,        // 필수 값 누락 (기본값 없는 값 포함)
    OUT_OF_RANGE,         // 허용 범위 벗어남
    INVALID_ENUM,         // 허용되지 않은 선택지
    EXECUTION_ERROR,      // 시뮬레이션 실행 오류
    INTERNAL_ERROR,       // 예기치 못한 서버 오류
    CRITICAL_OVERLOAD     // 수요가 공급을 크게 넘어 실험이 성립하지 않음
}
