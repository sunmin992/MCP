package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanSubtasksToolTest {

    private final ObjectMapper m = new ObjectMapper();
    private final PlanSubtasksTool tool = new PlanSubtasksTool(
            new SubtaskPlanner(new TemplateCatalog(), new AnswerNormalizer()), m);

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
}
