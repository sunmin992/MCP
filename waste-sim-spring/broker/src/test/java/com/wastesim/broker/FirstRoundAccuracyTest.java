package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 첫 조회만으로 맞는 서버가 걸리는가 — 되묻기 전의 정확도.
 *
 * <p>정답 모음은 V2 실험 A 조건의 첫 조회 54건이다. 라벨은 초안이라 확정(confirmed)된 줄만
 * 합격 기준으로 삼는다. 확정이 0개이면 첫 시험은 0 = 0 으로 지나간다 — 의도된 것이다.
 * 이후 규칙을 바꾸는 Task 가 {@link #measure} 를 다시 돌려 숫자를 비교한다.
 */
class FirstRoundAccuracyTest {

    /**
     * @param total              센 줄 수
     * @param falsePositives     요청을 그대로 받는다고 걸렸는데 기대 서버가 아닌 줄(가상 후보 제외)
     * @param misses             기대 서버가 그대로는 못 받고 조정이 필요한 줄
     * @param asked              기대 서버가 애매함만으로 막혀 되물어야 하는 줄
     * @param falsePositiveCases 잘못 걸린 줄의 runId 와 서버
     */
    record Score(int total, int falsePositives, int misses, int asked, List<String> falsePositiveCases) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateRegistry registry = registry();
    private final CandidateMatcher matcher = new CandidateMatcher(registry);

    private static CandidateRegistry registry() {
        CandidateRegistry r = new CandidateRegistry(TestCards.jangnyang(),
                "classpath*:/mcp/candidates/*.json");
        r.register(TestCards.district());
        r.register(TestCards.parking());
        return r;
    }

    private List<JsonNode> all() {
        try {
            List<JsonNode> rows = new ArrayList<>();
            mapper.readTree(new File("src/test/resources/eval/first-round-gold.json")).forEach(rows::add);
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<JsonNode> confirmedOnly() {
        return all().stream().filter(r -> r.path("confirmed").asBoolean()).toList();
    }

    private static List<String> strings(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(e -> out.add(e.asText()));
        else out.add(n.asText());
        return out;
    }

    private static RequestProfile profile(JsonNode p) {
        return new RequestProfile(
                p.path("domain").asText(null),
                p.path("spatialScale").asText(null),
                strings(p.path("environmentConditions")),
                p.path("objective").asText(null),
                strings(p.path("comparisonAxes")),
                strings(p.path("population")));
    }

    static Score measure(CandidateMatcher matcher, List<JsonNode> rows) {
        int fp = 0, miss = 0, asked = 0;
        List<String> fpCases = new ArrayList<>();
        for (JsonNode row : rows) {
            List<String> expected = new ArrayList<>();
            row.path("expectedServers").forEach(e -> expected.add(e.asText()));
            List<MatchResult> results = matcher.match(profile(row.path("profileSent")));
            for (MatchResult m : results) {
                if (m.fitsAsIs() && !m.fictional() && !expected.contains(m.serverId())) {
                    fp++;
                    fpCases.add(row.path("runId").asText() + " → " + m.serverId());
                }
            }
            // Task 2 전에는 분류가 없다 — 조정이 하나라도 있으면 모두 불일치로 센다.
            // 되묻기(애매만으로 막힘)는 Task 2 전에는 0.
            for (String id : expected) {
                MatchResult m = results.stream().filter(r -> r.serverId().equals(id)).findFirst().orElse(null);
                if (m == null || (!m.fitsAsIs() && !m.requestAdjustments().isEmpty())) miss++;
            }
        }
        return new Score(rows.size(), fp, miss, asked, fpCases);
    }

    @Test
    void 확정된_정답에서_잘못_걸림이_없다() {
        Score s = measure(matcher, confirmedOnly());
        assertEquals(0, s.falsePositives(), "잘못 걸림: " + s.falsePositiveCases());
    }

    @Test
    void 기준선을_출력한다() {
        System.out.println(measure(matcher, all()));
    }
}
