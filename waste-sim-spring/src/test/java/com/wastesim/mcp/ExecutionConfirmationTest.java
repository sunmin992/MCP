package com.wastesim.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.ses.BuildScenarioTool;
import com.wastesim.mcp.ses.ConfirmScenarioTool;
import com.wastesim.mcp.ses.ScenarioStore;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 기존 실행 도구가 토큰을 어떻게 다루는가.
 *
 * <p>이 셋({@code run_waste_simulation}·{@code run_scenario}·{@code update_route_sequence})은
 * 서브태스크 흐름 <b>밖에서도</b> 쓰인다. 토큰을 무조건 요구하면 그 경로가 전부 막히므로
 * 막지 않는다 — 대신 확인을 거쳤는지를 결과에 남긴다. "무엇으로 계산한 값인가" 를 결과가
 * 말하게 하는 것과 같은 방식이다.
 *
 * <p>다만 <b>틀린 토큰은 통과시키지 않는다.</b> 토큰을 줬다는 것은 확인을 주장한 것이고,
 * 틀린 주장을 통과시키면 확인 절차가 아무것도 보장하지 못한다.
 */
class ExecutionConfirmationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final ScenarioStore store = new ScenarioStore();
    private final ScenarioBuilder builder = new ScenarioBuilder(
            new PesFlattener(catalog), new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));
    private final ExecutionConfirmation gate = new ExecutionConfirmation(store, builder);

    /** 시나리오를 하나 만들고 확인까지 마친 뒤 토큰을 돌려준다. */
    private String 확인된_토큰() throws Exception {
        var build = new BuildScenarioTool(builder, store, catalog, mapper);
        var args = mapper.createObjectNode();
        var values = args.putObject("values");
        values.put("truckCount", 1);
        values.put("numBuildings", 4);
        values.put("days", 7);
        values.put("seeds", 2);
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(720);
        String id = mapper.readTree(build.call(args).result().toString()).path("scenarioId").asText();

        var confirm = new ConfirmScenarioTool(builder, store, mapper);
        return mapper.readTree(confirm.call(mapper.createObjectNode().put("scenarioId", id))
                .result().toString()).path("confirmToken").asText();
    }

    @Test
    void 토큰이_없으면_막지_않고_확인_안됨으로_표시한다() {
        var check = gate.check(mapper.createObjectNode());
        assertTrue(check.allowed(), "서브태스크 흐름 밖 호출을 막으면 기존 화면과 API 가 전부 멈춘다");
        assertFalse(check.confirmed());
        assertFalse(check.note().isBlank(), "표시만 하고 사유가 없으면 결과를 읽는 사람이 뜻을 모른다");
    }

    @Test
    void 맞는_토큰이면_확인됨으로_표시한다() throws Exception {
        var args = mapper.createObjectNode().put("confirmToken", 확인된_토큰());
        var check = gate.check(args);
        assertTrue(check.allowed());
        assertTrue(check.confirmed());
        assertFalse(check.scenarioId().isBlank(), "어느 시나리오의 확인인지 남아야 한다");
    }

    @Test
    void 모르는_토큰이면_실행하지_않는다() {
        var check = gate.check(mapper.createObjectNode().put("confirmToken", "cft-남의토큰"));
        assertFalse(check.allowed(),
                "토큰을 줬다는 것은 확인을 주장한 것이다 — 틀린 주장을 통과시키면 확인이 무의미해진다");
        assertTrue(check.note().contains("토큰"));
    }

    @Test
    void 확인_이후_설정이_바뀌면_실행하지_않는다() throws Exception {
        String token = 확인된_토큰();
        store.byToken(token).orElseThrow().scenario().runs().get(0).setNumTrucks(9);

        var check = gate.check(mapper.createObjectNode().put("confirmToken", token));
        assertFalse(check.allowed(),
                "실행 직전에 다시 세지 않으면 사용자가 확인한 것과 다른 설정이 돈다");
    }

    @Test
    void 빈_토큰_문자열은_토큰이_없는_것으로_본다() {
        var check = gate.check(mapper.createObjectNode().put("confirmToken", "   "));
        assertTrue(check.allowed());
        assertFalse(check.confirmed());
    }
}
