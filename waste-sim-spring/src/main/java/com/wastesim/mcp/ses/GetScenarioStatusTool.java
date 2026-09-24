package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 시나리오의 확인 상태를 <b>읽는다</b>.
 *
 * <p>모델이 실행하려면 토큰을 알아야 하는데, 발급은 확인 화면만 한다. 그래서 읽는 문만
 * 내준다 — 여기로는 상태를 바꿀 수 없으므로 모델이 동의를 만들어낼 수 없고, 사람이
 * 화면에서 확인할 때까지 기다렸다가 토큰을 집어 갈 수는 있다.
 *
 * <p>확인 전에는 토큰 자리가 비어 있고, 무엇을 해야 하는지 {@code nextStep} 이 말한다.
 */
@Component
public class GetScenarioStatusTool implements McpToolProvider {

    private final ScenarioStore store;
    private final ObjectMapper mapper;

    public GetScenarioStatusTool(ScenarioStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "get_scenario_status"; }

    @Override
    public String description() {
        return "시나리오가 확인됐는지 읽는다. UNCONFIRMED 면 사용자에게 확인 화면을 안내하고, "
                + "CONFIRMED 가 되면 함께 나온 토큰으로 실행한다. 이 도구는 상태를 바꾸지 않는다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "scenarioId":{"type":"string","description":"build_scenario 가 돌려준 시나리오 id"}},
             "required":["scenarioId"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String scenarioId = args.path("scenarioId").asText(null);
        if (scenarioId == null || scenarioId.isBlank()) {
            return ToolFailure.of("scenarioId", "시나리오 id 가 없습니다.");
        }

        var found = store.entry(scenarioId);
        if (found.isEmpty()) {
            return ToolFailure.of("scenarioId",
                    "보관된 시나리오가 없습니다: " + scenarioId + " — build_scenario 로 다시 만드십시오.");
        }
        ScenarioStore.Entry e = found.get();

        try {
            var root = mapper.createObjectNode();
            root.put("scenarioId", scenarioId);
            root.put("state", e.state());
            root.put("runCount", e.scenario().runs().size());
            root.put("unapprovedDefaultCount", e.pes().unapprovedDefaults().size());
            root.put("confirmUrl", "/confirm.html");
            root.put("nextStep", switch (e.state()) {
                case "UNCONFIRMED" -> "사용자에게 " + "/confirm.html" + " 을 열어 설정을 확인하도록 안내하십시오. "
                        + "확인은 사람만 할 수 있습니다.";
                case "CONFIRMED" -> "run_scenario_by_token 에 confirmToken 을 실어 실행하십시오.";
                default -> "이미 실행했습니다. 다시 돌리려면 확인 화면에서 다시 확인하십시오.";
            });
            if (e.scenario().confirmToken() != null) {
                root.put("confirmToken", e.scenario().confirmToken());
            }
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception ex) {
            return ToolFailure.of("scenario", "상태를 내지 못했습니다: " + ex.getMessage());
        }
    }
}
