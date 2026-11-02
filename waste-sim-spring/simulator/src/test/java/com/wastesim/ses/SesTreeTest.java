package com.wastesim.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SesTreeTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 루트 R 아래 A, A 아래 A/B. A 는 multi · spec · attr · couplingDecision 을 가진다. */
    private static final String ENTITIES = """
            "root": "R",
            "entities": {
              "R": {"decompositions": [{"kind": "aspect", "name": "구성", "children": ["A"]}]},
              "A": {"decompositions": [{"kind": "aspect", "name": "a", "children": ["A/B"]},
                                       {"kind": "multi", "name": "m", "children": ["원소"]}],
                    "specs": [{"name": "s", "children": ["P", "Q"]}],
                    "attrs": ["x"],
                    "couplingDecisions": ["c"]},
              "A/B": {"attrs": ["x"]}
            },
            """;

    private static SesTree parse(String entities, String decisions) throws Exception {
        String json = "{\"sesId\": \"t\", \"sesVersion\": \"9\", " + entities
                + "\"decisions\": [" + decisions + "]}";
        return SesTree.parse(M.readTree(json));
    }

    private static SesTree.Decision one(String decision) throws Exception {
        return parse(ENTITIES, decision).decisions().get(0);
    }

    @Test
    void 가장_긴_엔티티_키로_푼다() throws Exception {
        SesTree.Decision d = one("{\"at\": \"A/B/x\", \"templateId\": \"t1\"}");
        assertEquals("A/B/x", d.sesPath());
        assertEquals("ATTRIBUTE", d.nodeKind());
        assertEquals("t1", d.fields().get("templateId").asText());
        assertFalse(d.fields().has("at"));
        assertNull(d.card());
    }

    @Test
    void 엔티티_키_자체는_다중_분해다() throws Exception {
        assertEquals("MULTI_ASPECT", one("{\"at\": \"A\"}").nodeKind());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> one("{\"at\": \"A/B\"}"));
        assertTrue(ex.getMessage().contains("A/B"), ex.getMessage());
    }

    @Test
    void spec_attr_coupling_을_구분한다() throws Exception {
        assertEquals("SPECIALIZATION", one("{\"at\": \"A/s\", \"allowed\": [\"P\", \"Q\"]}").nodeKind());
        assertEquals("ATTRIBUTE", one("{\"at\": \"A/x\"}").nodeKind());
        assertEquals("COUPLING_CONDITION", one("{\"at\": \"A/c\"}").nodeKind());
    }

    @Test
    void card_칸은_fields_에서_빠진다() throws Exception {
        SesTree.Decision d = one("{\"at\": \"A/x\", \"k\": 1, \"card\": {\"matchKeys\": [\"m\"]}}");
        assertFalse(d.fields().has("card"));
        assertEquals("m", d.card().get("matchKeys").get(0).asText());
    }

    @Test
    void 없는_노드를_가리키면_막는다() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> one("{\"at\": \"A/없음\"}"));
        assertTrue(ex.getMessage().contains("A/없음"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> one("{\"at\": \"없는엔티티/x\"}"));
    }

    @Test
    void 닿지_않는_엔티티는_막는다() {
        String entities = ENTITIES.replace("\"A/B\": {\"attrs\": [\"x\"]}",
                "\"A/B\": {\"attrs\": [\"x\"]}, \"외딴\": {\"attrs\": [\"y\"]}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse(entities, "{\"at\": \"A/x\"}"));
        assertTrue(ex.getMessage().contains("외딴"), ex.getMessage());
    }

    @Test
    void spec_선택지와_allowed_가_다르면_막는다() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> one("{\"at\": \"A/s\", \"allowed\": [\"P\"]}"));
        assertTrue(ex.getMessage().contains("A/s"), ex.getMessage());
    }

    @Test
    void 결정_순서를_지킨다() throws Exception {
        SesTree ses = parse(ENTITIES, "{\"at\": \"A/x\"}, {\"at\": \"A/B/x\"}, {\"at\": \"A/c\"}");
        assertEquals(List.of("A/x", "A/B/x", "A/c"),
                ses.decisions().stream().map(SesTree.Decision::sesPath).toList());
        assertEquals("t", ses.sesId());
        assertEquals("9", ses.sesVersion());
    }

    @Test
    void 형식_오류는_막는다() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SesTree.parse(M.readTree("{\"sesId\": \"t\"}")));
        assertThrows(IllegalArgumentException.class, () -> one("{\"templateId\": \"at 없음\"}"));
    }
}
