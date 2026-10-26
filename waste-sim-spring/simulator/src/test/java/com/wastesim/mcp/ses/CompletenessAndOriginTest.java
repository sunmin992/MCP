package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실험(2026-10-19)에서 드러난 두 빈틈을 막는가.
 *
 * <ol>
 *   <li>물어야 할 결정이 빠진 PES 가 통과해 실행 설정 초기값으로 조용히 돌던 것 — 서버가 실행 설정마다 다시 센다.</li>
 *   <li>출처를 적지 않은 값이 사용자 값으로 넘겨져 확인 화면 경고에서 빠지던 것 — 기본값으로 보고 보여 준다.</li>
 * </ol>
 */
class CompletenessAndOriginTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final BuildScenarioTool build = new BuildScenarioTool(
            new ScenarioBuilder(new PesFlattener(catalog), new PesBackVerifier(catalog),
                    new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()),
                    new SubtaskPlanner(catalog)),
            new ScenarioStore(), catalog, mapper);

    /** 물어야 할 결정이 모두 들어 있는 장량동 PES. 출처는 모두 USER. */
    private ObjectNode complete() {
        ObjectNode args = mapper.createObjectNode();
        ObjectNode v = args.putObject("values");
        v.put("truckType", "SMALL_1TON");
        v.put("truckCount", 1);
        v.put("numBuildings", 4);
        v.put("residentsPerBuilding", 25);
        v.put("trafficMode", "IGNORE");
        v.put("travelTimeMode", "LEGACY_CONSTANT");
        v.putArray("occupationMix").add("BlueCollar").add("Student").add("Housewife");
        v.put("routeAvailableCapacityKg", 150);
        v.put("dischargeTimeMode", "PAPER_BASELINE");
        v.put("days", 7);
        v.put("seeds", 2);
        ObjectNode o = args.putObject("origins");
        v.fieldNames().forEachRemaining(k -> o.put(k, "USER"));
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(720);
        return args;
    }

    private JsonNode call(ObjectNode args) throws Exception {
        // 요청 원문은 이 시험들의 관심이 아니다 — 빠진 결정과 출처만 본다.
        if (!args.has("request")) args.put("request", "시험 요청");
        ToolResult r = build.call(args);
        assertTrue(r.ready(), String.valueOf(r.errors()));
        return mapper.readTree(r.result().toString());
    }

    @Test
    void 결정이_모두_있으면_통과한다() throws Exception {
        JsonNode out = call(complete());
        assertTrue(out.path("valid").asBoolean(), out.path("blocks").toString());
        assertEquals(0, out.path("unapprovedDefaults").size());
    }

    @Test
    void 기본값_없는_적재_몫이_빠지면_막는다() throws Exception {
        ObjectNode args = complete();
        ((ObjectNode) args.get("values")).remove("routeAvailableCapacityKg");
        ((ObjectNode) args.get("origins")).remove("routeAvailableCapacityKg");
        JsonNode out = call(args);
        assertFalse(out.path("valid").asBoolean(), "빠진 값이 실행 설정 초기값으로 조용히 돌면 안 된다");
        assertTrue(out.path("blocks").toString().contains("routeAvailableCapacityKg"));
        assertEquals("INVALID", out.path("state").asText());
    }

    @Test
    void 기본값이_있는_결정도_넣지_않으면_막는다() throws Exception {
        ObjectNode args = complete();
        ((ObjectNode) args.get("values")).remove("truckType");
        JsonNode out = call(args);
        assertFalse(out.path("valid").asBoolean());
        assertTrue(out.path("blocks").toString().contains("truckType"));
        assertTrue(out.path("blocks").toString().contains("MODEL_DEFAULT"),
                "어떻게 고치면 되는지(기본값을 출처와 함께 넣기)를 알려야 한다");
    }

    @Test
    void 실험_변수에_따라_생기는_결정은_실행_설정마다_센다() throws Exception {
        ObjectNode args = complete();
        args.put("variableAnswerKey", "truckCount");
        args.putArray("variableValues").add(1).add(3);
        ((ObjectNode) args.get("values")).remove("truckCount");
        ((ObjectNode) args.get("values")).put("collectionTimeMinutes", 720);
        JsonNode out = call(args);
        String blocks = out.path("blocks").toString();
        assertFalse(out.path("valid").asBoolean());
        assertTrue(blocks.contains("truckCount=3") && blocks.contains("dispatchIntervalMinutes"),
                "3대 실행에는 배차 간격이 필요하다: " + blocks);
        assertFalse(blocks.contains("truckCount=1"), "1대 실행에는 배차 간격이 생기지 않는다: " + blocks);
    }

    @Test
    void 물어야_할_결정과_보류된_결정을_나눠_적고_보류는_무엇을_기다리는지_알린다() throws Exception {
        ObjectNode args = complete();
        ((ObjectNode) args.get("values")).put("numBuildings", 26);
        ((ObjectNode) args.get("values")).remove("travelTimeMode");
        JsonNode out = call(args);
        String blocks = out.path("blocks").toString();
        assertTrue(blocks.contains("물어야 할 결정 travelTimeMode"), blocks);
        assertTrue(blocks.contains("보류된 결정 zoneAssignmentRule — travelTimeMode 이(가) 정해져야"), blocks);
        assertTrue(blocks.indexOf("물어야 할 결정 travelTimeMode") < blocks.indexOf("보류된 결정"),
                "물어야 할 결정을 먼저 적어야 LLM 이 그것부터 묻는다");
    }

    @Test
    void 출처를_적지_않은_값은_승인하지_않은_기본값으로_보인다() throws Exception {
        ObjectNode args = complete();
        ((ObjectNode) args.get("origins")).remove("truckType");
        ((ObjectNode) args.get("origins")).remove("days");
        JsonNode out = call(args);
        assertTrue(out.path("valid").asBoolean(), out.path("blocks").toString());
        assertTrue(out.path("unapprovedDefaults").has("truckType"));
        assertTrue(out.path("unapprovedDefaults").has("days"));
        assertEquals(2, out.path("unapprovedDefaults").size());
    }

    @Test
    void 모르는_출처는_거절한다() {
        ObjectNode args = complete();
        ((ObjectNode) args.get("origins")).put("truckType", "기본값");
        args.put("request", "시험 요청");
        ToolResult r = build.call(args);
        assertFalse(r.ready());
        assertTrue(r.errors().toString().contains("MODEL_DEFAULT"), "규칙을 함께 알려야 한다");
    }

    @Test
    void 출처는_대소문자를_가리지_않는다() throws Exception {
        ObjectNode args = complete();
        ((ObjectNode) args.get("origins")).put("truckType", "model_default");
        JsonNode out = call(args);
        assertTrue(out.path("unapprovedDefaults").has("truckType"));
    }

    @Test
    void 기본값이_없는_결정은_사용자에게_물으라고_하고_있는_결정은_그_기본값을_알린다() throws Exception {
        ObjectNode args = complete();
        ((ObjectNode) args.get("values")).remove("routeAvailableCapacityKg");
        ((ObjectNode) args.get("values")).remove("truckType");
        String blocks = call(args).path("blocks").toString();
        assertTrue(blocks.contains("routeAvailableCapacityKg") && blocks.contains("기본값이 없습니다"), blocks);
        assertTrue(blocks.contains("예시 값을 대신 넣지 마십시오"), "질문 문구의 예시를 기본값처럼 넣지 않게 한다");
        assertTrue(blocks.contains("기본값 LARGE_5TON"), "기본값이 있으면 그 값을 알려야 한다: " + blocks);
    }

    @Test
    void plan_subtasks_는_비교_값마다_계산해_합친다() throws Exception {
        PlanSubtasksTool plan = new PlanSubtasksTool(new SubtaskPlanner(catalog), mapper);
        ObjectNode args = mapper.createObjectNode();
        ObjectNode a = args.putObject("answers");
        a.put("numBuildings", 4);
        args.put("variableAnswerKey", "truckCount");
        args.putArray("variableValues").add(1).add(3);
        JsonNode out = mapper.readTree(plan.call(args).result().toString());

        JsonNode dispatch = null, trucks = null;
        for (JsonNode i : out.path("instances")) {
            if (i.path("answerKey").asText().equals("dispatchIntervalMinutes")) dispatch = i;
            if (i.path("answerKey").asText().equals("truckCount")) trucks = i;
        }
        assertEquals("UNFILLED", dispatch.path("status").asText(), "3대 실행에서 필요하면 한 번에 물어야 한다");
        assertEquals("[3]", out.path("onlyFor").path("dispatchIntervalMinutes").toString());
        assertEquals("FILLED", trucks.path("status").asText(), "실험 변수 자체는 비교 값으로 채워진 것이다");
        assertTrue(out.path("mustAskNoDefault").toString().contains("routeAvailableCapacityKg"));
        assertFalse(out.path("mustAskNoDefault").toString().contains("truckType"), "기본값이 있는 결정은 넣지 않는다");
    }

    @Test
    void 변수_없이_부르면_예전처럼_한_번_계산한다() throws Exception {
        PlanSubtasksTool plan = new PlanSubtasksTool(new SubtaskPlanner(catalog), mapper);
        ObjectNode args = mapper.createObjectNode();
        args.putObject("answers").put("numBuildings", 4).put("truckCount", 1);
        JsonNode out = mapper.readTree(plan.call(args).result().toString());
        assertEquals(0, out.path("onlyFor").size());
        assertTrue(mapper.readTree(plan.inputSchemaJson()).isObject(), "스키마가 올바른 JSON 이어야 tools/list 가 깨지지 않는다");
    }

    @Test
    void 설명에_출처_규칙이_있다() {
        assertTrue(build.description().contains("MODEL_DEFAULT"));
        assertTrue(build.description().contains("기본값으로"));
    }
}
