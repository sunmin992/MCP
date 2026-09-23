package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도구가 이름·스키마를 내고 호출에 답하는가.
 *
 * <p>스키마가 실제 인자와 어긋나면 LLM 이 호출을 만들 수 없다.
 */
class SesMcpToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();

    private BuildScenarioTool buildTool() {
        ScenarioBuilder builder = new ScenarioBuilder(
                new PesFlattener(catalog), new PesBackVerifier(catalog),
                new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));
        return new BuildScenarioTool(builder, new ScenarioStore(), catalog, mapper);
    }

    @Test
    void 능력카드_도구가_카드를_그대로_낸다() throws Exception {
        var tool = new GetCapabilityTool(new CapabilityCardLoader(), mapper);
        assertEquals("get_capability", tool.toolName());
        assertNotNull(mapper.readTree(tool.inputSchemaJson()));
        var result = tool.call(mapper.createObjectNode());
        assertTrue(result.toString().contains("jangnyang-waste-sim"));
    }

    @Test
    void 템플릿_도구가_열네개를_낸다() {
        var tool = new GetTemplatesTool(catalog, mapper);
        assertEquals("get_templates", tool.toolName());
        var result = tool.call(mapper.createObjectNode());
        assertTrue(result.toString().contains("jn.dispatchInterval"));
        assertTrue(result.toString().contains("jn.routeAvailableCapacity"),
                "경로 배정용량이 빠지면 가동률을 물을 수단이 없다");
    }

    @Test
    void 계획_도구가_차량한대면_배차간격을_빼고_낸다() {
        var tool = new PlanSubtasksTool(new SubtaskPlanner(catalog), mapper);
        var args = mapper.createObjectNode();
        var answers = args.putObject("answers");
        answers.put("numBuildings", 4);
        answers.put("truckCount", 1);
        String out = tool.call(args).toString();
        assertTrue(out.contains("NOT_GENERATED"), "생성되지 않은 상태가 보여야 한다");
    }

    @Test
    void 답변검증_도구가_허용밖_값을_거절한다() {
        var tool = new ValidateAnswersTool(catalog, new AnswerNormalizer(), mapper);
        var args = mapper.createObjectNode();
        var answers = args.putObject("answers");
        answers.put("truckType", "MEDIUM_3TON");
        String out = tool.call(args).toString();
        assertTrue(out.contains("OUT_OF_CLOSURE"));
    }

    @Test
    void 모든_도구의_스키마가_유효한_JSON이다() throws Exception {
        var tools = java.util.List.of(
                new GetCapabilityTool(new CapabilityCardLoader(), mapper),
                new GetTemplatesTool(catalog, mapper),
                new PlanSubtasksTool(new SubtaskPlanner(catalog), mapper),
                new ValidateAnswersTool(catalog, new AnswerNormalizer(), mapper),
                buildTool());
        for (var t : tools) {
            var schema = mapper.readTree(t.inputSchemaJson());
            assertEquals("object", schema.path("type").asText(), t.toolName() + " 스키마 타입");
            assertNotNull(t.description(), t.toolName() + " 설명 없음");
        }
    }

    // ── 시나리오 도구 ─────────────────────────────────────────────────────────

    @Test
    void 시나리오_도구가_통과하면_미확인으로_보관한다() throws Exception {
        ScenarioStore store = new ScenarioStore();
        ScenarioBuilder builder = new ScenarioBuilder(
                new PesFlattener(catalog), new PesBackVerifier(catalog),
                new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));
        var tool = new BuildScenarioTool(builder, store, catalog, mapper);

        var args = mapper.createObjectNode();
        var values = args.putObject("values");
        values.put("truckType", "SMALL_1TON");
        values.put("truckCount", 1);
        values.put("numBuildings", 4);
        values.put("days", 30);
        values.put("seeds", 30);
        values.put("trafficMode", "IGNORE");
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(360).add(720).add(1080);

        var out = mapper.readTree(tool.call(args).result().toString());
        assertTrue(out.path("valid").asBoolean(), "막힌 사유: " + out.path("blocks"));
        assertEquals(3, out.path("runCount").asInt());
        assertTrue(out.path("confirmToken").isMissingNode(),
                "검증만으로 토큰을 주면 '사용자가 확인하지 않은 것' 상태가 사라진다");
        assertEquals("UNCONFIRMED", out.path("state").asText());
        assertTrue(store.get(out.path("scenarioId").asText()).isPresent(),
                "통과한 시나리오는 확인·실행 때까지 남아 있어야 한다");
    }

    @Test
    void 시나리오_도구가_범위밖_값에는_토큰을_주지_않는다() throws Exception {
        var args = mapper.createObjectNode();
        var values = args.putObject("values");
        values.put("truckType", "SMALL_1TON");
        values.put("truckCount", 1);
        values.put("numBuildings", 4);
        values.put("trafficMode", "IGNORE");
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(720).add(1500);

        var out = mapper.readTree(buildTool().call(args).result().toString());
        assertFalse(out.path("valid").asBoolean(), "1500분은 0~1439 범위를 벗어난다");
        assertEquals("INVALID", out.path("state").asText(), "막힌 시나리오는 보관하지 않는다");
    }

    @Test
    void 시나리오_도구가_템플릿에_없는_키를_거절한다() {
        var args = mapper.createObjectNode();
        args.putObject("values").put("없는키", 1);
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(720);

        var result = buildTool().call(args);
        assertFalse(result.ready(), "모르는 키를 조용히 무시하면 사용자 값이 실행에 안 실린다");
        assertTrue(result.toString().contains("없는키"));
    }

    @Test
    void 시나리오_도구가_SES_식별자를_카탈로그에서_가져온다() throws Exception {
        var args = mapper.createObjectNode();
        var values = args.putObject("values");
        values.put("truckCount", 1);
        values.put("numBuildings", 4);
        args.put("variableAnswerKey", "days");
        args.putArray("variableValues").add(30);

        var out = mapper.readTree(buildTool().call(args).result().toString());
        assertEquals(catalog.sesId(), out.path("sesId").asText(),
                "식별자를 도구에 적어 두면 템플릿이 바뀌어도 옛 값을 주장한다");
        assertEquals(catalog.sesVersion(), out.path("sesVersion").asText());
    }
}
