package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolCatalog;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.McpToolRegistry;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.SimulationService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.wastesim.tool.ConfigArgs;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IT-77~84 — <b>MCP 도구 통합</b>(TDD 3.17.6).
 *
 * <p>신규 3종이 WASTE 도메인에만 노출되고, 기존 required 강제와 도메인 경계가 그대로
 * 적용되는지를 본다. 도메인 경계는 목록에서만이 아니라 <b>실행</b>에서도 지켜져야 한다 —
 * 그러지 않으면 이름만 알면 아무 엔드포인트에서나 부를 수 있어 분리가 표시상의 구분에 그친다.
 */
class JangnyangSubtaskMcpToolsTest {

    private final ObjectMapper om = new ObjectMapper();
    private final TrafficDataService traffic = new TrafficDataService();

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final JangnyangSubtaskValidator validator = new JangnyangSubtaskValidator();
    private final JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
    private final JangnyangScenarioBuilder builder =
            new JangnyangScenarioBuilder(checker, new SimulationConfigValidator(traffic), traffic);

    private final GetJangnyangFixedSubtasksTool getTool = new GetJangnyangFixedSubtasksTool(catalog);
    private final ValidateJangnyangSubtaskAnswersTool validateTool =
            new ValidateJangnyangSubtaskAnswersTool(catalog, validator, checker);
    private final BuildJangnyangScenarioTool buildTool =
            new BuildJangnyangScenarioTool(catalog, validator, builder);
    private final List<McpToolProvider> newTools = List.of(getTool, validateTool, buildTool);

    private JsonNode json(String s) throws Exception { return om.readTree(s); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ToolResult r) {
        assertTrue(r.ready(), () -> "실행이 거부됐다: " + r.errors());
        return (Map<String, Object>) r.result();
    }

    /** 전 항목을 채운 답변 인자. */
    private String fullAnswersJson() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("subtaskSetId", "jangnyang-simulator-v2");
        args.put("version", 2);
        args.put("answers", V2Answers.all());
        return om.writeValueAsString(args);
    }

    @Test
    @DisplayName("IT-77 신규 3종이 레지스트리에 등록되고 스키마가 유효한 JSON — 허브 기준 14종")
    void toolsRegisterWithValidSchemas() throws Exception {
        McpToolRegistry registry = new McpToolRegistry(newTools);
        assertEquals(3, registry.all().size());
        for (McpToolProvider p : registry.all()) {
            assertSame(p, registry.byToolName(p.toolName()));
            assertFalse(p.description().isBlank());
            JsonNode schema = json(p.inputSchemaJson());
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("properties").size() > 0);
        }
        assertNotNull(registry.byToolName("get_jangnyang_fixed_subtasks"));
        assertNotNull(registry.byToolName("validate_jangnyang_subtask_answers"));
        assertNotNull(registry.byToolName("build_jangnyang_scenario"));
    }

    // 노출 <b>규모</b>(허브 14종·장량동 8종·엣지 6종)는 여기서 세지 않는다 —
    // McpToolExposureTest가 실제 스프링 컨텍스트에서 센다.
    //
    // 손으로 만든 레지스트리로 세던 것이 문제였다: 이 테스트가 자기가 넣은 3종만 세는
    // 동안 선택 3종이 함께 등록돼 서버는 17종을 내보내고 있었고, 단언은 초록불이었다.
    // 세는 대상이 "내가 만든 목록"이면 무엇이 등록됐는지는 영영 알 수 없다. 여기 남은
    // 것은 도구 하나하나의 행동이고, 규모는 컨텍스트가 답한다.

    @Test
    @DisplayName("IT-78 신규 3종이 tools/list에 실린다")
    void newToolsAreExposed() throws Exception {
        JsonNode tools = fullCatalog().toolsList(om).path("tools");
        List<String> names = new java.util.ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));

        assertTrue(names.contains("get_jangnyang_fixed_subtasks"));
        assertTrue(names.contains("validate_jangnyang_subtask_answers"));
        assertTrue(names.contains("build_jangnyang_scenario"));
    }

    // IT-79(도메인 경계 — 엣지 엔드포인트에서 장량동 도구가 보이지도 실행되지도 않는다)는
    // 사라졌다. 엣지 도메인을 별도 시스템으로 분리하면서 지킬 경계 자체가 없어졌기 때문이다.
    // 경계를 코드로 강제하던 McpDomain·belongsTo()도 함께 제거됐다.

    @Test
    @DisplayName("IT-80 subtaskSetId 없이 validate를 부르면 기본값으로 실행하지 않고 거부한다")
    void requiredArgumentsAreEnforced() throws Exception {
        // required 강제는 기존 missingRequired()가 그대로 적용된다 — 신규 도구를 위해
        // 별도 검사 목록을 만들지 않는다(스키마가 단일 원천, A-01).
        JsonNode schema = json(validateTool.inputSchemaJson());
        List<String> missing = McpToolCatalog.missingRequired(schema, json("{\"answers\":{}}"));
        assertTrue(missing.contains("subtaskSetId"));
        assertTrue(missing.contains("version"));

        // 세트 ID가 어긋나면 도구 자신도 거부한다.
        ToolResult wrongSet = validateTool.call(json(
                "{\"subtaskSetId\":\"다른-세트\",\"version\":2,\"answers\":{}}"));
        assertFalse(wrongSet.ready());
        assertEquals("subtaskSetId", wrongSet.errors().get(0).field());

        // build도 같은 규칙이어야 한다 — 한 도구만 관대하면 그 경로로 우회된다.
        assertFalse(buildTool.call(json(
                "{\"subtaskSetId\":\"다른-세트\",\"version\":2,\"answers\":{}}")).ready());
        assertTrue(McpToolCatalog.missingRequired(json(buildTool.inputSchemaJson()),
                json("{}")).contains("answers"));
    }

    @Test
    @DisplayName("IT-81 get_jangnyang_fixed_subtasks의 버전·해시·배열이 카탈로그와 일치한다")
    void getToolMatchesCatalog() throws Exception {
        Map<String, Object> out = result(getTool.call(json("{}")));
        JangnyangSubtaskDefinition def = catalog.latest();

        assertEquals(def.subtaskSetId(), out.get("subtaskSetId"));
        assertEquals(def.version(), out.get("version"));
        assertEquals(def.hash(), out.get("hash"));
        assertEquals(Boolean.TRUE, out.get("immutable"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("subtasks");
        assertEquals(def.subtasks().size(), items.size());
        // FR-120이 요구하는 열 항목이 응답에 하나도 빠지지 않아야 한다.
        for (Map<String, Object> item : items) {
            for (String key : List.of("id", "order", "question", "answerField", "answerType",
                    "required", "allowedRange", "validationRule", "retryQuestion", "completionCondition")) {
                assertTrue(item.containsKey(key), "응답에 " + key + "가 없다: " + item.get("id"));
            }
        }
        assertEquals(def.ordered().get(0).question(), items.get(0).get("question"));

        // 명시적으로 버전 1을 요청해도 같은 응답이다.
        assertEquals(out, result(getTool.call(json("{\"version\":2}"))));
    }

    @Test
    @DisplayName("IT-82 일부만 채운 답변은 missing·errors·재질문 문장과 함께 complete=false로 돌아온다")
    void validateRoundTripReportsMissingAndRetry() throws Exception {
        ToolResult r = validateTool.call(json("""
            {"subtaskSetId":"jangnyang-simulator-v2","version":2,
             "answers":{"ST-001":"목적","ST-020":"25:99"}}
            """));
        Map<String, Object> out = result(r);

        assertEquals(Boolean.FALSE, out.get("complete"));
        assertEquals(Boolean.FALSE, out.get("valid"));

        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) out.get("missing");
        assertTrue(missing.contains("ST-020"), "형식이 틀린 답은 채워진 것으로 세지 않는다");
        assertTrue(missing.contains("ST-035"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) out.get("errors");
        assertEquals(1, errors.size());
        assertEquals("ST-020", errors.get(0).get("subtaskId"));
        assertEquals(catalog.latest().byId("ST-020").retryQuestion(),
                errors.get(0).get("retryQuestion"),
                "재질문 문장을 호출자가 짓지 않아도 되게 함께 실어야 한다(FR-127)");

        // 시나리오별 충분성도 함께 돌려준다 — 필수는 다 찼는데 실행이 안 되는 경우를
        // 호출자가 구분할 수 있어야 한다(FR-130).
        assertEquals(Boolean.FALSE, out.get("sufficient"));
        assertNull(out.get("scenarioType"), "ST-02가 없으면 유형도 없다");
    }

    @Test
    @DisplayName("IT-83 전 항목을 채우면 scenarioSpec·simulationConfig·appliedDefaults·assumptions를 반환한다")
    void buildRoundTripReturnsSpecAndConfig() throws Exception {
        Map<String, Object> out = result(buildTool.call(json(fullAnswersJson())));

        assertNotNull(out.get("scenarioSpec"));
        assertNotNull(out.get("simulationConfig"));
        assertInstanceOf(SimulationConfig.class, out.get("simulationConfig"));
        // v2는 값을 거의 다 묻기 때문에 채워 넣을 것이 없을 수도 있다. 대신 "해당 없음"으로
        // 넘어간 항목이 가정으로 남는지를 본다 — 답하지 않기로 한 것도 실험의 조건이다.
        assertNotNull(out.get("appliedDefaults"));
        assertFalse(((List<?>) out.get("assumptions")).isEmpty(),
                "가정을 숨기면 호출자는 조건을 모른 채 결과를 읽는다(D-53)");
        assertEquals("single-run", out.get("scenarioType"));
        assertEquals("run_waste_simulation", out.get("toolName"));
        assertEquals(SimulationModelRegistry.DEFAULT_MODEL_ID, out.get("engineId"));
        assertTrue(String.valueOf(out.get("preview")).contains("이 조건으로 실행할까요?"));

        // 미충족이면 부분 명세를 만들지 않고 거부한다(UT-324와 같은 규칙, 도구 계층에서).
        ToolResult partial = buildTool.call(json("""
            {"subtaskSetId":"jangnyang-simulator-v2","version":2,"answers":{"ST-001":"목적"}}
            """));
        assertFalse(partial.ready());
        assertFalse(partial.errors().isEmpty());
    }

    @Test
    @DisplayName("IT-84 build 결과의 config로 기존 실행 경로가 그대로 동작한다(새 실행 경로 없음)")
    void builtConfigRunsThroughTheExistingTool() throws Exception {
        Map<String, Object> out = result(buildTool.call(json(fullAnswersJson())));
        SimulationConfig cfg = (SimulationConfig) out.get("simulationConfig");

        // 기존 파사드를 그대로 부른다 — 구성 계층이 우회로를 만들지 않았다는 확인이다.
        SimulationService sim = new SimulationService(new SimulationEngine(traffic));
        SimulationTool tool = new SimulationTool(
                new SimulationConfigValidator(traffic),
                new SimulationModelRegistry(List.of(new com.wastesim.mcp.JavaEngineProvider(sim))),
                new com.wastesim.service.ScenarioService(sim),
                new SimpleMeterRegistry());

        ToolResult run = tool.runSimulation(cfg, out.get("engineId").toString(), true);
        assertTrue(run.ready(), () -> "기존 실행 경로가 거부했다: " + run.errors());
        assertNotNull(run.result());

        // MCP 인자 매핑(ConfigArgs)으로 한 바퀴 돌려도 같은 설정이어야 한다 — 구성 계층이
        // 만든 설정이 기존 도구의 입력 계약과 어긋나지 않는지 보는 것이다.
        JsonNode asArgs = om.valueToTree(cfg);
        SimulationConfig roundTripped = ConfigArgs.fromJson(asArgs);
        assertEquals(cfg.getDays(), roundTripped.getDays());
        assertEquals(cfg.getNumBuildings(), roundTripped.getNumBuildings());
    }

    /** 신규 3종을 등록한 카탈로그 — 도구가 목록에 실리는지 보기 위한 것이다. */
    private McpToolCatalog fullCatalog() {
        SimulationService sim = new SimulationService(new SimulationEngine(traffic));
        SimulationModelRegistry models = new SimulationModelRegistry(List.of(
                new com.wastesim.mcp.JavaEngineProvider(sim),
                new com.wastesim.mcp.PythonWasteSimAdapter()));
        SimulationTool tool = new SimulationTool(new SimulationConfigValidator(traffic), models,
                new com.wastesim.service.ScenarioService(sim), new SimpleMeterRegistry());
        return new McpToolCatalog(tool, models, new McpToolRegistry(newTools));
    }
}
