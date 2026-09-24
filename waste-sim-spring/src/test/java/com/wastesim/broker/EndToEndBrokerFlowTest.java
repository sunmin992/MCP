package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.mcp.ses.BuildScenarioTool;
import com.wastesim.mcp.ses.GetCapabilityTool;
import com.wastesim.mcp.ses.GetTemplatesTool;
import com.wastesim.mcp.ses.PlanSubtasksTool;
import com.wastesim.mcp.ses.ScenarioStore;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요청에서 서버 선택까지, 그리고 고른 서버로 그대로 이어지는가 — 명세 2·3·4 → 5·6·9·10.
 *
 * <p>새 기능을 만들지 않고 계약이 성립하는지만 본다. 마지막 시험이 계획 2 전체의 요점이다 —
 * <b>후보가 하나뿐일 때는 물을 수 없던 질문</b>이기 때문이다. 무엇을 고르든 정답이었다.
 */
class EndToEndBrokerFlowTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CapabilityCardLoader cardLoader = new CapabilityCardLoader();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final CandidateRegistry registry =
            new CandidateRegistry(cardLoader.rawJson(), "classpath*:/mcp/candidates/*.json");
    private final FindSimulatorsTool find =
            new FindSimulatorsTool(new CandidateMatcher(registry), mapper);

    private JsonNode ok(com.wastesim.mcp.McpToolProvider tool, JsonNode args) throws Exception {
        var r = tool.call(args);
        assertTrue(r.ready(), tool.toolName() + " 이 거절했다: " + r);
        return mapper.readTree(r.result().toString());
    }

    /** 명세 1단계 발화에서 LLM 이 뽑은 프로필. */
    private JsonNode 장량동_조회() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "쓰레기수거");
        args.put("spatialScale", "한 동네");
        args.putArray("environmentConditions").add("평일 교통량");
        args.put("objective", "민원이 가장 적은 수거 시각");
        return ok(find, args);
    }

    @Test
    void 요청에서_고른_서버로_시뮬레이션까지_이어진다() throws Exception {
        // 3·4단계 — 브로커가 고른다.
        JsonNode top = 장량동_조회().path("matches").get(0);
        String 고른서버 = top.path("serverId").asText();
        assertEquals("jangnyang-waste-sim", 고른서버);

        // 고른 서버의 카드를 실제로 꺼낼 수 있어야 한다. 못 꺼내면 고른 의미가 없다.
        JsonNode 카드 = registry.byServerId(고른서버).orElseThrow();
        assertFalse(카드.path("fictional").asBoolean(), "지어낸 서버로는 실행까지 갈 수 없다");
        assertEquals(카드, ok(new GetCapabilityTool(cardLoader, mapper), mapper.createObjectNode()),
                "브로커가 내준 카드와 그 서버가 스스로 내는 카드가 다르면 무엇을 보고 고른 것인지 알 수 없다");

        // 5단계 — 그 서버의 템플릿.
        JsonNode 템플릿 = ok(new GetTemplatesTool(catalog, mapper), mapper.createObjectNode());
        assertEquals(14, 템플릿.path("templates").size());

        // 6단계 — 답변으로 서브태스크 집합.
        var planArgs = mapper.createObjectNode();
        var answers = planArgs.putObject("answers");
        answers.put("truckType", "SMALL_1TON");
        answers.put("truckCount", 3);
        answers.put("numBuildings", 4);
        answers.put("residentsPerBuilding", 25);
        answers.put("days", 30);
        answers.put("seeds", 5);
        answers.put("trafficMode", "APPLY");
        answers.put("trafficProfileId", "jangryang-weekday");
        answers.put("travelTimeMode", "LEGACY_CONSTANT");
        answers.put("zoneAssignmentRule", "ROUND_ROBIN");
        answers.put("dispatchIntervalMinutes", 15);
        answers.put("routeAvailableCapacityKg", 150.0);
        JsonNode plan = ok(new PlanSubtasksTool(new SubtaskPlanner(catalog), mapper), planArgs);
        assertEquals(0, plan.path("counts").path("DEFERRED").asInt(),
                "보류가 남으면 조건이 나중에 참이 되어도 영영 묻지 않는다");

        // 9·10단계 — 시나리오. 요청의 목적(수거 시각 비교)이 실험 변수가 된다.
        ScenarioBuilder builder = new ScenarioBuilder(
                new PesFlattener(catalog), new PesBackVerifier(catalog),
                new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));
        var buildArgs = mapper.createObjectNode();
        buildArgs.set("values", answers);
        buildArgs.put("variableAnswerKey", "collectionTimeMinutes");
        buildArgs.putArray("variableValues").add(360).add(720).add(1080);
        JsonNode scenario = ok(
                new BuildScenarioTool(builder, new ScenarioStore(), catalog, mapper), buildArgs);

        assertTrue(scenario.path("valid").asBoolean(), "막힌 사유: " + scenario.path("blocks"));
        assertEquals(3, scenario.path("runCount").asInt());
        assertEquals("UNCONFIRMED", scenario.path("state").asText(),
                "브로커를 거쳐 왔어도 확인은 사람이 한다");
    }

    @Test
    void 교통을_요구하면_교통을_못_하는_후보가_뒤로_간다() throws Exception {
        JsonNode matches = 장량동_조회().path("matches");
        assertEquals(2, matches.size());
        assertEquals("district-waste-sim", matches.get(1).path("serverId").asText());
        assertTrue(String.join(" ", 목록(matches.get(1).path("mismatches"))).contains("교통"),
                "왜 2등인지 말하지 않으면 사용자가 그 서버를 고집할 때 답할 수 없다");
    }

    // ── 이것이 계획 2 전체의 요점이다 ─────────────────────────────────────────

    @Test
    void 도메인이_다른_요청에는_다른_서버가_나온다() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "상수도 누수");
        args.put("objective", "누수 지점을 찾고 싶다");
        JsonNode out = ok(find, args);

        List<String> ids = 목록(out.path("matches"), "serverId");
        assertEquals(List.of("water-leak-sim"), ids);
        assertFalse(ids.contains("jangnyang-waste-sim"),
                "후보가 하나뿐일 때는 이 질문을 할 수 없었다 — 무엇을 고르든 장량동이었다");
    }

    @Test
    void 아무_후보도_없으면_오류가_아니라_사유를_낸다() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "교통 신호 최적화");
        JsonNode out = ok(find, args);
        assertEquals(0, out.path("matchCount").asInt());
        assertFalse(out.path("note").asText().isBlank());
    }

    private List<String> 목록(JsonNode arrayNode) {
        List<String> out = new java.util.ArrayList<>();
        arrayNode.forEach(n -> out.add(n.asText()));
        return out;
    }

    private List<String> 목록(JsonNode arrayNode, String field) {
        List<String> out = new java.util.ArrayList<>();
        arrayNode.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }
}
