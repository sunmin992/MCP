package com.wastesim.tool;

import java.util.List;

/**
 * 서버측 검증 결과. ready=false면 실행 금지(fail-closed).
 * warnings는 실행을 막지 않는 비차단 경고(예: V-T5 피크 시각) — 비어있지
 * 않으면 호출측(SimulationTool)이 즉시 실행 대신 사용자 확인을 유도할 수 있다.
 */
public record ValidationResult(boolean ready, List<ValidationError> errors, List<ValidationError> warnings) {
    public static ValidationResult ok() { return new ValidationResult(true, List.of(), List.of()); }
    public static ValidationResult ok(List<ValidationError> warnings) {
        return new ValidationResult(true, List.of(), warnings);
    }
    public static ValidationResult fail(List<ValidationError> e) { return new ValidationResult(false, e, List.of()); }
    public static ValidationResult fail(ValidationError e) { return fail(List.of(e)); }
}
