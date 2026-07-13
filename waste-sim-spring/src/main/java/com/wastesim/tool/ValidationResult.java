package com.wastesim.tool;

import java.util.List;

/** 서버측 검증 결과. ready=false면 실행 금지(fail-closed). */
public record ValidationResult(boolean ready, List<ValidationError> errors) {
    public static ValidationResult ok() { return new ValidationResult(true, List.of()); }
    public static ValidationResult fail(List<ValidationError> e) { return new ValidationResult(false, e); }
    public static ValidationResult fail(ValidationError e) { return fail(List.of(e)); }
}
