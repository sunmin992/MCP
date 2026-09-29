package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 브로커의 MCP 엔드포인트. LLM 이 3단계(조회)로 부르는 문이다.
 *
 * <p>도구는 {@link McpToolProvider} 빈 전부다 — {@code find_simulators} · {@code list_candidates}.
 * 등록({@code POST /api/simulators})은 도구로 내지 않는다. 도구로 내면 LLM 이 있지도 않은
 * 서버를 등록해 스스로 고를 수 있다.
 */
@RestController
public class BrokerMcpController {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    /** 도구 호출마다 한 줄 — 시뮬레이터의 {@code mcp.calls} 와 같은 이름이라 두 터미널을 나란히 읽을 수 있다. */
    private static final Logger callLog = LoggerFactory.getLogger("mcp.calls");

    private final Map<String, McpToolProvider> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    public BrokerMcpController(List<McpToolProvider> providers, ObjectMapper mapper) {
        providers.forEach(p -> tools.put(p.toolName(), p));
        this.mapper = mapper;
    }

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<?> handle(@RequestBody JsonNode req) {
        JsonNode id = req.get("id");
        // JSON-RPC 알림(id 없음) — 응답하지 않는다.
        if (id == null || id.isNull()) {
            return ResponseEntity.noContent().build();
        }
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        String method = req.path("method").asText("");
        // 시뮬레이터의 CorrelationIdFilter 와 같은 모양의 요청 id. 한 호출에서 나온 줄을 묶는다.
        MDC.put("requestId", UUID.randomUUID().toString().substring(0, 8));
        try {
            switch (method) {
                case "initialize" -> {
                    JsonNode client = req.path("params").path("clientInfo");
                    callLog.info("[연결] MCP 클라이언트 {} {}",
                            client.path("name").asText("(이름 없음)"), client.path("version").asText(""));
                    resp.set("result", initialize());
                }
                case "ping"       -> resp.set("result", mapper.createObjectNode());
                case "tools/list" -> resp.set("result", toolsList());
                case "tools/call" -> resp.set("result", call(req.path("params")));
                default           -> resp.set("error", rpcError(-32601, "Method not found: " + method));
            }
        } catch (Exception e) {
            resp.set("error", rpcError(-32603, "Internal error: " + e.getMessage()));
        } finally {
            MDC.remove("requestId");
        }
        return ResponseEntity.ok(resp);
    }

    private ObjectNode initialize() {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        r.putObject("capabilities").putObject("tools");
        ObjectNode si = r.putObject("serverInfo");
        si.put("name", "waste-sim-broker");
        si.put("version", "1.0.0");
        return r;
    }

    private ObjectNode toolsList() throws Exception {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (McpToolProvider p : tools.values()) {
            ObjectNode t = arr.addObject();
            t.put("name", p.toolName());
            t.put("description", p.description());
            t.set("inputSchema", mapper.readTree(p.inputSchemaJson()));
        }
        return r;
    }

    private ObjectNode call(JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        McpToolProvider tool = tools.get(name);
        if (tool == null) {
            callLog.info("[브로커] 알 수 없는 도구: {}", name);
            return textResult("알 수 없는 도구: " + name, true);
        }
        ToolResult tr = tool.call(params.path("arguments"));
        if (!tr.ready()) {
            callLog.info("[브로커] {} → 거절: {}", name, tr.errors());
            return textResult("검증 실패: " + mapper.writeValueAsString(tr.errors()), true);
        }
        // find_simulators 는 3·4단계를 스스로 적는다. 나머지는 불렸다는 사실만.
        if (!"find_simulators".equals(name)) callLog.info("[브로커] {} 호출", name);
        // 도구가 이미 JSON 문자열을 돌려주면 그대로 싣는다. 한 번 더 직렬화하면 따옴표에 싸인
        // 문자열이 되어 클라이언트가 두 번 풀어야 한다.
        Object result = tr.result();
        return textResult(result instanceof String s ? s : mapper.writeValueAsString(result), false);
    }

    private ObjectNode textResult(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode block = result.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private ObjectNode rpcError(int code, String message) {
        ObjectNode err = mapper.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        return err;
    }
}
