package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.ses.SesCatalog;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/** SES 원본 JSON 을 모델을 거치지 않고 그대로 낸다. */
@Component
public class GetSesTool implements McpToolProvider {

    private final SesCatalog catalog;

    public GetSesTool(SesCatalog catalog) {
        this.catalog = catalog;
    }

    @Override public String toolName() { return "get_ses"; }

    @Override
    public String description() {
        return "이 시뮬레이터의 SES 원본 트리를 낸다 — 엔티티 분해·특수화·다중 분해·속성·결합과, "
                + "결정마다 그 자리(at)와 답변키. 결정의 answerKey 는 get_templates 의 answerKey 와 같다.";
    }

    @Override
    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult call(JsonNode args) {
        return ToolResult.ok(catalog.tree().raw().toString());
    }
}
