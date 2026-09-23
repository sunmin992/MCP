package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 0단계 능력 카드를 그대로 낸다.
 *
 * <p>자바 모델을 거쳐 다시 조립하지 않고 원문 JSON 을 낸다 — 중간에 모델을 거치면
 * 칸이 하나 늘 때마다 코드를 고쳐야 하고, 빠뜨린 칸은 조용히 사라진다.
 */
@Component
public class GetCapabilityTool implements McpToolProvider {

    private final CapabilityCardLoader loader;
    private final ObjectMapper mapper;

    public GetCapabilityTool(CapabilityCardLoader loader, ObjectMapper mapper) {
        this.loader = loader;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "get_capability"; }

    @Override
    public String description() {
        return "이 시뮬레이터의 능력 카드를 낸다 — 분석 단위, 결정 지점, 적용 범위, "
                + "품질과 보정 근거, 조건부 기능, 그리고 하지 않는 일 열두 가지.";
    }

    @Override
    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult call(JsonNode args) {
        return ToolResult.ok(loader.rawJson().toString());
    }
}
