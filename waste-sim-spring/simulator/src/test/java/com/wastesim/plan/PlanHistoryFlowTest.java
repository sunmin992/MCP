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
}
