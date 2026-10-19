package com.parkingsim.tool;

import java.util.List;

/** 도구 실행 결과. ready=true 면 result 에 결과, false 면 errors 에 사유. */
public record ToolResult(boolean ready, Object result, List<ValidationError> errors) {
    public static ToolResult ok(Object result) {
        return new ToolResult(true, result, List.of());
    }
    public static ToolResult rejected(List<ValidationError> errors) {
        return new ToolResult(false, null, errors);
    }
    public static ToolResult rejected(ValidationError e) { return rejected(List.of(e)); }
}
