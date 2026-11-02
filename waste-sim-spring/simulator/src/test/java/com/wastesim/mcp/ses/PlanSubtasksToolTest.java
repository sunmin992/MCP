package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.plan.PlanRounds;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanSubtasksToolTest {

    private final ObjectMapper m = new ObjectMapper();
    private final PlanSubtasksTool tool = new PlanSubtasksTool(
            new SubtaskPlanner(new TemplateCatalog(), new AnswerNormalizer()), m,
            new PlanRounds(new TemplateCatalog(), new CapabilityCardLoader(), m));

    private JsonNode instance(String json, String key) throws Exception {
        JsonNode r = m.readTree((String) tool.call(m.readTree(json)).result());
        for (JsonNode i : r.get("instances")) if (key.equals(i.get("answerKey").asText())) return i;
        throw new AssertionError(key);
    }

    @Test
    void plan_subtasks_가_발화와_근거를_받는다() throws Exception {
        JsonNode i = instance("{\"answers\":{\"numBuildings\":4},\"origins\":{\"numBuildings\":\"USER\"},"
                + "\"userUtterances\":[\"건물 4동\"],\"evidence\":{}}", "numBuildings");
        assertEquals("UNFILLED", i.get("status").asText());
        assertEquals("NO_EVIDENCE", i.get("reason").get("code").asText());
        JsonNode ok = instance("{\"answers\":{\"numBuildings\":4},\"origins\":{\"numBuildings\":\"USER\"},"
                + "\"userUtterances\":[\"건물 4동\"],\"evidence\":{\"numBuildings\":\"건물 4동\"}}", "numBuildings");
        assertEquals("VERIFIED", ok.get("evidence").asText());
        JsonNode none = instance("{\"answers\":{\"numBuildings\":4},\"origins\":{\"numBuildings\":\"USER\"}}", "numBuildings");
        assertEquals("NOT_CHECKED", none.get("evidence").asText());
    }

    private JsonNode call(String json) throws Exception {
        return m.readTree((String) tool.call(m.readTree(json)).result());
    }

    @Test
    void planId_없이_부르면_발급하고_이어_부르면_단계가_오른다() throws Exception {
        JsonNode first = call("{\"answers\":{}}");
        String id = first.get("planId").asText();
        assertTrue(id.startsWith("plan-"));
        assertEquals(1, first.get("round").asInt());
        for (String f : new String[]{"changes", "completedAtRound", "minRounds"}) assertTrue(first.has(f), f);
        JsonNode next = call("{\"planId\":\"" + id + "\",\"answers\":{\"truckType\":\"LARGE_5TON\"}}");
        assertEquals(id, next.get("planId").asText());
        assertEquals(2, next.get("round").asInt());
        assertFalse(next.get("changes").isEmpty());
    }

    @Test
    void 근거만_달라지면_recheck() throws Exception {
        String base = "\"answers\":{\"numBuildings\":4},\"origins\":{\"numBuildings\":\"USER\"}";
        String id = call("{" + base + "}").get("planId").asText();
        JsonNode again = call("{\"planId\":\"" + id + "\"," + base
                + ",\"userUtterances\":[\"건물 4동\"],\"evidence\":{\"numBuildings\":\"건물 4동\"}}");
        assertEquals(1, again.get("round").asInt());
        assertTrue(again.get("changes").isEmpty());
    }

    @Test
    void 모르는_planId_는_planId_칸으로_거절한다() throws Exception {
        var r = tool.call(m.readTree("{\"planId\":\"plan-000000000000\",\"answers\":{}}"));
        assertFalse(r.ready());
        assertEquals("planId", r.errors().get(0).field());
        assertEquals(PlanRounds.UNKNOWN, r.errors().get(0).message());
    }
}
