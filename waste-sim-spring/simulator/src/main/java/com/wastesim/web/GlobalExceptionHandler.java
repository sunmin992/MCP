package com.wastesim.web;

import com.wastesim.tool.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 핸들러 — 모든 REST 오류를 일관된 ApiError JSON으로 변환한다.
 * 스택트레이스 노출을 막고, 잘못된 입력을 5xx가 아닌 4xx로 정확히 분류한다
 * (예: /compare 의 무방비 캐스트로 나던 500 → 400).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 깨진/파싱 불가 JSON 본문 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(
                ApiError.of(ErrorCode.BAD_REQUEST.name(), "요청 본문을 파싱할 수 없습니다."));
    }

    /** 잘못된 타입/인자 (ClassCastException 포함) */
    @ExceptionHandler({IllegalArgumentException.class, ClassCastException.class})
    public ResponseEntity<ApiError> onBadArgs(RuntimeException e) {
        return ResponseEntity.badRequest().body(
                ApiError.of(ErrorCode.INVALID_ARGUMENTS.name(),
                        "요청 인자가 올바르지 않습니다: " + e.getMessage()));
    }

    /** favicon.ico 등 없는 정적 리소스 — 브라우저가 자동 요청하는 흔한 케이스라
     *  ERROR 로그·500 없이 조용히 404만 반환한다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> onResourceNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(ErrorCode.BAD_REQUEST.name(), "요청한 리소스를 찾을 수 없습니다."));
    }

    /** 그 외 예기치 못한 오류 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onOther(Exception e) {
        log.error("처리되지 않은 서버 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(ErrorCode.INTERNAL_ERROR.name(), "서버 내부 오류가 발생했습니다."));
    }
}
