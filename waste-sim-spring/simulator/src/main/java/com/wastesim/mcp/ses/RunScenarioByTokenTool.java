package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.pes.Scenario;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.SimulationService;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 확인 토큰이 맞는 시나리오만 실행한다.
 *
 * <p>토큰은 사용자가 확인한 설정과 실제 실행 설정이 같음을 묶어 주는 것이다. 발급하고
 * 대조하는 데까지만 가고 <b>검사하는 자리가 없으면</b> 검증 안 된 설정으로 실행될 수 있는
 * 경로가 남는다 — 그러면 토큰은 아무것도 보장하지 못한다. 이 도구가 그 자리다.
 *
 * <p>보관된 시나리오를 다시 해싱해 대조하므로, 발급 이후에 설정이 바뀌었으면 옛 토큰은
 * 무효다. 실행 직전에 다시 세는 것이 요점이다.
 */
@Component
public class RunScenarioByTokenTool implements McpToolProvider {

    private final ScenarioBuilder builder;
    private final ScenarioStore store;
    private final SimulationService simulations;
    private final ObjectMapper mapper;

    public RunScenarioByTokenTool(ScenarioBuilder builder, ScenarioStore store,
                                  SimulationService simulations, ObjectMapper mapper) {
        this.builder = builder;
        this.store = store;
        this.simulations = simulations;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "run_scenario_by_token"; }

    @Override
    public String description() {
        return "확인 토큰이 맞는 시나리오를 실행한다. 토큰이 없거나 발급 이후 설정이 바뀌었으면 "
                + "실행하지 않는다. 결과는 설정 간 비교이며 운영 예측이 아니다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "scenarioId":{"type":"string","description":"build_scenario 가 돌려준 시나리오 id"},
               "confirmToken":{"type":"string","description":"build_scenario 가 발급한 확인 토큰"},
               "seed":{"type":"integer","description":"쓰이지 않는다 — 반복 횟수는 시나리오의 seeds 가 정하고 시드는 1..seeds 로 고정이다"}},
             "required":["scenarioId","confirmToken"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String scenarioId = args.path("scenarioId").asText(null);
        String token = args.path("confirmToken").asText(null);
        if (scenarioId == null || scenarioId.isBlank()) {
            return ToolFailure.of("scenarioId", "시나리오 id 가 없습니다.");
        }
        if (token == null || token.isBlank()) {
            return ToolFailure.of("confirmToken",
                    "확인 토큰이 없습니다 — 확인하지 않은 설정은 실행하지 않습니다.");
        }

        Optional<ScenarioStore.Entry> found = store.entry(scenarioId);
        if (found.isEmpty()) {
            return ToolFailure.of("scenarioId",
                    "보관된 시나리오가 없습니다: " + scenarioId
                            + " — build_scenario 로 다시 만드십시오. 서버가 다시 뜨면 토큰도 함께 사라집니다.");
        }
        // 확인 한 번에 실행 한 번이다. 토큰이 계속 통하면 확인이 무기한 허가가 된다.
        if (found.get().executedAt() != null) {
            return ToolFailure.of("confirmToken",
                    "이미 실행한 시나리오입니다: " + scenarioId
                            + " — 다시 돌리려면 사용자가 확인 화면에서 다시 확인해야 합니다.");
        }
        Scenario scenario = found.get().scenario();

        // 실행 직전에 다시 센다. 발급 이후 설정이 바뀌었으면 여기서 어긋난다.
        if (scenario.confirmToken() == null) {
            return ToolFailure.of("confirmToken",
                    "아직 확인되지 않은 시나리오입니다: " + scenarioId
                            + " — 사용자에게 설정을 보여주고 confirm_scenario 를 먼저 부르십시오.");
        }
        if (!builder.tokenMatches(scenario, token)) {
            return ToolFailure.of("confirmToken",
                    "확인 토큰이 이 시나리오의 현재 설정과 맞지 않습니다 — "
                            + "발급 이후 설정이 바뀌었거나 다른 시나리오의 토큰입니다.");
        }

        try {
            var runs = mapper.createArrayNode();
            for (SimulationConfig cfg : scenario.runs()) {
                // 시나리오가 확인한 반복 횟수(cfg.getSeeds())만큼 돌려 집계한다.
                // 엔진을 직접 한 번 부르면 "반복 30회" 로 확인해 놓고 1회 결과를 내주게 된다 —
                // 사용자가 확인한 것과 다른 실험의 값이다.
                SimulationResult r = simulations.runExperiment(cfg);
                var node = runs.addObject();
                node.put("collectionTimeMinutes", cfg.getCollectionTimeMinutes());

                // 반복 집계가 채우는 값만 낸다. totalComplaints·peakFillKg·seed 는 한 번
                // 돌렸을 때의 값이라 집계 요약에 없다 — 없는 것을 0 으로 내보내면 재지 않은
                // 값을 잰 것처럼 읽힌다.
                node.put("meanComplaints", r.getMeanComplaints());
                node.put("stdComplaints", r.getStdComplaints());
                node.put("truckUtilizationPercent", r.getTruckUtilizationPercent());
                node.put("uncollectedDemandKg", r.getUncollectedDemandKg());

                // 능력 카드의 alwaysAttachToResult — "무엇으로 계산한 값인가" 를
                // 결과만 보고 알 수 있어야 한다. 시드는 1..seeds 로 고정이다.
                node.put("seeds", cfg.getSeeds());
                node.put("massBalanceErrorKg", r.getMassBalanceErrorKg());
                node.set("allTotals", mapper.valueToTree(r.getAllTotals()));
                node.set("dataQualityFlags", mapper.valueToTree(r.getDataQualityFlags()));
                node.set("assumptionNotes", mapper.valueToTree(r.getAssumptionNotes()));
                node.put("coordinateQuality", r.getCoordinateQualityLabel());
            }

            store.markExecuted(scenarioId);

            var root = mapper.createObjectNode();
            root.put("scenarioId", scenario.scenarioId());
            root.put("state", store.entry(scenarioId)
                    .map(ScenarioStore.Entry::state).orElse("EXECUTED"));
            root.put("notForOperationalUse", true);
            root.put("limitation", "이 결과는 설정 간 비교이며 운영 예측이 아니다.");
            root.set("runs", runs);
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("scenario", "실행에 실패했습니다: " + e.getMessage());
        }
    }
}
