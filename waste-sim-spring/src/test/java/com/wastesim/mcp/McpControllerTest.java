package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.service.ScenarioService;
import com.wastesim.service.SimulationService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.SimulationTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpControllerTest {

    private final ObjectMapper om = new ObjectMapper();

    private McpController controller() {
        return controller(new McpToolRegistry(List.of()));
    }

    private McpController controller(McpToolRegistry independentTools) {
        SimulationEngine engine = new SimulationEngine(new TrafficDataService());
        SimulationService sim = new SimulationService(engine);
        ScenarioService sc = new ScenarioService(sim);
        SimulationModelRegistry models = new SimulationModelRegistry(List.of(new JavaEngineProvider(sim)));
        SimulationTool tool = new SimulationTool(new SimulationConfigValidator(new TrafficDataService()), models, sc, new SimpleMeterRegistry());
        return new McpController(tool, new McpToolCatalog(tool, models, independentTools), models, independentTools);
    }

    private JsonNode call(String json) throws Exception {
        return call(json, controller());
    }

    private JsonNode call(String json, McpController c) throws Exception {
        ResponseEntity<?> resp = c.handle(om.readTree(json));
        return (JsonNode) resp.getBody();
    }

    /** 테스트 전용 — SimulationConfig와 무관한 독립 도구를 흉내낸다(엣지 모델 자리표시자). */
    private static class FakeIndependentTool implements McpToolProvider {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public String toolName() { return "predict_edge_throttling"; }
        @Override public String description() { return "테스트용 가짜 엣지 모델"; }
        @Override public String inputSchemaJson() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public com.wastesim.tool.ToolResult call(JsonNode args) {
            var node = mapper.createObjectNode();
            node.put("rttMinutes", args.path("temperature").asDouble() > 70 ? 3 : 30);
            return com.wastesim.tool.ToolResult.ok(node);
        }
    }

    @Test
    void toolsListReturnsFour() throws Exception {          // IT-20 (update_route_sequence 추가로 3→4)
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertEquals(4, b.path("result").path("tools").size());
        assertTrue(b.path("result").path("tools").get(0).has("inputSchema"));
    }

    @Test
    void toolsCallRunsSimulation() throws Exception {        // IT-21
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_waste_simulation\","
                + "\"arguments\":{\"collectionTime\":\"08:00\",\"days\":2,\"seeds\":2}}}");
        assertFalse(b.path("result").path("isError").asBoolean());
        assertTrue(b.path("result").path("content").isArray());
    }

    @Test
    void toolsCallValidationFails() throws Exception {       // IT-22 (fail-closed)
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_waste_simulation\",\"arguments\":{\"days\":0}}}");
        assertTrue(b.path("result").path("isError").asBoolean());
    }

    @Test
    void unknownMethodReturnsError() throws Exception {      // IT-23
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"foo\"}");
        assertEquals(-32601, b.path("error").path("code").asInt());
    }

    @Test
    void updateRouteSequenceRuns() throws Exception {         // IT-T1
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"update_route_sequence\","
                + "\"arguments\":{\"collectionTime\":\"08:00\",\"days\":2,\"seeds\":2,"
                + "\"routeSequence\":[\"Node_A\",\"Node_C\",\"Node_B\",\"Node_D\"]}}}");
        assertFalse(b.path("result").path("isError").asBoolean());
    }

    @Test
    void runWithZeroTrucksBlocked() throws Exception {        // IT-T2 (시나리오 4)
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_waste_simulation\","
                + "\"arguments\":{\"collectionTime\":\"12:00\",\"truckCount\":0}}}");
        assertTrue(b.path("result").path("isError").asBoolean());
        String text = b.path("result").path("content").get(0).path("text").asText();
        assertTrue(text.contains("CRITICAL_WASTE_ACCUMULATION") || text.contains("TRUCK_COUNT_ZERO"));
    }

    // ── MCP 서버·모델 분리(McpToolProvider) ─────────────────────────────────

    @Test
    void toolsListIncludesIndependentToolsWhenRegistered() throws Exception {
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}", c);
        // 기존 4개(run_waste_simulation, run_scenario, list_scenarios, update_route_sequence) + 독립 도구 1개
        assertEquals(5, b.path("result").path("tools").size());
        boolean found = false;
        for (JsonNode t : b.path("result").path("tools")) {
            if ("predict_edge_throttling".equals(t.path("name").asText())) found = true;
        }
        assertTrue(found, "독립 도구가 tools/list에 노출돼야 한다");
    }

    @Test
    void toolsCallRoutesToIndependentToolWithoutSimulationConfigValidation() throws Exception {
        // SimulationConfig 필드(collectionTime 등)를 하나도 안 줘도 실패하지 않아야 한다 —
        // 이 경로는 SimulationConfigValidator를 아예 거치지 않는다는 걸 보여준다.
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"predict_edge_throttling\",\"arguments\":{\"temperature\":75}}}", c);
        assertFalse(b.path("result").path("isError").asBoolean());
        String text = b.path("result").path("content").get(0).path("text").asText();
        assertTrue(text.contains("\"rttMinutes\":3"));
    }

    @Test
    void independentToolAbsentWhenNotRegistered() throws Exception {
        // 등록된 게 없는 기본 상태(no-arg controller())에선 이 도구 이름이 여전히 "알 수 없는 도구"다.
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"predict_edge_throttling\",\"arguments\":{}}}");
        assertTrue(b.path("result").path("isError").asBoolean());
    }
}
