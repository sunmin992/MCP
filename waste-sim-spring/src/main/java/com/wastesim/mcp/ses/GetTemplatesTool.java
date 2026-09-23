package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 서브태스크 템플릿을 낸다 — <b>질문 목록이 아니라 규칙집</b>이다.
 *
 * <p>LLM 은 이 규칙의 생성 조건을 평가해 요청마다 필요한 작업만 만든다.
 */
@Component
public class GetTemplatesTool implements McpToolProvider {

    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;

    public GetTemplatesTool(TemplateCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "get_templates"; }

    @Override
    public String description() {
        return "서브태스크 템플릿을 낸다. 각 템플릿은 결정 대상·선택지·생성 조건·"
                + "기본값·실행 대응을 담은 규칙이며, 인스턴스는 요청마다 만들어야 한다.";
    }

    @Override
    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult call(JsonNode args) {
        try {
            var root = mapper.createObjectNode();
            root.put("sesId", catalog.sesId());
            root.put("sesVersion", catalog.sesVersion());
            root.set("templates", mapper.valueToTree(catalog.all()));
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("templates", "템플릿을 직렬화하지 못했습니다: " + e.getMessage());
        }
    }
}
