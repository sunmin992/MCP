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

    private JsonNode callDomain(String json, String slug, McpController c) throws Exception {
        ResponseEntity<?> resp = c.handleDomain(slug, om.readTree(json));
        return (JsonNode) resp.getBody();
    }

    /** 테스트 전용 — SimulationConfig와 무관한 독립 도구를 흉내낸다(엣지 모델 자리표시자). */
    private static class FakeIndependentTool implements McpToolProvider {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public McpDomain domain() { return McpDomain.EDGE; }
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

    // ── 도메인별 엔드포인트(POST /mcp/{domain}) ────────────────────────────
    //
    // 도메인 분리의 핵심 성질은 두 가지다.
    //   (1) 목록이 갈린다 — 각 엔드포인트가 자기 도메인 도구만 tools/list에 노출
    //   (2) 실행도 갈린다 — 이름을 알아도 남의 도메인 도구는 tools/call로 못 부른다
    // (2)가 없으면 분리는 표시상의 구분에 그치므로 반드시 함께 검증한다.

    @Test
    void wasteEndpointExposesOnlyWasteTools() throws Exception {
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        JsonNode b = callDomain("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\"}", "waste", c);
        JsonNode tools = b.path("result").path("tools");
        // run_waste_simulation + run_scenario + list_scenarios + update_route_sequence
        assertEquals(4, tools.size());
        for (JsonNode t : tools) {
            assertNotEquals("predict_edge_throttling", t.path("name").asText(),
                    "엣지 도구가 /mcp/waste 목록에 새면 안 된다");
        }
    }

    @Test
    void edgeEndpointExposesOnlyEdgeTools() throws Exception {
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        JsonNode b = callDomain("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}", "edge", c);
        JsonNode tools = b.path("result").path("tools");
        assertEquals(1, tools.size());
        assertEquals("predict_edge_throttling", tools.get(0).path("name").asText());
    }

    @Test
    void hubEndpointStillExposesEverything() throws Exception {
        // 하위호환 — 기존 스크립트·MCP 클라이언트가 쓰던 POST /mcp는 그대로 전부 보여준다.
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        JsonNode b = call("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/list\"}", c);
        assertEquals(5, b.path("result").path("tools").size());
    }

    @Test
    void crossDomainCallIsBlocked() throws Exception {
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        // 엣지 도구를 장량동 엔드포인트에서 부르려는 시도
        JsonNode b = callDomain("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"predict_edge_throttling\",\"arguments\":{\"temperature\":75}}}",
                "waste", c);
        assertTrue(b.path("result").path("isError").asBoolean());

        // 반대 방향도 마찬가지 — 장량동 도구를 엣지 엔드포인트에서 부를 수 없다
        JsonNode b2 = callDomain("{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_waste_simulation\","
                + "\"arguments\":{\"collectionTime\":\"08:00\",\"days\":2,\"seeds\":2}}}",
                "edge", c);
        assertTrue(b2.path("result").path("isError").asBoolean());
    }

    @Test
    void sameDomainCallStillRuns() throws Exception {
        // 경계를 세운 뒤에도 제 도메인 도구는 허브에서와 똑같이 실행돼야 한다.
        McpController c = controller(new McpToolRegistry(List.of(new FakeIndependentTool())));
        JsonNode b = callDomain("{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"predict_edge_throttling\",\"arguments\":{\"temperature\":75}}}",
                "edge", c);
        assertFalse(b.path("result").path("isError").asBoolean());
        assertTrue(b.path("result").path("content").get(0).path("text").asText().contains("\"rttMinutes\":3"));
    }

    @Test
    void unknownDomainReturns404() throws Exception {
        McpController c = controller();
        ResponseEntity<?> resp = c.handleDomain("nope", om.readTree("{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"tools/list\"}"));
        assertEquals(404, resp.getStatusCode().value());
        assertEquals(-32601, ((JsonNode) resp.getBody()).path("error").path("code").asInt());
    }

    @Test
    void initializeReportsDomainOnDomainEndpoint() throws Exception {
        // MCP 클라이언트가 두 엔드포인트를 동시에 연결했을 때 목록에서 구분할 수 있어야 한다.
        McpController c = controller();
        JsonNode b = callDomain("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"initialize\"}", "edge", c);
        assertEquals("waste-sim-mcp-edge", b.path("result").path("serverInfo").path("name").asText());
        assertEquals("edge", b.path("result").path("serverInfo").path("domain").asText());

        // 허브는 예전 이름을 그대로 유지한다(하위호환).
        JsonNode hub = call("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"initialize\"}", c);
        assertEquals("waste-sim-mcp", hub.path("result").path("serverInfo").path("name").asText());
    }
}
