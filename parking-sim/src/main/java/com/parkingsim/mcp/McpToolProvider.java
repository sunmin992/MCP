package com.parkingsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkingsim.tool.ToolResult;

/**
 * MCP 도구 하나. 구현체를 스프링 빈으로 등록하면 {@link McpToolRegistry} 가 모아
 * {@code tools/list} 와 {@code tools/call} 에 싣는다.
 */
public interface McpToolProvider {

    /** tools/list · tools/call 에 노출되는 도구 이름. */
    String toolName();

    /** tools/list 에 노출되는 설명. */
    String description();

    /** tools/list 에 노출되는 입력 JSON Schema. */
    String inputSchemaJson();

    /** 원본 JSON 인자를 받아 실행한다. 실패는 {@link ToolResult#rejected} 로 낸다. */
    ToolResult call(JsonNode args);
}
