package com.wastesim.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.mcp.ses.PlanSubtasksTool;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanHistoryWriterTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void 한_사건은_한_줄(@TempDir Path dir) throws Exception {
        var writer = new PlanHistoryWriter(dir);
        for (String event : List.of("round", "build")) {
            ObjectNode e = m.createObjectNode();
            e.put("ts", "2026-11-03T01:02:03Z");
            e.put("event", event);
            e.put("note", "수거 시각\n줄바꿈");
            writer.write(e);
        }
        Path file = dir.resolve("plan-history-2026-11-03.jsonl");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("\r"), "줄바꿈은 LF 다");
        List<String> lines = text.lines().toList();
        assertEquals(2, lines.size());
        assertTrue(text.endsWith("\n"));
        JsonNode first = m.readTree(lines.get(0));
        assertEquals("round", first.get("event").asText());
        assertEquals("수거 시각\n줄바꿈", first.get("note").asText());
        assertTrue(lines.get(0).contains("수거 시각"), "한글을 이스케이프하지 않고 UTF-8 로 남긴다");
    }

    @Test
    void 쓰기_실패해도_응답은_나간다(@TempDir Path dir) throws Exception {
        Path notADir = Files.writeString(dir.resolve("file"), "x");
        var catalog = new TemplateCatalog();
        var tool = new PlanSubtasksTool(new SubtaskPlanner(catalog, new AnswerNormalizer()), m,
                new PlanRounds(catalog, new CapabilityCardLoader(), m, new PlanHistoryWriter(notADir)));
        var r = tool.call(m.readTree("{\"answers\":{}}"));
        assertTrue(r.ready(), String.valueOf(r.errors()));
        assertTrue(m.readTree((String) r.result()).get("planId").asText().startsWith("plan-"));
    }
}
