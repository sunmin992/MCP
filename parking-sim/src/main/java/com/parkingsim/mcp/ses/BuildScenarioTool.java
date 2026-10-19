package com.parkingsim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkingsim.mcp.McpToolProvider;
import com.parkingsim.pes.ExperimentFrame;
import com.parkingsim.pes.Pes;
import com.parkingsim.pes.Scenario;
import com.parkingsim.pes.ScenarioBuilder;
import com.parkingsim.template.TemplateCatalog;
import com.parkingsim.mcp.ToolFailure;
import com.parkingsim.tool.ToolResult;
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

    private static final java.util.Set<String> KNOWN_ORIGINS = java.util.Set.of("USER", "MODEL_DEFAULT", "DERIVED");

    @Override public String toolName() { return "build_scenario"; }

    @Override
    public String description() {
        return "PES 와 실험 프레임으로 실행 설정 N벌을 만들고 검증한다. 실행 설정마다 서브태스크 생성 조건을 다시 "
                + "평가해, 물어야 할 결정이 비어 있으면 막는다 — 기본값을 받아들인 결정도 그 값을 values 에 넣어야 한다. "
                + "통과하면 UNCONFIRMED 로 보관되고, 사람이 확인 화면에서 승인해야 토큰이 나온다. " + ORIGIN_RULE;
    }

    /** 출처 규칙. 확인 화면이 무엇을 "승인하지 않은 기본값" 으로 경고하는지가 이 규칙으로 정해진다. */
    static final String ORIGIN_RULE = "출처(origins) 규칙: USER = 사용자가 그 값을 직접 말했다. "
            + "MODEL_DEFAULT = 사용자가 값을 말하지 않았다 — LLM 이 템플릿 기본값을 제안했거나, 사용자가 '기본값으로', "
            + "'알아서 해줘' 처럼 값 없이 넘겼다. 확인 화면은 이 값들을 승인하지 않은 기본값으로 따로 보여 준다. "
            + "DERIVED = 사용자가 말한 다른 내용에서 정해졌다(예: '평일 교통량' → trafficMode=APPLY). "
            + "출처를 적지 않은 값은 MODEL_DEFAULT 로 본다.";

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "values":{"type":"object","description":"답변키 → 확정값"},
               "origins":{"type":"object","description":"답변키 → USER / MODEL_DEFAULT / DERIVED. 사용자가 값을 직접 말했을 때만 USER, '기본값으로' 처럼 값 없이 넘긴 것은 MODEL_DEFAULT. 적지 않으면 MODEL_DEFAULT"},
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
            List<String> badOrigins = new ArrayList<>();
            args.path("origins").fields().forEachRemaining(e -> {
                String o = e.getValue().asText().trim().toUpperCase(java.util.Locale.ROOT);
                if (!KNOWN_ORIGINS.contains(o)) badOrigins.add(e.getKey() + "=" + e.getValue().asText());
                origins.put(e.getKey(), o);
            });
            if (!badOrigins.isEmpty()) {
                return ToolFailure.of("origins", "알 수 없는 출처: " + badOrigins + ". " + ORIGIN_RULE);
            }
            // 출처를 적지 않은 값은 사용자가 말했는지 알 수 없다. 사용자 값으로 넘겨짚으면 확인 화면에서
            // 경고가 사라진다 — 보이는 쪽(MODEL_DEFAULT)으로 둔다.
            values.keySet().forEach(k -> origins.putIfAbsent(k, "MODEL_DEFAULT"));

            List<Object> variableValues = new ArrayList<>();
            args.path("variableValues").forEach(v -> variableValues.add(JsonValues.plain(v)));

            List<String> observations = new ArrayList<>();
            args.path("observations").forEach(v -> observations.add(v.asText()));

            Pes pes = new Pes(catalog.sesId(), catalog.sesVersion(), values, origins);
            ExperimentFrame frame = new ExperimentFrame(
                    args.path("variableAnswerKey").asText(), variableValues, observations);

            Scenario scenario = builder.build(pes, frame);

            // 실험 변수는 값이 여럿이라 기본 PES 에 들어 있지 않다. LLM 이 그 값들을 제안했으면
            // (출처 MODEL_DEFAULT) 확인 화면이 경고할 수 있도록, 보관할 PES 에 비교할 값 목록을
            // 함께 싣는다. 식별자와 토큰은 builder 가 이미 계산했으므로 바뀌지 않는다.
            String variableKey = frame.variableAnswerKey();
            if (origins.containsKey(variableKey) && !values.containsKey(variableKey)) {
                Map<String, Object> shown = new LinkedHashMap<>(values);
                shown.put(variableKey, List.copyOf(variableValues));
                pes = new Pes(pes.sesId(), pes.sesVersion(), shown, origins);
            }
            if (scenario.valid()) store.put(scenario, pes);

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
            // 토큰은 여기서 나오지 않는다. 검증을 통과해도 아직 미확인이며,
            // 사람이 확인 화면에서 승인해야 발급된다(ScenarioConfirmController → ConfirmScenarioTool).
            root.put("state", store.entry(scenario.scenarioId())
                    .map(ScenarioStore.Entry::state).orElse("INVALID"));
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (IllegalArgumentException e) {
            return ToolFailure.of("values", e.getMessage());
        } catch (Exception e) {
            return ToolFailure.of("scenario", "시나리오 생성에 실패했습니다: " + e.getMessage());
        }
    }
}
