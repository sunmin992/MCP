package com.wastesim.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 결정 간 조건 참조(active)와 카드 조건 설명 생성. */
class SesActiveConditionTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static JsonNode j(String s) throws Exception { return M.readTree(s); }

    @Test void activeExpandsToReferencedConditionAndSplicesSameOperator() throws Exception {
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        raw.put("zone", j("{\"all\":[{\"eq\":[\"travel\",\"ZONE\"]},{\"gt\":[\"n\",4]}]}"));
        raw.put("intra", j("{\"all\":[{\"active\":\"zone\"},{\"eq\":[\"zone\",\"CONTIGUOUS\"]}]}"));
        assertEquals(j("{\"all\":[{\"eq\":[\"travel\",\"ZONE\"]},{\"gt\":[\"n\",4]},{\"eq\":[\"zone\",\"CONTIGUOUS\"]}]}"),
                SesDerivation.expand(raw.get("intra"), raw, new ArrayDeque<>(List.of("intra"))));
    }

    @Test void changingReferencedConditionChangesDependent() throws Exception {
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        raw.put("zone", j("{\"eq\":[\"travel\",\"ZONE\"]}"));
        raw.put("intra", j("{\"all\":[{\"active\":\"zone\"},{\"eq\":[\"zone\",\"CONTIGUOUS\"]}]}"));
        assertEquals(j("{\"all\":[{\"eq\":[\"travel\",\"ZONE\"]},{\"eq\":[\"zone\",\"CONTIGUOUS\"]}]}"),
                SesDerivation.expand(raw.get("intra"), raw, new ArrayDeque<>(List.of("intra"))));
    }

    @Test void rejectsUnknownAlwaysAndCyclicReferences() throws Exception {
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        raw.put("a", j("{\"active\":\"b\"}"));
        raw.put("b", j("{\"active\":\"a\"}"));
        raw.put("c", j("\"ALWAYS\""));
        raw.put("d", j("{\"active\":\"c\"}"));
        raw.put("e", j("{\"active\":\"nope\"}"));
        for (String k : List.of("a", "d", "e"))
            assertThrows(IllegalArgumentException.class, () -> SesDerivation.expand(raw.get(k), raw, new ArrayDeque<>(List.of(k))), k);
    }

    @Test void rendersConditionForCard() throws Exception {
        assertEquals("numBuildings >= 2 && truckCount >= 2",
                SesDerivation.render(j("{\"all\":[{\"gte\":[\"numBuildings\",2]},{\"gte\":[\"truckCount\",2]}]}")));
        assertEquals("!given(collectionTimesMinutes)", SesDerivation.render(j("{\"not\":{\"given\":\"collectionTimesMinutes\"}}")));
        assertEquals("a == X || (b > 1 && c != Y)",
                SesDerivation.render(j("{\"any\":[{\"eq\":[\"a\",\"X\"]},{\"all\":[{\"gt\":[\"b\",1]},{\"ne\":[\"c\",\"Y\"]}]}]}")));
        assertNull(SesDerivation.render(j("\"ALWAYS\"")));
    }

    @Test void cardFieldsComeFromDecisionNotHandWritten() throws Exception {
        ObjectNode ses = (ObjectNode) M.readTree(SesActiveConditionTest.class.getResourceAsStream(SesTree.DEFAULT_RESOURCE));
        for (JsonNode d : ses.get("decisions"))
            if (d.path("answerKey").asText().equals("dispatchIntervalMinutes")) ((ObjectNode) d.get("card")).put("generateWhen", "손으로 적은 설명");
        assertThrows(IllegalArgumentException.class, () -> SesTree.parse(ses));
        var digest = SesDerivation.digest(SesTree.load(SesTree.DEFAULT_RESOURCE));
        for (JsonNode p : digest.get("decisionPoints"))
            if (p.path("field").asText().equals("dispatchIntervalMinutes")) {
                assertEquals("numBuildings >= 2 && truckCount >= 2", p.get("generateWhen").asText());
                assertEquals(1439, p.path("range").path("max").asInt());
            }
    }
}
