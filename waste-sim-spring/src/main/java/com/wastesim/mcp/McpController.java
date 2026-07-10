package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.tool.ConfigArgs;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 서버 — JSON-RPC 2.0 over HTTP(POST /mcp). 외부 SDK 없이 프로토콜 핵심
 * 메서드(initialize·tools/list·tools/call·ping)를 직접 구현한다. 이 시스템의
 * 존재 이유 — "LLM(GPT·로컬)이 이 도구를 호출해 복잡한 시뮬레이션을 생성"한다 —
 * 를 실현하는 진입점. 실제 검증·실행은 SimulationTool 파사드가 담당한다.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimulationTool tool;
    private final McpToolCatalog catalog;

    public McpController(SimulationTool tool, McpToolCatalog catalog) {
        this.tool = tool;
        this.catalog = catalog;
    }

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<?> handle(@RequestBody JsonNode req) {
        String method = req.path("method").asText("");
        JsonNode idNode = req.get("id");

        // JSON-RPC 알림(notification: id 없음) — 응답하지 않음
        if (idNode == null || idNode.isNull()) {
            return ResponseEntity.noContent().build();
        }

        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", idNode);
        try {
            switch (method) {
                case "initialize"   -> resp.set("result", initialize());
                case "ping"         -> resp.set("result", mapper.createObjectNode());
                case "tools/list"   -> resp.set("result", catalog.toolsList(mapper));
                case "tools/call"   -> resp.set("result", callTool(req.path("params")));
                default             -> resp.set("error", rpcError(-32601, "Method not found: " + method));
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
        ObjectNode caps = mapper.createObjectNode();
        caps.set("tools", mapper.createObjectNode());
        r.set("capabilities", caps);
        ObjectNode si = mapper.createObjectNode();
        si.put("name", "waste-sim-mcp");
        si.put("version", "1.0.0");
        r.set("serverInfo", si);
        return r;
    }

    private ObjectNode callTool(JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");

        switch (name) {
            case "run_waste_simulation":
                return toCallResult(tool.runSimulation(ConfigArgs.fromJson(args)));
            case "run_scenario":
                return toCallResult(tool.runScenario(args.path("type").asText(""), ConfigArgs.fromJson(args)));
            case "list_scenarios":
                return textResult(catalog.scenarioListText(), false);
            case "update_route_sequence":
                return toCallResult(tool.updateRouteSequence(ConfigArgs.fromJson(args), routeSequenceArg(args)));
            default:
                return textResult("알 수 없는 도구: " + name, true);
        }
    }

    /** ToolResult → MCP CallToolResult{ content:[{type:text,...}], isError } */
    private ObjectNode toCallResult(ToolResult tr) throws Exception {
        if (tr.ready()) {
            return textResult(mapper.writeValueAsString(tr.result()), false);
        }
        return textResult("검증 실패: " + mapper.writeValueAsString(tr.errors()), true);
    }

    private List<String> routeSequenceArg(JsonNode args) {
        List<String> out = new ArrayList<>();
        if (args.has("routeSequence") && args.get("routeSequence").isArray()) {
            for (JsonNode n : args.get("routeSequence")) out.add(n.asText());
        }
        return out;
    }

    private ObjectNode textResult(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        content.add(block);
        result.set("content", content);
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
