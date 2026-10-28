package com.parkingsim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkingsim.mcp.McpToolProvider;
import com.parkingsim.template.TemplateCatalog;
import com.parkingsim.mcp.ToolFailure;
import com.parkingsim.tool.ToolResult;
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
            root.put("apiMode", "실제 면수를 쓰려면 fetch_parking_snapshot(실시간) 또는 fetch_standard_parking(표준 시설정보+초기 빈자리 사용자 가정)으로 선택하고 plan_subtasks와 build_scenario에 parkingSnapshotId를 전달하십시오. lotCount·spacesPerLot는 생략하며 initialVehicleRemainingMinutes(기존 차량 잔여 체류시간 가정)를 별도로 묻습니다.");
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("templates", "템플릿을 직렬화하지 못했습니다: " + e.getMessage());
        }
    }
}
