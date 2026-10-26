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
    void 카드의_SES_식별자는_템플릿과_같다() throws Exception {
        TemplateCatalog catalog = new TemplateCatalog();
        JsonNode digest = card().path("sesDigest");
        assertEquals(catalog.sesId(), digest.path("sesId").asText());
        assertEquals(catalog.sesVersion(), digest.path("sesVersion").asText());
    }
}
