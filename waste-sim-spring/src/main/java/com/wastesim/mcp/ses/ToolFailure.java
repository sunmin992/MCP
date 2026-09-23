package com.wastesim.mcp.ses;

import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;

/**
 * 도구 실패를 {@code ToolResult} 로 옮기는 한 자리.
 *
 * <p>{@code ToolResult} 에는 {@code error(String)} 가 없다 — 실패는 언제나 어느 자리의
 * 무슨 사유인지를 함께 말해야 하므로 {@link ValidationError} 를 거친다. 다섯 도구가
 * 같은 조립을 반복하지 않도록 여기 한 번만 적는다.
 */
final class ToolFailure {

    private ToolFailure() {}

    static ToolResult of(String field, String message) {
        return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, field, message));
    }
}
