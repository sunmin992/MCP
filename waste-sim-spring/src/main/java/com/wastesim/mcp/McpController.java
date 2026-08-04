package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.tool.ConfigArgs;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
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
 * <p><b>엔드포인트 구성</b> (모두 같은 프로세스·같은 포트 8090):
 * <ul>
 *   <li>{@code POST /mcp} — 허브. 모든 도메인의 도구를 노출한다(기존 주소, 하위호환)</li>
 *   <li>{@code POST /mcp/waste} — 장량동 수거 시뮬레이션 도구만</li>
 *   <li>{@code POST /mcp/edge} — 라즈베리파이 엣지 발열 도구만</li>
 * </ul>
 * 도메인 목록은 {@link McpDomain}이 갖고 있고, 어떤 도구가 어느 도메인인지는
 * 도구 자신이 {@code domain()}으로 선언한다 — 그래서 도구·도메인이 늘어도 이
 * 컨트롤러는 손댈 필요가 없다.
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

    /**
     * <b>허브 엔드포인트</b> — 모든 도메인의 도구를 한 곳에서 노출한다. 도메인
     * 분리 이전부터 쓰던 주소이므로 그대로 둔다(기존 스크립트·MCP 클라이언트 설정
     * 하위호환). 도메인을 가리지 않으므로 {@code domain = null}로 처리한다.
     */
    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<?> handle(@RequestBody JsonNode req) {
        return dispatch(req, null);
    }

    /**
     * <b>도메인 엔드포인트</b> — {@code POST /mcp/waste}, {@code POST /mcp/edge}.
     * 해당 도메인의 도구만 tools/list에 노출하고, tools/call도 그 도메인으로 제한한다.
     *
     * <p><b>왜 나누는가</b>: 허브 하나만 있으면 MCP 클라이언트가 tools/list에서
     * 성격이 전혀 다른 두 계열(장량동 수거 / 라즈베리파이 발열)을 한꺼번에 받는다.
     * LLM이 도구를 고르는 구조에서 선택지가 넓어질수록 엉뚱한 도구를 부를 확률이
     * 올라가고, 이 프로젝트는 그 위험을 줄이려고 도메인 판정을 결정론적으로
     * 처리해 왔다(DomainIntentDetector). 엔드포인트를 나누면 <b>애초에 다른
     * 도메인 도구가 후보에 오르지 않으므로</b> 같은 목표를 프로토콜 층에서
     * 구조적으로 달성한다.
     */
    @PostMapping(value = "/mcp/{domain}", produces = "application/json")
    public ResponseEntity<?> handleDomain(@PathVariable("domain") String domainSlug,
                                          @RequestBody JsonNode req) {
        McpDomain domain = McpDomain.fromSlug(domainSlug);
        if (domain == null) {
            JsonNode idNode = req.get("id");
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", idNode == null ? NullNode.getInstance() : idNode);
            resp.set("error", rpcError(-32601, "알 수 없는 도메인: " + domainSlug + " (지원: " + slugList() + ")"));
            return ResponseEntity.status(404).body(resp);
        }
        return dispatch(req, domain);
    }

    /**
     * JSON-RPC 요청 하나를 처리한다.
     *
     * @param domain 이 요청을 제한할 도메인. {@code null}이면 허브(제한 없음).
     */
    private ResponseEntity<?> dispatch(JsonNode req, McpDomain domain) {
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
                case "initialize"   -> resp.set("result", initialize(domain));
                case "ping"         -> resp.set("result", mapper.createObjectNode());
                case "tools/list"   -> resp.set("result", catalog.toolsList(mapper, domain));
                case "tools/call"   -> resp.set("result", callTool(req.path("params"), domain));
                default             -> resp.set("error", rpcError(-32601, "Method not found: " + method));
            }
        } catch (Exception e) {
            log.error("MCP 처리 오류 (method={}, domain={})", method, domain, e);
            resp.set("error", rpcError(-32603, "Internal error: " + e.getMessage()));
        }
        return ResponseEntity.ok(resp);
    }

    private String slugList() {
        StringBuilder sb = new StringBuilder();
        for (McpDomain d : McpDomain.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.slug());
        }
        return sb.toString();
    }

    private ObjectNode initialize(McpDomain domain) {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = mapper.createObjectNode();
        caps.set("tools", mapper.createObjectNode());
        r.set("capabilities", caps);
        ObjectNode si = mapper.createObjectNode();
        // 서버 이름에 도메인을 실어 준다 — MCP 클라이언트가 두 엔드포인트를 동시에
        // 연결했을 때 목록에서 어느 쪽인지 구분할 수 있어야 한다.
        si.put("name", domain == null ? "waste-sim-mcp" : "waste-sim-mcp-" + domain.slug());
        si.put("version", "1.0.0");
        if (domain != null) {
            si.put("domain", domain.slug());
            si.put("domainLabel", domain.label());
        }
        r.set("serverInfo", si);
        return r;
    }

    private ObjectNode callTool(JsonNode params, McpDomain domain) throws Exception {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");

        // 도메인 경계는 목록(tools/list)뿐 아니라 실행(tools/call)에서도 지킨다 —
        // 그러지 않으면 이름만 알면 아무 엔드포인트에서나 부를 수 있어 분리가
        // 표시상의 구분에 그친다(McpToolCatalog#belongsTo).
        if (!catalog.belongsTo(name, domain)) {
            return textResult("이 엔드포인트(/mcp/" + domain.slug() + ")의 도메인에 없는 도구: " + name, true);
        }

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

        // 장량동 도메인과 무관한 독립 도구/모델 — SimulationConfig 변환도,
        // SimulationConfigValidator 검증도 거치지 않고 원본 JSON을 그대로
        // 넘긴다(McpToolProvider 참고). 등록된 게 없으면 이 분기는 항상 null.
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
