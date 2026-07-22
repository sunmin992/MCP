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
        SimulationEngine engine = new SimulationEngine(new TrafficDataService());
        SimulationService sim = new SimulationService(engine);
        ScenarioService sc = new ScenarioService(sim);
        SimulationModelRegistry models = new SimulationModelRegistry(List.of(new JavaEngineProvider(sim)));
        SimulationTool tool = new SimulationTool(new SimulationConfigValidator(new TrafficDataService()), models, sc, new SimpleMeterRegistry());
        return new McpController(tool, new McpToolCatalog(tool, models), models);
    }

    private JsonNode call(String json) throws Exception {
        ResponseEntity<?> resp = controller().handle(om.readTree(json));
        return (JsonNode) resp.getBody();
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
}
