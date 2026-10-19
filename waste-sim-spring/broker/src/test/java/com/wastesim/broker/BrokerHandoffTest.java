package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrokerHandoffTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private BrokerMcpController controller(Runnable onCall) {
        McpToolProvider tool = new McpToolProvider() {
            public String toolName() { return "find_simulators"; }
            public String description() { return "test"; }
            public String inputSchemaJson() { return "{}"; }
            public ToolResult call(JsonNode args) {
                onCall.run();
                return ToolResult.ok("""
                        {"verdict":"MATCH","recommended":{"serverId":"jangnyang","endpoint":"http://localhost/mcp"}}
                        """);
            }
        };
        return new BrokerMcpController(List.of(tool), mapper, new BrokerSessions());
    }

    private JsonNode call(BrokerMcpController controller, String arguments) throws Exception {
        JsonNode request = mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"find_simulators","arguments":%s}}
                """.formatted(arguments));
        JsonNode response = mapper.valueToTree(controller.handle(request, "same-session").getBody());
        assertFalse(response.has("error"), response.toString());
        return mapper.readTree(response.path("result").path("content").path(0).path("text").asText());
    }

    @Test
    void unrelatedRequestsInOneSessionDoNotShareHandoffs() throws Exception {
        var controller = controller(() -> {});
        String first = "{\"domain\":\"쓰레기수거\",\"objective\":\"수거 시각\"}";
        String second = "{\"domain\":\"쓰레기수거\",\"objective\":\"차량 수\"}";
        assertFalse(call(controller, first).has("previousMatch"));
        assertFalse(call(controller, second).has("previousMatch"));
        // JSON 객체의 키 순서가 달라도 같은 조회다.
        assertTrue(call(controller, "{\"objective\":\"수거 시각\",\"domain\":\"쓰레기수거\"}")
                .has("previousMatch"));
    }

    @Test
    void overlappingCallsOnlySeeHandoffsFromBeforeTheyStarted() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var controller = controller(() -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        });
        String arguments = "{\"domain\":\"쓰레기수거\"}";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> call(controller, arguments));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = pool.submit(() -> call(controller, arguments));
                assertFalse(second.get(5, TimeUnit.SECONDS).has("previousMatch"));
            } finally {
                release.countDown();
            }
            assertFalse(first.get(5, TimeUnit.SECONDS).has("previousMatch"));
        }
        assertTrue(call(controller, arguments).has("previousMatch"));
    }
}
