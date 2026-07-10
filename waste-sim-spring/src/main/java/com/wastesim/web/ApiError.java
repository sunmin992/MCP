package com.wastesim.web;

import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.springframework.http.ResponseEntity;

import java.util.List;

/** REST 오류 응답의 공통 형태. code=안정적 오류 코드, errors=필드별 상세(선택). */
public record ApiError(String code, String message, List<ValidationError> errors) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of());
    }
    public static ApiError of(String code, String message, List<ValidationError> errors) {
        return new ApiError(code, message, errors);
    }

    /**
     * ToolResult → ResponseEntity 공통 변환(검증 실패 시 400+ApiError, 성공 시
     * 200+result). ScenarioController/SimulationController에 각각 따로
     * 있던 동일 패턴을 통합.
     */
    public static ResponseEntity<?> respond(ToolResult tr) {
        return respond(tr, "설정 검증 실패");
    }

    public static ResponseEntity<?> respond(ToolResult tr, String failMessage) {
        if (!tr.ready()) {
            return ResponseEntity.badRequest().body(ApiError.of("VALIDATION", failMessage, tr.errors()));
        }
        return ResponseEntity.ok(tr.result());
    }
}
