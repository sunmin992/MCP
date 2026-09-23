package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.pes.Scenario;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 사용자가 확인한 시나리오에 토큰을 발급한다.
 *
 * <p>검증 통과는 "돌릴 수 있다"이고 확인은 "돌려도 된다"이다. 둘을 한 단계로 합치면
 * 사용자가 확인하지 않은 상태를 표현할 자리가 없어지고, 그러면 토큰이 확인을 뜻한다고
 * 말할 수 없다 — 검증만 통과해도 받는 표는 확인표가 아니다.
 *
 * <p><b>MCP 도구가 아니다.</b> 확인 화면만 이것을 부른다. 도구로 내면 모델이 스스로
 * 불러 동의를 만들어낼 수 있고, 그러면 토큰이 "사람이 보고 동의했다"를 뜻하지 못한다.
 * 모델은 {@link GetScenarioStatusTool} 로 <b>읽기만</b> 한다.
 */
@Component
public class ConfirmScenarioTool {

    private final ScenarioBuilder builder;
    private final ScenarioStore store;
    private final ObjectMapper mapper;

    public ConfirmScenarioTool(ScenarioBuilder builder, ScenarioStore store, ObjectMapper mapper) {
        this.builder = builder;
        this.store = store;
        this.mapper = mapper;
    }

    public ToolResult call(JsonNode args) {
        String scenarioId = args.path("scenarioId").asText(null);
        if (scenarioId == null || scenarioId.isBlank()) {
            return ToolFailure.of("scenarioId", "시나리오 id 가 없습니다.");
        }

        var found = store.get(scenarioId);
        if (found.isEmpty()) {
            return ToolFailure.of("scenarioId",
                    "보관된 시나리오가 없습니다: " + scenarioId + " — build_scenario 로 다시 만드십시오.");
        }
        Scenario scenario = found.get();
        if (!scenario.valid()) {
            return ToolFailure.of("scenarioId",
                    "검증을 통과하지 못한 시나리오는 확인할 수 없습니다: " + scenario.blocks());
        }

        Scenario confirmed = new Scenario(scenario.scenarioId(), scenario.runs(),
                scenario.blocks(), scenario.backVerificationBlocks(), builder.tokenFor(scenario));
        store.confirm(confirmed);

        try {
            var root = mapper.createObjectNode();
            root.put("scenarioId", confirmed.scenarioId());
            root.put("state", store.entry(scenarioId).orElseThrow().state());
            root.put("confirmToken", confirmed.confirmToken());
            root.put("runCount", confirmed.runs().size());
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("scenario", "확인 결과를 내지 못했습니다: " + e.getMessage());
        }
    }
}
