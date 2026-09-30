package com.wastesim.web;

import com.wastesim.tool.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 핸들러 — 모든 REST 오류를 일관된 ApiError JSON으로 변환한다.
 * 스택트레이스 노출을 막고, 잘못된 입력을 5xx가 아닌 4xx로 정확히 분류한다
 * (예: /compare 의 무방비 캐스트로 나던 500 → 400).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Spring MVC가 결정한 상태 코드와 Allow 등 프로토콜 헤더를 보존한다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = e instanceof HttpMessageNotReadableException
                ? "요청 본문을 파싱할 수 없습니다."
                : e instanceof NoResourceFoundException
                ? "요청한 리소스를 찾을 수 없습니다."
                : status.is5xxServerError()
                ? "서버 내부 오류가 발생했습니다."
                : "요청 형식, 인자 또는 HTTP 메서드를 확인해 주세요.";
        String code = status.is5xxServerError()
                ? ErrorCode.INTERNAL_ERROR.name() : ErrorCode.BAD_REQUEST.name();
        return super.handleExceptionInternal(e, ApiError.of(code, message), headers, status, request);
    }

    /** 잘못된 타입/인자 (ClassCastException 포함) */
    @ExceptionHandler({IllegalArgumentException.class, ClassCastException.class})
    public ResponseEntity<ApiError> onBadArgs(RuntimeException e) {
        return ResponseEntity.badRequest().body(
                ApiError.of(ErrorCode.INVALID_ARGUMENTS.name(),
                        "요청 인자가 올바르지 않습니다: " + e.getMessage()));
    }

    /** 그 외 예기치 못한 오류 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onOther(Exception e) {
        log.error("처리되지 않은 서버 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(ErrorCode.INTERNAL_ERROR.name(), "서버 내부 오류가 발생했습니다."));
    }
}
