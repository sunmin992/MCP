package com.wastesim.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.TestScenarioRequests;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.controller.ScenarioConfirmController;
import com.wastesim.mcp.ses.BuildScenarioTool;
import com.wastesim.mcp.ses.ConfirmScenarioTool;
import com.wastesim.mcp.ses.PlanSubtasksTool;
import com.wastesim.mcp.ses.RunScenarioByTokenTool;
import com.wastesim.mcp.ses.ScenarioStore;
import com.wastesim.mcp.ses.ValidateAnswersTool;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 한 요청의 plan → build → confirm → run 사건이 파일에 같은 planId 로 이어지는가. 조립은 EndToEndSubtaskFlowTest 와 같다. */
class PlanHistoryFlowTest {

    private final ObjectMapper m = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final ScenarioStore store = new ScenarioStore();
    private final ScenarioBuilder builder = new ScenarioBuilder(
            new PesFlattener(catalog), new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()),
            new SubtaskPlanner(catalog, new AnswerNormalizer()));

    @TempDir Path dir;
    private PlanRounds rounds;

    private PlanRounds rounds() {
        if (rounds == null) rounds = new PlanRounds(catalog, new CapabilityCardLoader(), m, new PlanHistoryWriter(dir));
        return rounds;
    }

    private JsonNode ok(com.wastesim.tool.ToolResult r) throws Exception {
        assertTrue(r.ready(), String.valueOf(r.errors()));
        return m.readTree(r.result().toString());
    }

    private List<JsonNode> lines() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList())
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) out.add(m.readTree(line));
        }
        return out;
    }

    /** 다 찬 요청. 실행을 짧게 하려고 일 수 · 반복을 줄인다. */
    private ObjectNode fullRequest() {
        ObjectNode args = m.createObjectNode();
        ObjectNode values = args.putObject("values");
        values.put("days", 3);
        values.put("seeds", 1);
        args.put("variableAnswerKey", "collectionTimeMinutes");
        args.putArray("variableValues").add(360).add(1080);
        return TestScenarioRequests.completed(args);
    }

    private JsonNode plan(String planId, ObjectNode answers, ObjectNode full) throws Exception {
        ObjectNode a = m.createObjectNode();
        if (planId != null) a.put("planId", planId);
        a.set("answers", answers);
        a.set("origins", full.get("origins"));
        a.set("variableAnswerKey", full.get("variableAnswerKey"));
        a.set("variableValues", full.get("variableValues"));
        return ok(new PlanSubtasksTool(new SubtaskPlanner(catalog, new AnswerNormalizer()), m, rounds()).call(a));
    }

    @Test
    void 검증_기록_생성이_실패하면_미검증_시나리오를_보관하지_않는다() throws Exception {
        var built=ok(new BuildScenarioTool(builder,store,catalog,m,rounds()).call(fullRequest()));
        var entry=store.entry(built.path("scenarioId").asText()).orElseThrow();
        var isolated=new ScenarioStore();
        assertThrows(IllegalStateException.class,()->isolated.putValidated(entry.scenario(),entry.pes(),"request","id",m.createObjectNode(),
                ()->{throw new IllegalStateException("검사 실패");}));
        assertTrue(isolated.entry(entry.scenario().scenarioId()).isEmpty());
    }

    @Test
    void 목적_지표가_실행_결과에_실제로_포함된다() throws Exception {
        ObjectNode full=fullRequest();
        full.putArray("observations").add("meanComplaints");
        var purpose=full.putObject("purpose").put("mode","COMPARE").put("comparisonKey","collectionTimeMinutes");
        purpose.putArray("comparisonValues").add(360).add(1080);
        purpose.putArray("observations").add("meanComplaints");
        var built=ok(new BuildScenarioTool(builder,store,catalog,m,rounds()).call(full));
        String id=built.path("scenarioId").asText();
        var confirmed=ok(new ConfirmScenarioTool(builder,store,m).call(m.createObjectNode().put("scenarioId",id)));
        var result=ok(new RunScenarioByTokenTool(builder,store,new SimulationService(new SimulationEngine(new TrafficDataService())),m,rounds())
                .call(m.createObjectNode().put("scenarioId",id).put("confirmToken",confirmed.path("confirmToken").asText())));
        assertEquals("VERIFIED",result.path("purposeResultCheck").asText());
        assertEquals(2,result.path("runs").size());
        assertTrue(store.validationCurrent(id));
    }

    @Test
    void 실행중_요청이_수정되면_이전_실행의_결과를_채택하지_않는다() throws Exception {
        ObjectNode full=fullRequest().put("requestId","mid-run-change");
        var built=ok(new BuildScenarioTool(builder,store,catalog,m,rounds()).call(full));
        String id=built.path("scenarioId").asText();
        ok(new ConfirmScenarioTool(builder,store,m).call(m.createObjectNode().put("scenarioId",id)));
        var expected=store.entry(id).orElseThrow();
        assertTrue(store.beginExecution(id,expected));
        store.revise("mid-run-change",m.createObjectNode().put("changed",true));
        assertFalse(store.completeExecution(id,expected));
        assertNull(store.entry(id).orElseThrow().executedAt());
        store.endExecution(id);
    }

    @Test
    void 목적_불일치는_실행가능한_설정이라도_저장하지_않는다() throws Exception {
        ObjectNode full=fullRequest();
        full.putArray("observations").add("meanComplaints");
        var purpose=full.putObject("purpose").put("mode","COMPARE").put("comparisonKey","truckCount");
        purpose.putArray("comparisonValues").add(360).add(1080);
        purpose.putArray("observations").add("meanComplaints");
        JsonNode built=ok(new BuildScenarioTool(builder,store,catalog,m,rounds()).call(full));
        assertFalse(built.path("valid").asBoolean());
        assertEquals("MISMATCH",built.path("purposeCheck").path("status").asText());
        assertTrue(store.entry(built.path("scenarioId").asText()).isEmpty());
    }

    @Test
    void 검증한_설정이_바뀌면_이전_승인과_실행을_막는다() throws Exception {
        ObjectNode full=fullRequest();
        full.putArray("observations").add("meanComplaints");
        var purpose=full.putObject("purpose").put("mode","COMPARE").put("comparisonKey","collectionTimeMinutes");
        purpose.putArray("comparisonValues").add(360).add(1080);
        purpose.putArray("observations").add("meanComplaints");
        JsonNode built=ok(new BuildScenarioTool(builder,store,catalog,m,rounds()).call(full));
        assertTrue(built.path("valid").asBoolean(),built.toString());
        assertEquals("SATISFIED",built.path("purposeCheck").path("status").asText());
        String id=built.path("scenarioId").asText();
        var confirm=new ConfirmScenarioTool(builder,store,m);
        var confirmed=ok(confirm.call(m.createObjectNode().put("scenarioId",id)));
        String token=confirmed.path("confirmToken").asText();
        store.entry(id).orElseThrow().scenario().runs().get(0).setDays(12);
        assertFalse(store.validationCurrent(id));
        assertFalse(confirm.call(m.createObjectNode().put("scenarioId",id)).ready());
        assertFalse(new RunScenarioByTokenTool(builder,store,new SimulationService(new SimulationEngine(new TrafficDataService())),m,rounds())
                .call(m.createObjectNode().put("scenarioId",id).put("confirmToken",token)).ready());
    }

    @Test
    void 목적만_변경한_실패한_요청도_이전_승인을_무효화한다() throws Exception {
        ObjectNode full=fullRequest().put("requestId","purpose-revision");
        var tool=new BuildScenarioTool(builder,store,catalog,m,rounds());
        var first=ok(tool.call(full));
        String id=first.path("scenarioId").asText();
        full.putObject("purpose").put("mode","RUN").putArray("observations").add("unsupportedMetric");
        var changed=ok(tool.call(full));
        assertFalse(changed.path("valid").asBoolean());
        assertFalse(store.validationCurrent(id));
    }

    @Test
    void 같은_요청의_실패한_수정도_이전_완료를_무효화한다() throws Exception {
        var tool=new BuildScenarioTool(builder,store,catalog,m,rounds());
        ObjectNode full=fullRequest().put("requestId","request-revision-test");
        JsonNode first=ok(tool.call(full));
        String id=first.path("scenarioId").asText();
        assertTrue(store.validationCurrent(id));
        ((ObjectNode)full.get("values")).put("seeds",0);
        tool.call(full);
        assertFalse(store.validationCurrent(id));
    }

    @Test
    void 목적_지표가_현재_설정에서_측정되지_않으면_막는다() throws Exception {
        ObjectNode full=fullRequest();
        var purpose=m.createObjectNode().put("mode","RUN");
        purpose.putArray("observations").add("avgRouteDurationMinutes");
        var built=ok(new BuildScenarioTool(builder,store,catalog,m,rounds()).call(full));
        var scenario=store.entry(built.path("scenarioId").asText()).orElseThrow().scenario();
        scenario.runs().forEach(c -> { c.setTravelTimeMode("LEGACY_CONSTANT"); c.setRouteTravelMinutes(0); });
        var checked=com.wastesim.readiness.PurposeCheck.evaluate(purpose,"collectionTimeMinutes",List.of(360,1080),
                List.of("avgRouteDurationMinutes"),builder.availableObservations(scenario));
        assertFalse(checked.acceptable());
        assertTrue(checked.reasons().toString().contains("관측할 수 없는"));
    }

    @Test
    void 근거_생략은_완료와_구별하여_보관하고_확인해야_실행한다() throws Exception {
        JsonNode built = ok(new BuildScenarioTool(builder, store, catalog, m, rounds()).call(fullRequest()));
        assertTrue(built.path("valid").asBoolean(), built.toString());
        assertTrue(built.path("taskReadiness").path("ready").asBoolean());
        assertEquals("NOT_CHECKED", built.path("evidenceStatus").asText());
        String id = built.path("scenarioId").asText();
        var entry = store.entry(id).orElseThrow();
        assertEquals("UNCONFIRMED", entry.state());
        assertNull(entry.scenario().confirmToken());
        assertFalse(entry.pes().reviewRequiredValues().isEmpty());
        assertTrue(entry.pes().evidenceChecks().values().stream().allMatch("NOT_CHECKED"::equals));
        assertEquals("USER", entry.pes().origins().get("truckType"));
    }

    @Test
    void 한_요청의_사건이_순서대로_쌓인다() throws Exception {
        ObjectNode full = fullRequest();
        ObjectNode partial = m.createObjectNode().put("truckType", "LARGE_5TON");
        JsonNode r1 = plan(null, partial, full);
        String planId = r1.get("planId").asText();
        JsonNode r2 = plan(planId, (ObjectNode) full.get("values"), full);
        assertEquals(2, r2.get("round").asInt());
        assertTrue(r2.get("complete").asBoolean(), r2.toString());

        full.put("planId", planId);
        JsonNode built = ok(new BuildScenarioTool(builder, store, catalog, m, rounds()).call(full));
        String scenarioId = built.get("scenarioId").asText();
        assertEquals(planId, store.planId(scenarioId).orElseThrow());

        var controller = new ScenarioConfirmController(store, new ConfirmScenarioTool(builder, store, m), m,
                "127.0.0.1", catalog, rounds());
        assertEquals(200, controller.confirm(scenarioId).getStatusCode().value());

        ObjectNode run = m.createObjectNode().put("scenarioId", scenarioId)
                .put("confirmToken", store.entry(scenarioId).orElseThrow().scenario().confirmToken());
        ok(new RunScenarioByTokenTool(builder, store,
                new SimulationService(new SimulationEngine(new TrafficDataService())), m, rounds()).call(run));

        List<JsonNode> lines = lines();
        assertEquals(List.of("round", "round", "build", "confirm", "run"),
                lines.stream().map(l -> l.get("event").asText()).toList());
        for (JsonNode l : lines) {
            for (String f : List.of("ts", "planId", "matchId", "serverId", "sesId", "sesVersion", "event", "round"))
                assertTrue(l.has(f), f + " 없음: " + l);
            assertEquals(planId, l.get("planId").asText());
        }
        assertEquals(List.of(1, 2, 2, 2, 2), lines.stream().map(l -> l.get("round").asInt()).toList());
        JsonNode build = lines.get(2);
        assertEquals(scenarioId, build.get("scenarioId").asText());
        assertTrue(build.get("valid").asBoolean());
        for (String f : List.of("blocks", "backVerificationBlocks", "branchBlocks"))
            assertEquals(0, build.get(f).asInt(), f);
        assertTrue(build.has("droppedDefaults") && build.has("guardUnverified"), build.toString());
        assertTrue(lines.get(3).get("approved").asBoolean());
        assertEquals(scenarioId, lines.get(3).get("scenarioId").asText());
        assertTrue(lines.get(4).get("success").asBoolean());
        assertEquals(scenarioId, lines.get(4).get("scenarioId").asText());
        // 메모리 기록도 같은 순서다.
        assertEquals(5, rounds().history(planId).orElseThrow().size());
    }

    @Test
    void validate_answers_에_planId_를_주면_validate_사건() throws Exception {
        String planId = plan(null, m.createObjectNode(), fullRequest()).get("planId").asText();
        var tool = new ValidateAnswersTool(catalog, new AnswerNormalizer(), m, rounds());

        JsonNode r = ok(tool.call(m.readTree("{\"planId\":\"" + planId + "\",\"answers\":{\"truckCount\":\"3\",\"truckType\":\"X\"}}")));
        assertFalse(r.has("planIdUnknown"));
        JsonNode v = lines().get(1);
        assertEquals("validate", v.get("event").asText());
        assertEquals(planId, v.get("planId").asText());
        assertEquals(1, v.get("round").asInt());
        assertEquals(3, v.get("normalized").get("truckCount").asInt());
        assertEquals("truckType", v.get("rejected").get(0).get("answerKey").asText());

        // 모르는 planId 면 planId 없이 부른 것과 같고, 사건을 붙이지 않고 그 사실만 알린다.
        JsonNode unknown = ok(tool.call(m.readTree("{\"planId\":\"plan-000000000000\",\"answers\":{\"truckCount\":\"3\"}}")));
        assertTrue(unknown.get("planIdUnknown").asBoolean());
        assertEquals(2, lines().size());
    }

    @Test
    void build_scenario_에_모르는_planId_를_주면_사건_없이_만든다() throws Exception {
        ObjectNode full = fullRequest();
        full.put("planId", "plan-000000000000");
        JsonNode built = ok(new BuildScenarioTool(builder, store, catalog, m, rounds()).call(full));
        assertTrue(built.get("planIdUnknown").asBoolean());
        assertTrue(built.get("valid").asBoolean());
        assertTrue(store.planId(built.get("scenarioId").asText()).isEmpty());
        assertTrue(lines().isEmpty());
    }

    @Test
    void 모르는_시나리오의_확인은_planId_null_로_파일에만_남는다() throws Exception {
        var controller = new ScenarioConfirmController(store, new ConfirmScenarioTool(builder, store, m), m,
                "127.0.0.1", catalog, rounds());
        assertEquals(400, controller.confirm("sc-없음").getStatusCode().value());
        JsonNode l = lines().get(0);
        assertEquals("confirm", l.get("event").asText());
        assertTrue(l.get("planId").isNull());
        assertTrue(l.get("round").isNull());
        assertEquals("sc-없음", l.get("scenarioId").asText());
        assertFalse(l.get("approved").asBoolean());
        assertEquals(catalog.sesId(), l.get("sesId").asText());
    }

    @Test
    void 근거_검사로_거절된_build_도_사건으로_남는다() throws Exception {
        ObjectNode full = fullRequest();
        String planId = plan(null, (ObjectNode) full.get("values"), full).get("planId").asText();
        full.put("planId", planId);
        ((ObjectNode) full.get("origins")).put("days", "USER");
        full.putArray("userUtterances").add("아무 말");
        full.putObject("evidence");
        var r = new BuildScenarioTool(builder, store, catalog, m, rounds()).call(full);
        assertFalse(r.ready());
        var h = rounds().history(planId).orElseThrow();
        JsonNode last = h.get(h.size() - 1);
        assertEquals("build", last.get("event").asText());
        assertFalse(last.get("valid").asBoolean());
        assertEquals("GUARD", last.get("rejectedBy").asText());
        assertTrue(last.get("guardBlocks").asInt() >= 1, last.toString());
        assertEquals(planId, lines().get(lines().size() - 1).get("planId").asText());
    }

    @Test
    void 요청_원문이_없어_거절된_build_도_사건으로_남는다() throws Exception {
        ObjectNode full = fullRequest();
        String planId = plan(null, (ObjectNode) full.get("values"), full).get("planId").asText();
        full.put("planId", planId);
        full.remove("request");
        assertFalse(new BuildScenarioTool(builder, store, catalog, m, rounds()).call(full).ready());
        var h = rounds().history(planId).orElseThrow();
        assertEquals("REQUEST", h.get(h.size() - 1).get("rejectedBy").asText());
        assertFalse(h.get(h.size() - 1).has("guardBlocks"));
    }

    @Test
    void 밀려난_planId_의_확인은_planId_를_그대로_남긴다() throws Exception {
        rounds().attachOrWrite("plan-밀려남0000", m.createObjectNode().put("event", "confirm").put("scenarioId", "sc-1"));
        JsonNode l = lines().get(0);
        assertEquals("plan-밀려남0000", l.get("planId").asText());
        assertTrue(l.get("round").isNull());
    }
}
