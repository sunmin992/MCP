package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 등록된 시뮬레이터를 요약해 낸다.
 *
 * <p>{@code find_simulators} 가 빈 목록을 냈을 때 "그럼 무엇이 있는가" 를 답한다. 이것이
 * 없으면 후보가 없다는 답에서 요청을 어떻게 고쳐야 할지 알 수 없다.
 *
 * <p><b>가상 여부를 함께 낸다.</b> 지어낸 후보를 실재하는 것처럼 내면 누군가 그 endpoint 를
 * 부르려 한다.
 */
@Component
public class ListCandidatesTool implements McpToolProvider {

    private final CandidateRegistry registry;
    private final ObjectMapper mapper;

    public ListCandidatesTool(CandidateRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "list_candidates"; }

    @Override
    public String description() {
        return "브로커에 등록된 시뮬레이터를 요약해 낸다 — 서버 id, 도메인, 분석 단위, "
                + "그리고 매칭 평가용으로 지어낸 서버인지 여부.";
    }

    @Override
    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult call(JsonNode args) {
        try {
            var list = mapper.createArrayNode();
            for (JsonNode card : registry.cards()) {
                var node = list.addObject();
                node.put("serverId", card.path("serverId").asText());
                node.put("name", card.path("name").asText());
                node.set("domain", card.path("domain"));
                node.put("scope", card.path("analysisUnit").path("scope").asText());
                node.put("unit", card.path("analysisUnit").path("label").asText());
                node.put("fictional", card.path("fictional").asBoolean(false));
                if (card.path("fictional").asBoolean(false)) {
                    node.put("fictionalReason", card.path("fictionalReason").asText());
                }
            }
            var root = mapper.createObjectNode();
            root.put("count", list.size());
            root.set("candidates", list);
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("candidates", "후보 목록을 내지 못했습니다: " + e.getMessage());
        }
    }
}
