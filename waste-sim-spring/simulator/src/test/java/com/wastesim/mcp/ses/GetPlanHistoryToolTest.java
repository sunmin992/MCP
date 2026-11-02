package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.plan.PlanRounds;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetPlanHistoryToolTest {

    private final ObjectMapper m = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final PlanRounds rounds = new PlanRounds(catalog, new CapabilityCardLoader(), m);
    private final GetPlanHistoryTool tool = new GetPlanHistoryTool(rounds);

    @Test
    void 기록을_순서대로_낸다() throws Exception {
        var across = new SubtaskPlanner(catalog, new AnswerNormalizer()).planAcross(Map.of(), Map.of(), null, List.of(), null);
        var step = rounds.record(null, null, null, m.createObjectNode(), m.createObjectNode(), null,
                m.createArrayNode(), across.plan(), across.waitingOn(), across.minRounds());
        ObjectNode build = m.createObjectNode();
        build.put("event", "build");
        rounds.attach(step.planId(), build);

        var r = tool.call(m.createObjectNode().put("planId", step.planId()));
        assertTrue(r.ready());
        JsonNode out = m.readTree((String) r.result());
        assertEquals(step.planId(), out.get("planId").asText());
        assertEquals(2, out.get("events").size());
        assertEquals("round", out.get("events").get(0).get("event").asText());
        assertEquals("build", out.get("events").get(1).get("event").asText());
    }

    @Test
    void 모르는_planId_는_안내한다() {
        for (JsonNode args : List.of(m.createObjectNode().put("planId", "plan-nope"), m.createObjectNode(),
                m.createObjectNode().put("planId", " "))) {
            var r = tool.call(args);
            assertFalse(r.ready());
            assertEquals("planId", r.errors().get(0).field());
            assertEquals("기록이 메모리에 없습니다 — logs/plan-history 파일을 보십시오", r.errors().get(0).message());
        }
    }
}
