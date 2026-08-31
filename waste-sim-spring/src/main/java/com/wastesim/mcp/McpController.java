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
 * MCP 서버 — JSON-RPC 2.0 over HTTP. 외부 SDK 없이 프로토콜 핵심
 * 메서드(initialize·tools/list·tools/call·ping)를 직접 구현한다. 이 시스템의
 * 존재 이유 — "LLM(GPT·로컬)이 이 도구를 호출해 복잡한 시뮬레이션을 생성"한다 —
 * 를 실현하는 진입점. 실제 검증·실행은 SimulationTool 파사드가 담당한다.
 *
 * <p><b>엔드포인트</b>: {@code POST /mcp} 하나뿐이다. 이 서버는 장량동 하나만 다루므로
 * 도메인별로 주소를 가를 이유가 없다.
 *
 * <p>라즈베리파이 엣지 도메인이 함께 있던 시절에는 {@code /mcp/waste}·{@code /mcp/edge}가
 * 각각 자기 도메인 도구만 노출했다. 목록뿐 아니라 실행(tools/call)에서도 도메인 경계를
 * 강제해, 이름만 알면 남의 엔드포인트에서 부르는 일을 막았다. 도메인이 하나가 되면서
 * 그 경계도, 슬러그 규약({@code McpDomain})도 함께 사라졌다.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimulationTool tool;
    private final McpToolCatalog catalog;
    private final SimulationModelRegistry models;
    private final McpToolRegistry independentTools;

    public McpController(SimulationTool tool, McpToolCatalog catalog, SimulationModelRegistry models,
                         McpToolRegistry independentTools) {
        this.tool = tool;
        this.catalog = catalog;
        this.models = models;
        this.independentTools = independentTools;
    }

    /** 유일한 MCP 엔드포인트. */
    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<?> handle(@RequestBody JsonNode req) {
        return dispatch(req);
    }

    /** JSON-RPC 요청 하나를 처리한다. */
    private ResponseEntity<?> dispatch(JsonNode req) {
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

        // 공개한 inputSchema의 required 필드를 실행 <b>전에</b> 강제한다(A-01). 이 검사가
        // 없으면 collectionTime 없이 부른 요청이 조용히 기본값 12:00으로 돌아가, 클라이언트는
        // 자기 요청이 불완전했다는 사실조차 모른 채 엉뚱한 실험 결과를 받는다. 스키마가
        // 진실 원천이므로 별도 목록을 만들지 않고 스키마의 required를 그대로 재사용한다.
        List<String> missing = McpToolCatalog.missingRequired(catalog.inputSchemaFor(mapper, name), args);
        if (!missing.isEmpty()) {
            return textResult("필수 인자 누락: " + String.join(", ", missing)
                    + " (도구 " + name + "의 스키마에 required로 선언된 필드입니다)", true);
        }

        // 모델 어댑터(run_waste_simulation, run_waste_simulation_devs, ...)는
        // 전부 SimulationModelRegistry로 라우팅한다 — 새 모델이 추가돼도 이
        // switch는 손댈 필요가 없다(MCP_모델_연결_방법.md §2).
        SimulationModelProvider model = models.byToolName(name);
        if (model != null) {
            return toCallResult(tool.runSimulation(ConfigArgs.fromJson(args), model.modelId(), true));
        }

        // SimulationConfig 스키마를 쓰지 않는 독립 도구(서브태스크 3종) — 변환도
        // SimulationConfigValidator 검증도 거치지 않고 원본 JSON을 그대로 넘긴다
        // (McpToolProvider 참고). 구현체가 자기 방식으로 파싱·검증한다.
        McpToolProvider indep = independentTools.byToolName(name);
        if (indep != null) {
            return toCallResult(indep.call(args));
        }

        switch (name) {
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
