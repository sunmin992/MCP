package com.wastesim.ses;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 커밋된 템플릿 JSON 이 SES 원본에서 만든 것과 같은가.
 *
 * <p>{@code -Dses.regenerate=true} 로 돌면 비교하지 않고 파일을 다시 쓴다.
 */
class SesDerivationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path TEMPLATES = Path.of("src/main/resources/ses/jangnyang-templates.json");
    private static final Path CARD = Path.of("src/main/resources/mcp/jangnyang-capability-card.json");

    @Test
    void 템플릿은_원본에서_만든_것과_같다() throws Exception {
        ObjectNode derived = SesDerivation.templates(SesTree.load(SesTree.DEFAULT_RESOURCE));
        if (Boolean.getBoolean("ses.regenerate")) {
            // 시스템 줄바꿈(Windows CRLF)이 아니라 LF 로, 배열도 한 줄에 한 원소로 쓴다.
            DefaultIndenter lf = new DefaultIndenter("  ", "\n");
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withObjectIndenter(lf);
            pp.indentArraysWith(lf);
            Files.writeString(TEMPLATES, M.writer(pp).writeValueAsString(derived) + "\n");
            return;
        }
        assertEquals(read("/ses/jangnyang-templates.json"), derived,
                "jangnyang-ses.json 과 템플릿이 어긋납니다 — ./mvnw -pl simulator test -Dtest=SesDerivationTest -Dses.regenerate=true 로 다시 만드십시오");
    }

    /** 원본화가 동작을 바꾸지 않았다는 증거: sesVersion 말고는 1.0.0 템플릿과 칸 하나하나가 같다. */
    @Test
    void sesVersion_외에는_예전_템플릿과_같다() throws Exception {
        ObjectNode derived = SesDerivation.templates(SesTree.load(SesTree.DEFAULT_RESOURCE));
        ObjectNode before = (ObjectNode) read("/ses/templates-1.0.0.json");
        derived.remove("sesVersion");
        before.remove("sesVersion");
        assertEquals(before, derived);
    }

    @Test
    void 카드_digest_는_원본에서_만든_것과_같다() throws Exception {
        ObjectNode derived = SesDerivation.derivedCard(
                (ObjectNode) read("/mcp/jangnyang-capability-card.json"), SesTree.load(SesTree.DEFAULT_RESOURCE));
        if (Boolean.getBoolean("ses.regenerate")) {
            DefaultIndenter lf = new DefaultIndenter("  ", "\n");
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withObjectIndenter(lf);
            pp.indentArraysWith(lf);
            Files.writeString(CARD, M.writer(pp).writeValueAsString(derived) + "\n");
            return;
        }
        assertEquals(read("/mcp/jangnyang-capability-card.json"), derived,
                "jangnyang-ses.json 과 카드 sesDigest 가 어긋납니다 — ./mvnw -pl simulator test -Dtest=SesDerivationTest -Dses.regenerate=true 로 다시 만드십시오");
    }

    /** 브로커가 판정에 쓰는 조건 범위도 SES 에서 온다 — 규칙만 고치면 브로커 판정까지 바뀐다(R3 C4). */
    @Test
    void SES_범위를_고치면_브로커_판정이_따라_바뀐다() throws Exception {
        ObjectNode card = (ObjectNode) read("/mcp/jangnyang-capability-card.json");
        ObjectNode ses = (ObjectNode) read("/ses/jangnyang-ses.json");
        JsonNode request = M.readTree("""
            {"constraints":[{"name":"배차 간격","value":3,"unit":"시간"}]}""");
        java.util.function.Function<ObjectNode, String> status = c ->
                com.wastesim.contract.RequirementContract.evaluate(request, c).stream()
                        .filter(x -> x.field().equals("constraints.dispatchIntervalMinutes")).findFirst().orElseThrow().status();
        assertEquals("SATISFIED", status.apply(SesDerivation.derivedCard(card, SesTree.parse(ses))));
        for (JsonNode d : ses.get("decisions"))
            if (d.path("answerKey").asText().equals("dispatchIntervalMinutes")) ((ObjectNode) d).put("max", 120);
        ObjectNode changed = SesDerivation.derivedCard(card, SesTree.parse(ses));
        assertEquals(120, changed.at("/capabilities/constraints/dispatchIntervalMinutes/max").asInt());
        assertEquals("UNSATISFIED", status.apply(changed), "180분은 새 최대 120분을 넘는다");
    }

    @Test
    void 조건_표현이_비면_거절한다() throws Exception {
        ObjectNode ses = (ObjectNode) read("/ses/jangnyang-ses.json");
        for (JsonNode d : ses.get("decisions"))
            if (d.path("answerKey").asText().equals("days")) ((ObjectNode) d).putObject("constraint").putArray("terms");
        ObjectNode card = (ObjectNode) read("/mcp/jangnyang-capability-card.json");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SesDerivation.derivedCard(card, SesTree.parse(ses)));
    }

    @Test
    void 카드의_다른_칸은_그대로다() throws Exception {
        ObjectNode before = (ObjectNode) read("/mcp/card-before-ses.json");
        ObjectNode now = (ObjectNode) read("/mcp/jangnyang-capability-card.json");
        before.remove("sesDigest");
        now.remove("sesDigest");
        assertEquals(before, now);
    }

    @Test
    void digest_는_예전_카드와_경로_두개와_순서만_다르다() throws Exception {
        JsonNode old = read("/mcp/card-before-ses.json").get("sesDigest");
        ObjectNode now = SesDerivation.digest(SesTree.load(SesTree.DEFAULT_RESOURCE));
        assertEquals(old.get("entities"), now.get("entities"));
        Map<String, ObjectNode> oldByField = new HashMap<>();
        old.get("decisionPoints").forEach(p -> oldByField.put(p.get("field").asText(), (ObjectNode) p));
        assertEquals(oldByField.size(), now.get("decisionPoints").size());
        Set<String> pathDiff = new TreeSet<>();
        for (JsonNode p : now.get("decisionPoints")) {
            String field = p.get("field").asText();
            ObjectNode o = oldByField.get(field);
            assertEquals(true, o != null, "예전 카드에 없는 field: " + field);
            if (!o.get("path").equals(p.get("path"))) {
                pathDiff.add(field);
            }
            assertEquals(o.deepCopy().without("path"), ((ObjectNode) p).deepCopy().without("path"), field);
        }
        assertEquals(Set.of("occupationMix", "dischargeTimeMode"), pathDiff);
    }

    private static JsonNode read(String resource) throws Exception {
        try (InputStream in = SesDerivationTest.class.getResourceAsStream(resource)) {
            return M.readTree(in);
        }
    }
}
