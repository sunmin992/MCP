package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R1 정답 데이터(공통 명세, 사용자 표현)를 네 제공자의 실제 카드로 돌려 기대 후보 집합이 나오는지 본다.
 * LLM 해석은 들어가지 않는다 — 정답 요구가 카드의 표현으로 바르게 대응되는지(②층)만 확인한다.
 */
class GoldCandidateTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 정답_요구는_카드_표현으로_대응되어_기대_후보를_낸다() throws Exception {
        var registry = new CandidateRegistry(TestCards.jangnyang(), "classpath*:/mcp/candidates/*.json");
        for (String card : List.of("../../parking-sim/src/main/resources/mcp/parking-capability-card.json",
                "../../counter-sim/src/main/resources/mcp/counter-capability-card.json",
                "../../district-waste-sim/src/main/resources/mcp/district-capability-card.json"))
            assertTrue(registry.register(mapper.readTree(new File(card))), card);
        var find = new FindSimulatorsTool(new CandidateMatcher(registry), mapper);
        List<String> wrong = new ArrayList<>();
        int cases = 0;
        for (String line : Files.readAllLines(Path.of("../docs/reports/model-evaluation-2026-11-03/extraction-gold.jsonl"))) {
            if (line.isBlank()) continue;
            cases++;
            JsonNode gold = mapper.readTree(line);
            var result = find.call((ObjectNode) gold.get("expected").deepCopy());
            List<String> chosen = new ArrayList<>();
            if (result.ready()) for (JsonNode m : mapper.readTree(result.result().toString()).path("matches"))
                if (!m.path("fictional").asBoolean() && m.path("mismatches").isEmpty() && m.path("ambiguities").isEmpty())
                    chosen.add(m.path("serverId").asText());
            List<String> expected = new ArrayList<>();
            gold.get("expectedCandidates").forEach(n -> expected.add(n.asText()));
            Collections.sort(chosen);
            Collections.sort(expected);
            if (!chosen.equals(expected)) wrong.add(gold.get("id").asText() + " 기대 " + expected + " 실제 " + chosen);
        }
        assertEquals(28, cases);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }
}
