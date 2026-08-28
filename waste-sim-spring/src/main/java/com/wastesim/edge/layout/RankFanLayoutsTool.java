package com.wastesim.edge.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.edge.FanArraySpec;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구 {@code rank_fan_layouts} — "40 mm 팬 2개를 어디에 어떤 방향으로 달아야
 * 하나"에 순위로 답한다.
 *
 * <p>기본 동작은 장착 위치 6곳에서 만들 수 있는 <b>60조합 전수 평가</b>다
 * (15개 위치쌍 × 4개 방향조합).
 *
 * <h3>이 도구가 내는 온도를 시뮬레이터 온도와 비교하면 안 된다</h3>
 * 예상 온도는 "무팬 82 ℃"라는 임의 앵커에서 선형 환산한 값이라, 열저항·주변온도·부하
 * 프로파일에서 온도를 계산하는 {@code simulate_edge_throttling}과 숫자가 맞지 않는다.
 * 그래서 온도는 응답의 {@code advisory} 블록에만 담고 경고를 항상 함께 낸다.
 * 사용자가 봐야 할 1급 지표는 {@code coolingScore}와 {@code stagnationRisk}다.
 */
@Component
public class RankFanLayoutsTool implements McpToolProvider {

    /** 응답 기본 상위 개수 — 표로 읽을 수 있는 분량. */
    static final int DEFAULT_TOP_K = 10;
    /** 6위치 전수 조합 수. topK 상한이기도 하다. */
    static final int MAX_COMBINATIONS = 60;

    /**
     * 6위치 전수 조합의 정규화 키 → 정식 ID(P01~P60) 조회표.
     *
     * <p>{@code candidates} 모드에서 사용자가 준 배치도 이 표를 거쳐 정식 ID를 받는다
     * (설계 결정: 위치쌍이 무순서이므로 입력 순서와 무관하게 같은 배치는 같은 ID여야
     * 한다). {@code parsePositions}로 위치를 좁힌 열거는 이 표를 쓰지 않는다 — 그쪽은
     * 애초에 전수가 아니므로 P-ID 매핑 자체가 성립하지 않는다.
     */
    private static final Map<String, String> CANONICAL_ID_BY_KEY = buildCanonicalIndex();

    private static Map<String, String> buildCanonicalIndex() {
        Map<String, String> index = new HashMap<>();
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));
        for (FanLayoutCandidate c : all) {
            index.put(normalizedKey(c.position1(), c.flow1(), c.position2(), c.flow2()), c.id());
        }
        return index;
    }

    /** 위치쌍이 무순서이므로 ordinal이 작은 위치를 항상 앞에 둬 순서 무관 키를 만든다. */
    private static String normalizedKey(FanMountPosition p1, FanFlowRole f1,
                                        FanMountPosition p2, FanFlowRole f2) {
        if (p1.ordinal() <= p2.ordinal()) {
            return p1.name() + ":" + f1.name() + "|" + p2.name() + ":" + f2.name();
        }
        return p2.name() + ":" + f2.name() + "|" + p1.name() + ":" + f1.name();
    }

    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "rank_fan_layouts"; }

    @Override
    public String description() {
        return "라즈베리파이 함체에 40 mm 팬 2개를 다는 위치(하단·상단·좌우 상하단)와 방향(흡기·배기) "
             + "조합 60가지를 경험적 냉각점수로 순위 매긴다. 기류 유형·정체 위험·예상 온도편차를 함께 낸다. "
             + "실측 전 후보 선별용이며, 예상 온도는 이 도구 안에서만 유효한 임시 추정값이라 "
             + "발열 시뮬레이션 결과와 비교할 수 없다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "positions": {
                  "type": "array",
                  "description": "전수 열거에 포함할 장착 위치(2곳 이상). 생략하면 6곳 전부. candidates와 동시 사용 불가",
                  "items": {"type": "string",
                    "enum": ["bottom", "top", "left_bottom", "left_top", "right_bottom", "right_top"]}
                },
                "candidates": {
                  "type": "array",
                  "description": "직접 지정한 배치 후보. positions와 동시 사용 불가",
                  "items": {
                    "type": "object",
                    "properties": {
                      "fan1": {"type": "object", "properties": {
                        "position": {"type": "string"},
                        "flow": {"type": "string", "enum": ["intake", "exhaust"]}}},
                      "fan2": {"type": "object", "properties": {
                        "position": {"type": "string"},
                        "flow": {"type": "string", "enum": ["intake", "exhaust"]}}}
                    },
                    "required": ["fan1", "fan2"]
                  }
                },
                "topK": {"type": "integer", "description": "응답에 담을 상위 개수(1~60)", "default": 10},
                "includeAllCombinations": {"type": "boolean",
                  "description": "true면 topK와 무관하게 평가한 모든 조합을 반환", "default": false}
              }
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        JsonNode root = args;
        boolean hasPositions = has(root, "positions");
        boolean hasCandidates = has(root, "candidates");

        // 열거 범위와 직접 지정을 동시에 주면 무엇을 평가해야 하는지 모순이다.
        // 한쪽을 조용히 무시하면 사용자는 자기가 준 조건이 반영된 줄 안다.
        if (hasPositions && hasCandidates) {
            return reject(ErrorCode.INVALID_ARGUMENTS, "candidates",
                    "positions(열거 범위)와 candidates(직접 지정)는 함께 쓸 수 없다. 하나만 지정할 것");
        }

        List<FanLayoutCandidate> candidates;
        if (hasCandidates) {
            Object parsed = parseCandidates(root.get("candidates"));
            if (parsed instanceof ValidationError e) return ToolResult.rejected(e);
            @SuppressWarnings("unchecked")
            List<FanLayoutCandidate> list = (List<FanLayoutCandidate>) parsed;
            candidates = list;
        } else {
            Object parsed = parsePositions(root == null ? null : root.get("positions"));
            if (parsed instanceof ValidationError e) return ToolResult.rejected(e);
            @SuppressWarnings("unchecked")
            List<FanMountPosition> positions = (List<FanMountPosition>) parsed;
            candidates = FanLayoutScoreModel.enumerateAll(positions);
        }

        int topK = DEFAULT_TOP_K;
        if (has(root, "topK")) {
            JsonNode n = root.get("topK");
            if (!n.isNumber() || !n.isIntegralNumber()) {
                return reject(ErrorCode.INVALID_ARGUMENTS, "topK", "topK는 정수여야 한다 (받은 값: " + n + ")");
            }
            topK = n.asInt();
            if (topK < 1 || topK > MAX_COMBINATIONS) {
                return reject(ErrorCode.OUT_OF_RANGE, "topK",
                        "topK는 1 이상 " + MAX_COMBINATIONS + " 이하여야 한다 (받은 값: " + topK + ")");
            }
        }
        boolean includeAll = has(root, "includeAllCombinations")
                && root.get("includeAllCombinations").asBoolean(false);

        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(candidates);
        int limit = includeAll ? ranked.size() : Math.min(topK, ranked.size());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < limit; i++) rows.add(row(ranked.get(i)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("status", FanLayoutRanking.STATUS_RANKED);
        out.put("modelKind", FanLayoutRanking.MODEL_KIND);
        out.put("evaluatedCount", candidates.size());
        out.put("ranking", rows);
        out.put("tieBreak", FanLayoutRanking.TIE_BREAK);
        out.put("warnings", FanLayoutRanking.WARNINGS);
        // 리터럴로 중복해서 적으면 이 enum이 바뀔 때 조용히 어긋난다 — 이 필드가 정확히
        // "숫자가 아직 잠정값"이라는 걸 알리는 표식이므로 소스에서 직접 끌어온다.
        out.put("sourceStatus", FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE.name());
        out.put("recommendedMeasurementSteps", FanLayoutRanking.RECOMMENDED_MEASUREMENT_STEPS);
        return ToolResult.ok(out);
    }

    // ── 응답 조립 ────────────────────────────────────────────────────────

    private Map<String, Object> row(FanLayoutRanking.Entry e) {
        FanLayoutCandidate c = e.candidate();
        FanLayoutScore s = e.score();

        Map<String, Object> advisory = new LinkedHashMap<>();
        advisory.put("peakTempC", round(s.advisoryPeakTempC()));
        advisory.put("meanTempC", round(s.advisoryMeanTempC()));
        advisory.put("spreadC", round(s.advisorySpreadC()));
        advisory.put("anchorBarePeakC", FanLayoutScoreModel.BARE_PEAK_ANCHOR_C);
        // 이 한 줄이 클라이언트가 시뮬레이터 온도와 섞지 않게 막는 표식이다.
        advisory.put("comparableWithSimulator", Boolean.FALSE);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", e.rank());
        m.put("id", c.id());
        m.put("fan1", fan(c.position1(), c.flow1()));
        m.put("fan2", fan(c.position2(), c.flow2()));
        m.put("flowType", s.flowType().wire());
        m.put("flowTypeKo", s.flowType().koLabel());
        m.put("coolingScore", round(s.coolingScore()));
        m.put("pairFactor", round(s.pairFactor()));
        m.put("flowBonus", round(s.flowBonus()));
        m.put("stagnationRisk", s.stagnationRisk().wire());
        m.put("stagnationRiskKo", s.stagnationRisk().koLabel());
        m.put("interpretation", s.interpretation());
        m.put("advisory", advisory);
        return m;
    }

    private Map<String, Object> fan(FanMountPosition p, FanFlowRole f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", p.wire());
        m.put("positionKo", p.koLabel());
        m.put("flow", f.wire());
        m.put("flowKo", f.koLabel());
        return m;
    }

    /**
     * 부동소수점 잡음을 자른다. 골든 테스트가 1e-9로 비교하므로 그보다 훨씬 촘촘한
     * 자리에서만 자른다 — 반올림이 값을 바꾸면 엑셀과 어긋난다.
     */
    private double round(double v) { return Math.round(v * 1e12) / 1e12; }

    // ── 입력 파싱 (fail-closed) ──────────────────────────────────────────

    private Object parsePositions(JsonNode node) {
        if (node == null || node.isNull()) return List.of(FanMountPosition.values());
        if (!node.isArray()) {
            return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "positions", "positions는 배열이어야 한다");
        }
        LinkedHashSet<FanMountPosition> seen = new LinkedHashSet<>();
        for (JsonNode n : node) {
            FanMountPosition p = FanMountPosition.parse(n.asText(null));
            if (p == null) {
                return new ValidationError(ErrorCode.INVALID_ENUM, "positions",
                        "알 수 없는 장착 위치: " + n.asText()
                        + " (bottom·top·left_bottom·left_top·right_bottom·right_top 중 하나)");
            }
            if (!seen.add(p)) {
                return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "positions",
                        "장착 위치가 중복됐다: " + p.wire());
            }
        }
        if (seen.size() < 2) {
            return new ValidationError(ErrorCode.OUT_OF_RANGE, "positions",
                    "팬 2개를 배치하려면 위치가 2곳 이상이어야 한다 (받은 개수: " + seen.size() + ")");
        }
        return new ArrayList<>(seen);
    }

    private Object parseCandidates(JsonNode node) {
        if (!node.isArray()) {
            return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "candidates", "candidates는 배열이어야 한다");
        }
        if (node.isEmpty()) {
            return new ValidationError(ErrorCode.OUT_OF_RANGE, "candidates",
                    "평가할 배치가 하나도 없다");
        }
        if (node.size() > MAX_COMBINATIONS) {
            return new ValidationError(ErrorCode.OUT_OF_RANGE, "candidates",
                    "배치 후보는 " + MAX_COMBINATIONS + "개 이하여야 한다 (받은 개수: " + node.size() + ")");
        }
        List<FanLayoutCandidate> out = new ArrayList<>();
        LinkedHashSet<String> seenCanonicalIds = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode n : node) {
            String field = "candidates[" + index + "]";
            index++;
            FanMountPosition p1 = FanMountPosition.parse(n.path("fan1").path("position").asText(null));
            FanFlowRole f1 = FanFlowRole.parse(n.path("fan1").path("flow").asText(null));
            FanMountPosition p2 = FanMountPosition.parse(n.path("fan2").path("position").asText(null));
            FanFlowRole f2 = FanFlowRole.parse(n.path("fan2").path("flow").asText(null));

            if (p1 == null || p2 == null) {
                return new ValidationError(ErrorCode.INVALID_ENUM, field,
                        "알 수 없는 장착 위치다 (bottom·top·left_bottom·left_top·right_bottom·right_top)");
            }
            if (f1 == null || f2 == null) {
                return new ValidationError(ErrorCode.INVALID_ENUM, field,
                        "알 수 없는 팬 역할이다 (intake·exhaust 또는 흡기·배기)");
            }
            if (p1 == p2) {
                return new ValidationError(ErrorCode.INVALID_ARGUMENTS, field,
                        "같은 자리에 팬 2개를 달 수 없다: " + p1.koLabel());
            }

            // 정식 P-ID로 해석한다 — 입력 순서 기반 ID(P01, P02, ...)를 그대로 붙이면
            // "이 배치는 몇 번"이라는 값이 candidates 모드와 전수열거 모드에서 서로 다른
            // 뜻이 되어 두 응답을 나란히 놓고 읽을 수 없게 된다. 6위치 중 서로 다른
            // 두 곳을 고른 배치는 이미 위에서 p1 != p2를 확인했으므로 반드시 60조합
            // 중 하나에 걸린다 — 그래도 걸리지 않으면 추측하지 않고 거부한다(fail-closed).
            String canonicalId = CANONICAL_ID_BY_KEY.get(normalizedKey(p1, f1, p2, f2));
            if (canonicalId == null) {
                return new ValidationError(ErrorCode.INVALID_ARGUMENTS, field,
                        "이 배치를 표준 60조합 중 하나로 식별할 수 없다 — 위치·방향을 다시 확인할 것");
            }
            // 같은 배치가 두 번 들어오면 순위표에 같은 id가 서로 다른 순위로 두 번
            // 나온다 — 사용자가 보기에 결과가 모순돼 보이므로 조용히 중복 순위를
            // 매기지 않고 거부한다(fail-closed).
            if (!seenCanonicalIds.add(canonicalId)) {
                return new ValidationError(ErrorCode.INVALID_ARGUMENTS, field,
                        "같은 배치가 중복됐다: " + canonicalId);
            }
            out.add(new FanLayoutCandidate(canonicalId, p1, f1, p2, f2));
        }
        return out;
    }

    private static boolean has(JsonNode root, String field) {
        return root != null && root.has(field) && !root.get(field).isNull();
    }

    private static ToolResult reject(ErrorCode code, String field, String message) {
        return ToolResult.rejected(new ValidationError(code, field, message));
    }
}
