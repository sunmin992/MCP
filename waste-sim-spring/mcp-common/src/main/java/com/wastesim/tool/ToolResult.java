package com.wastesim.tool;

import java.util.List;

/**
 * 툴 파사드 실행 결과. 채팅·REST·MCP가 공유하는 단일 반환 형태.
 * ready=true면 result에 SimulationResult/ScenarioResponse, false면 errors에 사유.
 *
 * <p>needsConfirm=true(ready도 true)는 실행 자체는 막지 않지만 비차단 경고가
 * 있어 사용자 확인을 거치길 권하는 상태(TRAFFIC_EXTENSION_DESIGN.md §7.2,
 * V-T5) — 이때 result는 아직 실행되지 않은 SimulationConfig이고, warnings에
 * 사유가 담긴다. 호출측이 확인 후 재실행(스킵 옵션)하면 실제 결과를 받는다.
 */
public record ToolResult(boolean ready, Object result, List<ValidationError> errors,
                          boolean needsConfirm, List<ValidationError> warnings) {
    public static ToolResult ok(Object result) {
        return new ToolResult(true, result, List.of(), false, List.of());
    }
    public static ToolResult rejected(List<ValidationError> errors) {
        return new ToolResult(false, null, errors, false, List.of());
    }
    public static ToolResult rejected(ValidationError e) { return rejected(List.of(e)); }
    public static ToolResult needsConfirm(Object proposedConfig, List<ValidationError> warnings) {
        return new ToolResult(true, proposedConfig, List.of(), true, warnings);
    }
}
