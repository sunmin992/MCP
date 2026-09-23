package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 확인 토큰이 <b>실행 지점에서 강제되는가</b>.
 *
 * <p>인계 문서 §4(a) 가 지적한 구멍이다. 토큰을 발급하고 대조하는 데까지만 가면
 * 검증 안 된 설정으로 실행될 수 있는 경로가 남는다 — 토큰은 사용자가 확인한 설정과
 * 실제 실행 설정이 같음을 묶어 주는 것이므로, 검사하는 자리가 없으면 아무것도
 * 보장하지 못한다.
 */
class RunScenarioByTokenToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final ScenarioStore store = new ScenarioStore();
    private final ScenarioBuilder builder = new ScenarioBuilder(
            new PesFlattener(catalog), new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));
    private final BuildScenarioTool buildTool =
            new BuildScenarioTool(builder, store, catalog, mapper);
    private final ConfirmScenarioTool confirmTool =
            new ConfirmScenarioTool(builder, store, mapper);
    private final RunScenarioByTokenTool runTool = new RunScenarioByTokenTool(
            builder, store, new SimulationEngine(new TrafficDataService()), mapper);

    /** 시나리오를 하나 만들고 {scenarioId, confirmToken} 을 돌려준다. */
    private com.fasterxml.jackson.databind.JsonNode built() throws Exception {
        var args = mapper.createObjectNode();
        var values = args.putObject("values");
        values.put("truckType", "SMALL_1TON");
        values.put("truckCount", 1);
        values.put("numBuildings", 4);
        values.put("residentsPerBuilding", 25);
        values.put("days", 10);
        values.put("seeds", 3);
        values.put("trafficMode", "IGNORE");
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(360).add(1080);
        var built = mapper.readTree(buildTool.call(args).result().toString());
        // 사용자가 설정을 보고 동의한 자리.
        var confirmArgs = mapper.createObjectNode();
        confirmArgs.put("scenarioId", built.path("scenarioId").asText());
        return mapper.readTree(confirmTool.call(confirmArgs).result().toString());
    }

    /** 확인하지 않은 채로 보관만 된 시나리오. */
    private String builtButUnconfirmed() throws Exception {
        var args = mapper.createObjectNode();
        var values = args.putObject("values");
        values.put("truckCount", 1);
        values.put("numBuildings", 4);
        values.put("days", 7);
        values.put("seeds", 2);
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(540);
        return mapper.readTree(buildTool.call(args).result().toString())
                .path("scenarioId").asText();
    }

    private ObjectNode runArgs(String id, String token) {
        var a = mapper.createObjectNode();
        a.put("scenarioId", id);
        if (token != null) a.put("confirmToken", token);
        return a;
    }

    @Test
    void 토큰이_맞으면_실행하고_조건마다_결과를_낸다() throws Exception {
        var b = built();
        var out = mapper.readTree(runTool.call(
                runArgs(b.path("scenarioId").asText(), b.path("confirmToken").asText()))
                .result().toString());

        assertEquals(2, out.path("runs").size(), "조건 둘을 다 돌려야 한다");
        for (var run : out.path("runs")) {
            assertTrue(run.has("collectionTimeMinutes"));
            assertTrue(run.has("totalComplaints"));
            assertTrue(run.has("truckUtilizationPercent"));
        }
    }

    @Test
    void 토큰이_틀리면_실행하지_않는다() throws Exception {
        var b = built();
        var result = runTool.call(runArgs(b.path("scenarioId").asText(), "cft-남의토큰"));
        assertFalse(result.ready(), "토큰이 틀린데 실행하면 확인 절차가 아무것도 보장하지 못한다");
        assertTrue(result.toString().contains("토큰"));
    }

    @Test
    void 토큰이_없으면_실행하지_않는다() throws Exception {
        var b = built();
        var result = runTool.call(runArgs(b.path("scenarioId").asText(), null));
        assertFalse(result.ready());
    }

    @Test
    void 확인하지_않은_시나리오는_실행하지_않는다() throws Exception {
        String id = builtButUnconfirmed();
        var result = runTool.call(runArgs(id, "cft-아무거나"));
        assertFalse(result.ready(),
                "검증만 통과한 설정을 돌리면 사용자가 확인하지 않은 실험이 돈다");
        assertTrue(result.toString().contains("확인되지 않은"));
    }

    @Test
    void 확인했지만_실행하지_않으면_CONFIRMED_로_남는다() throws Exception {
        var b = built();
        assertEquals("CONFIRMED", b.path("state").asText());
        assertEquals("CONFIRMED",
                store.entry(b.path("scenarioId").asText()).orElseThrow().state(),
                "확인만 하고 돌리지 않은 것도 상태로 남아야 한다");
    }

    @Test
    void 실행하면_EXECUTED_가_된다() throws Exception {
        var b = built();
        String id = b.path("scenarioId").asText();
        var out = mapper.readTree(runTool.call(
                runArgs(id, b.path("confirmToken").asText())).result().toString());
        assertEquals("EXECUTED", out.path("state").asText());
        assertEquals("EXECUTED", store.entry(id).orElseThrow().state());
    }

    @Test
    void 모르는_시나리오는_실행하지_않는다() {
        var result = runTool.call(runArgs("scn-없는것-1", "cft-아무거나"));
        assertFalse(result.ready());
        assertTrue(result.toString().contains("scn-없는것-1"));
    }

    @Test
    void 보관_이후_설정이_바뀌면_토큰이_무효가_된다() throws Exception {
        var b = built();
        String id = b.path("scenarioId").asText();
        String token = b.path("confirmToken").asText();

        // 사용자가 확인한 뒤 누군가 보관된 설정을 바꾼 상황.
        store.get(id).orElseThrow().runs().get(0).setNumTrucks(9);

        var result = runTool.call(runArgs(id, token));
        assertFalse(result.ready(),
                "설정이 바뀌었는데 옛 토큰으로 실행되면 사용자가 확인한 것과 다른 실험이 돈다");
    }

    @Test
    void 결과에_재현과_한계_정보가_함께_실린다() throws Exception {
        var b = built();
        var out = mapper.readTree(runTool.call(
                runArgs(b.path("scenarioId").asText(), b.path("confirmToken").asText()))
                .result().toString());

        // 능력 카드의 alwaysAttachToResult — "무엇으로 계산한 값인가" 를 결과만 보고 알아야 한다.
        for (var run : out.path("runs")) {
            assertTrue(run.has("seed"), "재현 정보가 없으면 같은 결과를 다시 낼 수 없다");
            assertTrue(run.has("massBalanceErrorKg"));
            assertTrue(run.has("dataQualityFlags"));
            assertTrue(run.has("assumptionNotes"));
        }
        assertTrue(out.path("notForOperationalUse").asBoolean(),
                "설정 간 비교이지 운영 예측이 아니라는 사실이 결과에 남아야 한다");
    }

    @Test
    void 스키마가_토큰을_필수로_요구한다() throws Exception {
        var schema = mapper.readTree(runTool.inputSchemaJson());
        assertEquals("run_scenario_by_token", runTool.toolName());
        var required = mapper.convertValue(schema.path("required"), java.util.List.class);
        assertTrue(required.contains("scenarioId"));
        assertTrue(required.contains("confirmToken"),
                "스키마가 토큰을 선택으로 두면 LLM 이 토큰 없이 호출을 만든다");
    }
}
