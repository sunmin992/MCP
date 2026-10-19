package com.parkingsim.validation;

import com.parkingsim.tool.ValidationError;

import java.util.List;

/** 설정 검증 결과. ready=false 면 실행하지 않는다. warnings 는 실행을 막지 않는다. */
public record ValidationResult(boolean ready, List<ValidationError> errors, List<ValidationError> warnings) {

    public static ValidationResult of(List<ValidationError> errors, List<ValidationError> warnings) {
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }
}
