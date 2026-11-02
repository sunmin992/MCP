package com.wastesim.templategen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SesDraftTest {

    static TemplateGenerator.Result result;
    static final ObjectMapper M = new ObjectMapper();

    @BeforeAll
    static void run() throws Exception {
        result = generate();
    }

    /** 생성기를 새로 돌린다. 결정성은 생성부터 다시 해야 보인다 — 같은 결과로 초안만 두 번 뽑으면 생성의 흔들림을 놓친다. */
    static TemplateGenerator.Result generate() throws Exception {
        return new TemplateGenerator(Path.of("../simulator/src/main/java")).generate(
                "com.wastesim.model.SimulationConfig",
                List.of("com.wastesim.tool.SimulationConfigValidator"),
                List.of("com.wastesim.service.SimulationService", "com.wastesim.simulation.SimulationEngine",
                        "com.wastesim.simulation.RoutePlanner", "com.wastesim.simulation.TravelTimeCalculator"));
    }

    static String kind(ObjectNode draft, String field) {
        for (JsonNode d : draft.get("decisions")) {
            if (field.equals(d.get("configField").asText())) return d.get("nodeKind").asText();
        }
        throw new AssertionError(field);
    }

    @Test
    void 종류_규칙() {
        ObjectNode d = SesDraft.draft(result);
        assertEquals("MULTI_ASPECT", kind(d, "numTrucks"));
        assertEquals("SPECIALIZATION", kind(d, "truckType"));
        assertEquals("ATTRIBUTE", kind(d, "days"));
        JsonNode root = d.at("/entities/초안");
        assertTrue(root.get("decompositions").toString().contains("numTrucks"));
        assertTrue(root.get("decompositions").toString().contains("무엇의 개수인지 확인"));
        assertTrue(root.get("specs").toString().contains("LARGE_5TON"));
        assertTrue(root.get("attrs").toString().contains("days"));
    }

    @Test
    void 결정마다_검토_칸을_단다() {
        ObjectNode d = SesDraft.draft(result);
        for (JsonNode x : d.get("decisions")) assertTrue(x.get("_review").isArray(), x.toString());
        assertTrue(d.at("/entities/초안/_review").toString().contains("엔티티 배치 필요"));
    }

    @Test
    void 같은_입력이면_바이트까지_같다() throws Exception {
        assertEquals(M.writeValueAsString(SesDraft.draft(generate())), M.writeValueAsString(SesDraft.draft(generate())));
    }

    @Test
    void 원본과_종류가_다른_결정을_보고한다() throws Exception {
        JsonNode tpl = M.readTree(Path.of("../simulator/src/main/resources/ses/jangnyang-templates.json").toFile());
        List<String> diff = SesDraft.kindDiff(SesDraft.draft(result), tpl);
        assertTrue(diff.stream().anyMatch(s -> s.startsWith("residentsPerBuilding")), diff.toString());
    }

    @Test
    void 종류_차이를_보고서_표로_만든다() {
        String md = SesDraft.kindDiffMarkdown(List.of("residentsPerBuilding: 초안 ATTRIBUTE · 원본 MULTI_ASPECT"));
        assertTrue(md.startsWith("## 결정 종류 차이 (초안 · 원본)"));
        assertTrue(md.contains("| configField | 초안 | 원본 |"));
        assertTrue(md.contains("| residentsPerBuilding | ATTRIBUTE | MULTI_ASPECT |"));
        assertTrue(SesDraft.kindDiffMarkdown(List.of()).contains("없음"));
    }
}
