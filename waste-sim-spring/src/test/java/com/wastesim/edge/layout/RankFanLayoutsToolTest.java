package com.wastesim.edge.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpDomain;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 도구 계약과 fail-closed 검증. 이 도구는 공용
 * {@code SimulationConfigValidator}를 타지 않으므로 검증 책임이 전부 여기 있다 —
 * 여기가 뚫리면 LLM이 만든 엉뚱한 위치 문자열이 그대로 순위표가 된다.
 */
class RankFanLayoutsToolTest {

    private final ObjectMapper om = new ObjectMapper();
    private final RankFanLayoutsTool tool = new RankFanLayoutsTool();

    private JsonNode json(String s) throws Exception { return om.readTree(s); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ok(String args) throws Exception {
        ToolResult r = tool.call(json(args));
        assertTrue(r.ready(), () -> "실행이 거부됐다: " + r.errors());
        return (Map<String, Object>) r.result();
    }

    private ToolResult rejected(String args) throws Exception {
        ToolResult r = tool.call(json(args));
        assertFalse(r.ready(), "거부됐어야 한다");
        assertFalse(r.errors().isEmpty());
        return r;
    }

    @Test
    @DisplayName("도구 규약 — 이름·도메인·스키마")
    void toolContract() throws Exception {
        assertEquals("rank_fan_layouts", tool.toolName());
        assertEquals(McpDomain.EDGE, tool.domain());
        assertFalse(tool.description().isBlank());
        JsonNode schema = json(tool.inputSchemaJson());
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").has("positions"));
        assertTrue(schema.path("properties").has("candidates"));
        assertTrue(schema.path("properties").has("topK"));
    }

    @Test
    @DisplayName("인자 없이 부르면 60조합을 평가하고 상위 10개를 돌려준다")
    @SuppressWarnings("unchecked")
    void defaultRunEvaluatesAllSixty() throws Exception {
        Map<String, Object> out = ok("{}");

        assertEquals("rank_fan_layouts", out.get("tool"));
        assertEquals(FanLayoutRanking.STATUS_RANKED, out.get("status"));
        assertEquals(FanLayoutRanking.MODEL_KIND, out.get("modelKind"));
        assertEquals(60, out.get("evaluatedCount"));

        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertEquals(10, ranking.size(), "topK 기본값은 10");

        Map<String, Object> first = ranking.get(0);
        assertEquals(1, first.get("rank"));
        assertEquals("P02", first.get("id"));
        assertEquals(1.075, (Double) first.get("coolingScore"), 1e-9);
        assertEquals("FORCED_THROUGH_FLOW", first.get("flowType"));
        assertEquals("LOW", first.get("stagnationRisk"));

        Map<String, Object> fan1 = (Map<String, Object>) first.get("fan1");
        assertEquals("bottom", fan1.get("position"));
        assertEquals("하단", fan1.get("positionKo"));
        assertEquals("intake", fan1.get("flow"));
        assertEquals("흡기", fan1.get("flowKo"));
    }

    @Test
    @DisplayName("예상 온도는 advisory 블록에 격리되고 비교 불가 표식이 붙는다")
    @SuppressWarnings("unchecked")
    void advisoryTempIsQuarantined() throws Exception {
        Map<String, Object> out = ok("{}");
        Map<String, Object> first = ((List<Map<String, Object>>) out.get("ranking")).get(0);

        // 온도가 1급 필드로 새어 나오면 안 된다 — 그러면 시뮬레이터 온도와 섞인다.
        assertFalse(first.containsKey("peakTempC"));
        assertFalse(first.containsKey("advisoryPeakTempC"));

        Map<String, Object> advisory = (Map<String, Object>) first.get("advisory");
        assertEquals(52.975, (Double) advisory.get("peakTempC"), 1e-9);
        assertEquals(47.775, (Double) advisory.get("meanTempC"), 1e-9);
        assertEquals(2.25, (Double) advisory.get("spreadC"), 1e-9);
        assertEquals(82.0, (Double) advisory.get("anchorBarePeakC"), 1e-9);
        assertEquals(Boolean.FALSE, advisory.get("comparableWithSimulator"));
    }

    @Test
    @DisplayName("경고 3종과 신뢰상태가 항상 붙는다")
    @SuppressWarnings("unchecked")
    void warningsAlwaysPresent() throws Exception {
        Map<String, Object> out = ok("{\"topK\":1}");
        List<String> warnings = (List<String>) out.get("warnings");
        assertTrue(warnings.contains("FAN_SPEC_NOT_VERIFIED"));
        assertTrue(warnings.contains("ADVISORY_TEMP_ANCHORED_ESTIMATE"));
        assertTrue(warnings.contains("ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR"));
        assertEquals("PRELIMINARY_ESTIMATE", out.get("sourceStatus"));
        assertEquals(FanLayoutRanking.RECOMMENDED_MEASUREMENT_STEPS,
                out.get("recommendedMeasurementSteps"));
        assertEquals(FanLayoutRanking.TIE_BREAK, out.get("tieBreak"));
    }

    @Test
    @DisplayName("includeAllCombinations=true면 topK와 무관하게 60개를 전부 돌려준다")
    @SuppressWarnings("unchecked")
    void includeAllOverridesTopK() throws Exception {
        Map<String, Object> out = ok("{\"topK\":3,\"includeAllCombinations\":true}");
        assertEquals(60, ((List<Map<String, Object>>) out.get("ranking")).size());
    }

    @Test
    @DisplayName("positions로 열거 범위를 줄일 수 있다")
    @SuppressWarnings("unchecked")
    void positionsNarrowsEnumeration() throws Exception {
        Map<String, Object> out = ok(
                "{\"positions\":[\"bottom\",\"top\"],\"includeAllCombinations\":true}");
        assertEquals(4, out.get("evaluatedCount"));
        assertEquals(4, ((List<Map<String, Object>>) out.get("ranking")).size());
    }

    /**
     * candidates 모드의 조합 ID는 입력 순서(P01, P02, ...)가 아니라 60조합 전수
     * 표에서의 <b>정식 ID</b>여야 한다(브리핑 문서 Ruling 1). 두 후보 — 하단 흡기 +
     * 상단 배기, 그리고 하단 배기 + 상단 흡기 — 는 각각 전수열거의 P02·P03과 같은
     * 배치다. 입력 순서대로 P01·P02를 붙이면 전수열거 모드가 말하는 P02(진짜로는
     * 이 후보의 두 번째 자리인 P03)와 뜻이 어긋난다.
     */
    @Test
    @DisplayName("candidates로 특정 배치만 직접 평가할 수 있다")
    @SuppressWarnings("unchecked")
    void candidatesEvaluateExplicitLayouts() throws Exception {
        Map<String, Object> out = ok("""
            {"candidates":[
              {"fan1":{"position":"하단","flow":"흡기"},"fan2":{"position":"상단","flow":"배기"}},
              {"fan1":{"position":"bottom","flow":"exhaust"},"fan2":{"position":"top","flow":"intake"}}
            ]}""");
        assertEquals(2, out.get("evaluatedCount"));
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertEquals(2, ranking.size());
        assertEquals(1.075, (Double) ranking.get(0).get("coolingScore"), 1e-9);
        assertEquals(0.825, (Double) ranking.get(1).get("coolingScore"), 1e-9);
        assertEquals("P02", ranking.get(0).get("id"));
        assertEquals("P03", ranking.get(1).get("id"));
    }

    /**
     * 위치쌍은 무순서라 fan1·fan2 순서를 뒤집어도 같은 배치여야 한다. 정규화 키가
     * ordinal로 정렬하지 않고 입력 순서를 그대로 쓰도록 "단순화"되면, 이 테스트가
     * 아니면 그 퇴행이 조용히 통과한다 — canonicalEvaluateExplicitLayouts의 두
     * 후보가 전부 정준 열거 순서(하단이 먼저)라 스왑 분기를 타지 않기 때문이다.
     * 그래서 여기서는 일부러 fan1=상단/배기, fan2=하단/흡기로 <b>뒤집어</b> 넣는다.
     */
    @Test
    @DisplayName("candidates에서 fan1·fan2 순서를 바꿔도 조합 ID가 같다")
    @SuppressWarnings("unchecked")
    void candidateFanOrderDoesNotChangeCombinationId() throws Exception {
        Map<String, Object> canonicalOut = ok("""
            {"candidates":[
              {"fan1":{"position":"bottom","flow":"intake"},"fan2":{"position":"top","flow":"exhaust"}}
            ]}""");
        // 뒤집은 순서 — 정준 열거 순서(하단이 먼저)와 정반대로 상단이 먼저 온다.
        Map<String, Object> reversedOut = ok("""
            {"candidates":[
              {"fan1":{"position":"top","flow":"exhaust"},"fan2":{"position":"bottom","flow":"intake"}}
            ]}""");

        List<Map<String, Object>> canonicalRanking =
                (List<Map<String, Object>>) canonicalOut.get("ranking");
        List<Map<String, Object>> reversedRanking =
                (List<Map<String, Object>>) reversedOut.get("ranking");

        assertEquals("P02", canonicalRanking.get(0).get("id"));
        assertEquals("P02", reversedRanking.get(0).get("id"),
                "fan1·fan2 순서를 뒤집어도 같은 물리적 배치이므로 같은 P-ID여야 한다");
        assertEquals(1.075, (Double) canonicalRanking.get(0).get("coolingScore"), 1e-9);
        assertEquals(1.075, (Double) reversedRanking.get(0).get("coolingScore"), 1e-9,
                "순서를 뒤집어도 점수가 달라지면 안 된다");
    }

    @Test
    @DisplayName("candidates와 positions를 함께 주면 거부한다")
    void rejectsCandidatesWithPositions() throws Exception {
        ToolResult r = rejected("""
            {"positions":["bottom","top"],
             "candidates":[{"fan1":{"position":"bottom","flow":"intake"},
                            "fan2":{"position":"top","flow":"exhaust"}}]}""");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }

    @Test
    @DisplayName("위치가 2곳 미만이면 쌍을 만들 수 없어 거부한다")
    void rejectsTooFewPositions() throws Exception {
        ToolResult r = rejected("{\"positions\":[\"bottom\"]}");
        assertEquals(ErrorCode.OUT_OF_RANGE, r.errors().get(0).code());
    }

    @Test
    @DisplayName("위치가 중복되면 거부한다")
    void rejectsDuplicatePositions() throws Exception {
        ToolResult r = rejected("{\"positions\":[\"bottom\",\"bottom\",\"top\"]}");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }

    @Test
    @DisplayName("알 수 없는 위치·방향은 추측하지 않고 거부한다")
    void rejectsUnknownEnums() throws Exception {
        assertEquals(ErrorCode.INVALID_ENUM,
                rejected("{\"positions\":[\"뒷면\",\"top\"]}").errors().get(0).code());
        assertEquals(ErrorCode.INVALID_ENUM, rejected("""
            {"candidates":[{"fan1":{"position":"bottom","flow":"순환"},
                            "fan2":{"position":"top","flow":"exhaust"}}]}""")
                .errors().get(0).code());
    }

    @Test
    @DisplayName("한 자리에 팬 2개를 다는 배치는 거부한다")
    void rejectsSamePositionForBothFans() throws Exception {
        ToolResult r = rejected("""
            {"candidates":[{"fan1":{"position":"bottom","flow":"intake"},
                            "fan2":{"position":"bottom","flow":"exhaust"}}]}""");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }

    @Test
    @DisplayName("topK가 범위 밖이면 거부한다")
    void rejectsTopKOutOfRange() throws Exception {
        assertEquals(ErrorCode.OUT_OF_RANGE, rejected("{\"topK\":0}").errors().get(0).code());
        assertEquals(ErrorCode.OUT_OF_RANGE, rejected("{\"topK\":61}").errors().get(0).code());
    }

    @Test
    @DisplayName("candidates가 빈 배열이면 거부한다")
    void rejectsEmptyCandidates() throws Exception {
        assertEquals(ErrorCode.OUT_OF_RANGE, rejected("{\"candidates\":[]}").errors().get(0).code());
    }

    /**
     * 같은 배치가 candidates 배열에 두 번 들어오면(예: 둘 다 P02로 정규화되는 서로
     * 다른 표기) 순위표에 같은 id가 서로 다른 순위(1위·2위)로 두 번 나온다 —
     * 사용자가 보기에 결과가 모순된다. positions 쪽은 이미 같은 위치 중복을
     * 거부하므로, candidates 쪽도 같은 기준으로 fail-closed여야 한다.
     */
    @Test
    @DisplayName("candidates에 같은 배치가 두 번 들어오면 거부한다")
    void rejectsDuplicateCandidateLayouts() throws Exception {
        ToolResult r = rejected("""
            {"candidates":[
              {"fan1":{"position":"bottom","flow":"intake"},"fan2":{"position":"top","flow":"exhaust"}},
              {"fan1":{"position":"top","flow":"exhaust"},"fan2":{"position":"bottom","flow":"intake"}}
            ]}""");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
        assertTrue(r.errors().get(0).message().contains("P02"),
                "중복된 조합 id(P02)를 메시지에 이름으로 밝혀야 한다: " + r.errors().get(0).message());
    }

    @Test
    @DisplayName("topK가 정수가 아니면 거부한다 — 소수는 조용히 잘라내지 않는다")
    void rejectsNonIntegralTopK() throws Exception {
        ToolResult r = rejected("{\"topK\":3.7}");
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.errors().get(0).code());
    }
}
