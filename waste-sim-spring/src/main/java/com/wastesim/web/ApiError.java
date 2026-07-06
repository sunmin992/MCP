package com.wastesim.web;

import com.wastesim.tool.ValidationError;

import java.util.List;

/** REST 오류 응답의 공통 형태. code=안정적 오류 코드, errors=필드별 상세(선택). */
public record ApiError(String code, String message, List<ValidationError> errors) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of());
    }
    public static ApiError of(String code, String message, List<ValidationError> errors) {
        return new ApiError(code, message, errors);
    }
}
