package com.districtsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.districtsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 엔드포인트(JSON-RPC 2.0). 도구는 {@link McpToolRegistry} 에 등록된 것만 낸다.
 *
 * <p>도구 호출마다 {@code mcp.calls} 로거에 한 줄을 남긴다 — 브로커가 고른 서버와 LLM 이 실제로
 * 부른 서버가 같은지를 로그로 대조하는 실험의 원자료다.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final Logger callLog = LoggerFactory.getLogger("mcp.calls");
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(McpToolRegistry registry) {
        this.registry = registry;
    }

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<?> handle(@RequestBody JsonNode req) {
        String method = req.path("method").asText("");
        JsonNode idNode = req.get("id");
        if (idNode == null || idNode.isNull()) {
            return ResponseEntity.noContent().build();   // 알림 — 응답하지 않는다
        }
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", idNode);
        try {
            switch (method) {
                case "initialize" -> {
                    callLog.info("[연결] client={}", req.path("params").path("clientInfo").path("name").asText("?"));
                    resp.set("result", initialize());
                }
                case "ping" -> resp.set("result", mapper.createObjectNode());
                case "tools/list" -> resp.set("result", toolsList());
                case "tools/call" -> resp.set("result", callTool(req.path("params")));
                default -> resp.set("error", rpcError(-32601, "Method not found: " + method));
            }
        } catch (Exception e) {
            log.error("MCP 처리 오류 (method={})", method, e);
            resp.set("error", rpcError(-32603, "Internal error: " + e.getMessage()));
        }
        return ResponseEntity.ok(resp);
    }

    private ObjectNode initialize() {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        r.putObject("capabilities").putObject("tools");
        ObjectNode si = r.putObject("serverInfo");
        si.put("name", "bukgu-district-waste-sim");
        si.put("version", "1.0.0");
        return r;
    }

    private ObjectNode toolsList() throws Exception {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode tools = r.putArray("tools");
        for (McpToolProvider p : registry.all()) {
            ObjectNode t = tools.addObject();
            t.put("name", p.toolName());
            t.put("description", p.description());
            t.set("inputSchema", mapper.readTree(p.inputSchemaJson()));
        }
        return r;
    }

    private ObjectNode callTool(JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        McpToolProvider p = registry.byToolName(name);
        if (p == null) {
            callLog.info("[도구] {} → 알 수 없는 도구", name);
            return textResult("알 수 없는 도구: " + name, true);
        }
        // 스키마의 required 를 실행 전에 강제한다 — 빠진 인자가 기본값으로 조용히 돌지 않게.
        JsonNode schema = mapper.readTree(p.inputSchemaJson());
        for (JsonNode req : schema.path("required")) {
            if (!args.has(req.asText()) || args.get(req.asText()).isNull()) {
                callLog.info("[도구] {} → 필수 인자 누락: {}", name, req.asText());
                return textResult("필수 인자 누락: " + req.asText(), true);
            }
        }
        ToolResult tr = p.call(args.isMissingNode() ? mapper.createObjectNode() : args);
        callLog.info("[도구] {} → {}", name, tr.ready() ? "ok" : "거절 " + tr.errors());
        if (tr.ready()) {
            Object body = tr.result();
            return textResult(body instanceof String s ? s : mapper.writeValueAsString(body), false);
        }
        return textResult("검증 실패: " + mapper.writeValueAsString(tr.errors()), true);
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
