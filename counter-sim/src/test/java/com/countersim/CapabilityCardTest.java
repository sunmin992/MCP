package com.countersim;

import com.countersim.template.SubtaskTemplate;
import com.countersim.template.TemplateCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCardTest {

    private JsonNode card() throws Exception {
        try (var input = getClass().getResourceAsStream("/mcp/counter-capability-card.json")) {
            assertNotNull(input);
            return new ObjectMapper().readTree(input);
        }
    }

    @Test
    void 카드의_결정_지점은_템플릿과_같은_설정_항목을_가리킨다() throws Exception {
        Set<String> fields = new HashSet<>();
        for (JsonNode dp : card().path("sesDigest").path("decisionPoints")) fields.add(dp.path("field").asText());
        Set<String> configFields = new HashSet<>();
        for (SubtaskTemplate t : new TemplateCatalog().all()) configFields.add(t.configField());
        assertEquals(configFields, fields);
    }

    @Test
    void 목적_이름표는_자기_키에_걸린다() throws Exception {
        // 브로커는 맞지 않는 목적을 고치라며 이 이름표들을 보기로 내놓는다. LLM 이 이름표를 그대로 다시 보내면
        // 걸려야 한다 — 걸리지 않으면 "보기에 있는 문구인데 거절" 이 된다(2026-10-26 RT-C3).
        for (JsonNode q : card().path("applicability").path("objectiveMatchKeys")) {
            String label = q.path("label").asText();
            boolean hit = false;
            for (JsonNode k : q.path("matchKeys")) hit |= label.contains(k.asText());
            assertTrue(hit, label + " 이 자기 matchKeys 에 걸리지 않는다");
        }
    }

    @Test
    void 카드의_SES_식별자는_템플릿과_같다() throws Exception {
        TemplateCatalog catalog = new TemplateCatalog();
        JsonNode digest = card().path("sesDigest");
        assertEquals(catalog.sesId(), digest.path("sesId").asText());
        assertEquals(catalog.sesVersion(), digest.path("sesVersion").asText());
    }
}
