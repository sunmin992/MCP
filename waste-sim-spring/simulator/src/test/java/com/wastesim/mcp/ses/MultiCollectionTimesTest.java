package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.SimulationService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "하루 몇 번 수거하는 게 민원이 가장 적은가" 를 확인 흐름으로 물을 수 있는가.
 *
 * <p>엔진은 전부터 하루 수거 시각 목록({@code collectionTimesMinutes})을 돌렸다. 막혀 있던 것은
 * 대화 쪽이다 — 템플릿이 시각 하나만 받았고, 목록 값은 build_scenario 에 들어오는 순간 빈
 * 문자열이 됐다. 횟수마다 몇 시에 수거할지는 LLM 이 제안하고 사람이 확인 화면에서 본다.
 */
class MultiCollectionTimesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final ScenarioStore store = new ScenarioStore();
    private final ScenarioBuilder builder = new ScenarioBuilder(
            new PesFlattener(catalog), new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));
    private final BuildScenarioTool build = new BuildScenarioTool(builder, store, catalog, mapper);
    private final PlanSubtasksTool plan = new PlanSubtasksTool(new SubtaskPlanner(catalog), mapper);
    private final ValidateAnswersTool validate =
            new ValidateAnswersTool(catalog, new AnswerNormalizer(), mapper);

    private JsonNode ok(com.wastesim.mcp.McpToolProvider tool, JsonNode args) throws Exception {
        var r = tool.call(args);
        assertTrue(r.ready(), tool.toolName() + " 이 거절했다: " + r);
        return mapper.readTree(r.result().toString());
    }

    /** 1대 · 한 동네 · 평일 교통. 수거 시각은 넣지 않는다 — 실험 변수가 채운다. */
    private ObjectNode 기본값() {
        ObjectNode v = mapper.createObjectNode();
        v.put("truckType", "SMALL_1TON");
        v.put("truckCount", 1);
        v.put("numBuildings", 4);
        v.put("residentsPerBuilding", 25);
        v.put("days", 7);
        v.put("seeds", 3);
        v.put("trafficMode", "APPLY");
        v.put("trafficProfileId", "jangryang-weekday");
        v.put("travelTimeMode", "LEGACY_CONSTANT");
        v.put("routeAvailableCapacityKg", 150);
        v.putArray("occupationMix").add("BlueCollar").add("Student").add("Housewife");
        return v;
    }

    private ObjectNode 횟수별_시나리오() {
        var args = mapper.createObjectNode();
        args.set("values", 기본값());
        args.put("variableAnswerKey", "collectionTimesMinutes");
        var vv = args.putArray("variableValues");
        vv.addArray().add(660);
        vv.addArray().add(660).add(1380);
        vv.addArray().add(180).add(660).add(1140);
        return args;
    }

    @Test
    void 횟수마다_시각_목록으로_시나리오를_만든다() throws Exception {
        JsonNode s = ok(build, 횟수별_시나리오());
        assertTrue(s.path("valid").asBoolean(), "막힌 사유: " + s.path("blocks"));
        assertEquals(3, s.path("runCount").asInt());
        assertEquals(0, s.path("backVerificationBlocks").size(),
                "되읽은 설정이 PES 와 달라지면 사용자가 준 시각으로 돌았다고 말할 수 없다: "
                        + s.path("backVerificationBlocks"));

        var runs = store.get(s.path("scenarioId").asText()).orElseThrow().runs();
        assertEquals(List.of(660), runs.get(0).getCollectionTimesMinutes());
        assertEquals(List.of(660, 1380), runs.get(1).getCollectionTimesMinutes());
        assertEquals(List.of(180, 660, 1140), runs.get(2).getCollectionTimesMinutes(),
                "목록이 빈 문자열로 바뀌면 전부 기본 1회(12시)로 돌고 그 사실이 아무 데도 안 남는다");
    }

    @Test
    void 모델이_제안한_시각이면_확인_화면에_승인하지_않은_기본값으로_뜬다() throws Exception {
        // 횟수마다 몇 시에 수거할지는 사용자가 말하지 않았다 — LLM 이 제안했다. 실험 변수라고
        // 해서 USER 로 덮으면 확인 화면이 그 사실을 경고하지 못한다.
        ObjectNode args = 횟수별_시나리오();
        args.putObject("origins").put("collectionTimesMinutes", "MODEL_DEFAULT");
        JsonNode s = ok(build, args);
        assertEquals("[[660],[660,1380],[180,660,1140]]",
                s.path("unapprovedDefaults").path("collectionTimesMinutes").toString(),
                "승인하지 않은 기본값: " + s.path("unapprovedDefaults"));

        var entry = store.entry(s.path("scenarioId").asText()).orElseThrow();
        assertTrue(entry.pes().unapprovedDefaults().containsKey("collectionTimesMinutes"),
                "확인 화면은 보관된 PES 로 경고를 띄운다 — 여기에 없으면 화면에도 없다");
    }

    @Test
    void 시각_목록을_주면_단일_수거시각은_묻지_않고_완결이다() throws Exception {
        var args = mapper.createObjectNode();
        ObjectNode answers = 기본값();
        answers.putArray("collectionTimesMinutes").add(660).add(1380);
        args.set("answers", answers);
        JsonNode p = ok(plan, args);

        String single = null, multi = null;
        for (JsonNode i : p.path("instances")) {
            if (i.path("answerKey").asText().equals("collectionTimeMinutes")) single = i.path("status").asText();
            if (i.path("answerKey").asText().equals("collectionTimesMinutes")) multi = i.path("status").asText();
        }
        assertEquals("FILLED", multi);
        assertEquals("NOT_GENERATED", single, "둘 다 받으면 어느 쪽으로 도는지 사용자가 알 수 없다");
        assertTrue(p.path("complete").asBoolean(), "counts: " + p.path("counts"));
    }

    @Test
    void 시각_목록을_안_주면_지금처럼_단일_수거시각을_묻는다() throws Exception {
        var args = mapper.createObjectNode();
        args.set("answers", 기본값());
        JsonNode p = ok(plan, args);
        for (JsonNode i : p.path("instances")) {
            if (i.path("answerKey").asText().equals("collectionTimesMinutes")) {
                assertEquals("NOT_GENERATED", i.path("status").asText(),
                        "다회 수거를 말하지 않은 대화에 목록을 물으면 기존 흐름이 한 칸 늘어난다");
            }
            if (i.path("answerKey").asText().equals("collectionTimeMinutes")) {
                assertEquals("UNFILLED", i.path("status").asText());
            }
        }
    }

    @Test
    void 시각_목록을_검증하고_보정하지_않는다() throws Exception {
        var args = mapper.createObjectNode();
        args.putObject("answers").putArray("collectionTimesMinutes").add(660).add(1380);
        JsonNode good = ok(validate, args);
        assertTrue(good.path("valid").asBoolean(), good.toString());
        assertEquals("[660,1380]", good.path("normalized").path("collectionTimesMinutes").toString());

        for (String bad : List.of("[660,1500]", "[660,660]", "[]", "[열한시]")) {
            var a = mapper.createObjectNode();
            a.putObject("answers").set("collectionTimesMinutes", mapper.readTree(
                    bad.equals("[열한시]") ? "[\"열한시\"]" : bad));
            JsonNode out = ok(validate, a);
            assertFalse(out.path("valid").asBoolean(), bad + " 가 통과했다: " + out);
        }
    }

    @Test
    void 실행_결과에_어느_시각_목록으로_돌았는지_실린다() throws Exception {
        JsonNode s = ok(build, 횟수별_시나리오());
        String id = s.path("scenarioId").asText();
        var confirm = new ConfirmScenarioTool(builder, store, mapper);
        // 사용자가 확인 화면에서 누른 자리. 확인 도구는 MCP 도구가 아니다.
        String token = mapper.readTree(confirm.call(mapper.createObjectNode().put("scenarioId", id))
                .result().toString()).path("confirmToken").asText();

        var run = new RunScenarioByTokenTool(builder, store,
                new SimulationService(new SimulationEngine(new TrafficDataService())), mapper);
        JsonNode out = ok(run, mapper.createObjectNode().put("scenarioId", id).put("confirmToken", token));
        assertEquals("[180,660,1140]", out.path("runs").get(2).path("collectionTimesMinutes").toString(),
                "결과에 시각 목록이 없으면 세 행이 모두 collectionTimeMinutes=720 으로 보인다");
    }
}
