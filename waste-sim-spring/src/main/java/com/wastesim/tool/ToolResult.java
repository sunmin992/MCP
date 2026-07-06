package com.wastesim.tool;

import java.util.List;

/**
 * 툴 파사드 실행 결과. 채팅·REST·MCP가 공유하는 단일 반환 형태.
 * ready=true면 result에 SimulationResult/ScenarioResponse, false면 errors에 사유.
 */
public record ToolResult(boolean ready, Object result, List<ValidationError> errors) {
    public static ToolResult ok(Object result) { return new ToolResult(true, result, List.of()); }
    public static ToolResult rejected(List<ValidationError> errors) { return new ToolResult(false, null, errors); }
    public static ToolResult rejected(ValidationError e) { return rejected(List.of(e)); }
}
