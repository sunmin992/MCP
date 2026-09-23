package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.pes.ExperimentFrame;
import com.wastesim.pes.Pes;
import com.wastesim.pes.Scenario;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PES 와 실험 프레임을 받아 실행 설정 N벌을 만들고 검증한다.
 *
 * <p>검증을 통과해야 확인 토큰이 나온다. 토큰 없이는 실행하지 않는다.
 *
 * <p>SES 식별자는 도구에 적지 않고 {@link TemplateCatalog} 에서 가져온다. 여기 적어 두면
 * 템플릿 리소스가 바뀌어도 도구가 옛 식별자를 주장하게 되고, "어느 SES 로 만든 설정인가"가
 * 틀린 채로 실행까지 간다.
 */
@Component
public class BuildScenarioTool implements McpToolProvider {

    private final ScenarioBuilder builder;
    private final ScenarioStore store;
    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;

    public BuildScenarioTool(ScenarioBuilder builder, ScenarioStore store,
                             TemplateCatalog catalog, ObjectMapper mapper) {
        this.builder = builder;
        this.store = store;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "build_scenario"; }

    @Override
    public String description() {
        return "PES 와 실험 프레임으로 실행 설정 N벌을 만들고 검증한다. 통과하면 확인 토큰을 "
                + "발급하며, 그 토큰으로만 실행할 수 있다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "values":{"type":"object","description":"답변키 → 확정값"},
               "origins":{"type":"object","description":"답변키 → USER / MODEL_DEFAULT / DERIVED"},
               "variableAnswerKey":{"type":"string","description":"실험 변수의 답변키"},
               "variableValues":{"type":"array","description":"비교할 값들"},
               "observations":{"type":"array","items":{"type":"string"}}},
             "required":["values","variableAnswerKey","variableValues"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            args.path("values").fields().forEachRemaining(
                    e -> values.put(e.getKey(), JsonValues.plain(e.getValue())));

            Map<String, String> origins = new LinkedHashMap<>();
            args.path("origins").fields().forEachRemaining(
                    e -> origins.put(e.getKey(), e.getValue().asText()));
            values.keySet().forEach(k -> origins.putIfAbsent(k, "USER"));

            List<Object> variableValues = new ArrayList<>();
            args.path("variableValues").forEach(v -> variableValues.add(JsonValues.plain(v)));

            List<String> observations = new ArrayList<>();
            args.path("observations").forEach(v -> observations.add(v.asText()));

            Pes pes = new Pes(catalog.sesId(), catalog.sesVersion(), values, origins);
            ExperimentFrame frame = new ExperimentFrame(
                    args.path("variableAnswerKey").asText(), variableValues, observations);

            Scenario scenario = builder.build(pes, frame);
            if (scenario.valid()) store.put(scenario);

            var root = mapper.createObjectNode();
            root.put("sesId", pes.sesId());
            root.put("sesVersion", pes.sesVersion());
            root.put("scenarioId", scenario.scenarioId());
            root.put("valid", scenario.valid());
            root.put("runCount", scenario.runs().size());
            root.set("blocks", mapper.valueToTree(scenario.blocks()));
            root.set("backVerificationBlocks",
                    mapper.valueToTree(scenario.backVerificationBlocks()));
            root.set("unapprovedDefaults", mapper.valueToTree(pes.unapprovedDefaults()));
            if (scenario.confirmToken() != null) root.put("confirmToken", scenario.confirmToken());
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (IllegalArgumentException e) {
            return ToolFailure.of("values", e.getMessage());
        } catch (Exception e) {
            return ToolFailure.of("scenario", "시나리오 생성에 실패했습니다: " + e.getMessage());
        }
    }
}
