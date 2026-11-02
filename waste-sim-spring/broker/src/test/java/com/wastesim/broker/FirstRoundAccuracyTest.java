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
     * @param misses             기대 서버가 결과에 없거나, 불일치 조정이 하나라도 있어 막힌 줄
     * @param asked              기대 서버가 애매함만으로 막혀 되물어야 하는 줄
     * @param falsePositiveCases 잘못 걸린 줄의 runId 와 서버
     * @param missCases          놓친 줄의 runId 와 서버
     * @param askedCases         되물어야 하는 줄의 runId 와 서버
     */
    record Score(int total, int falsePositives, int misses, int asked, List<String> falsePositiveCases,
                 List<String> missCases, List<String> askedCases) {}

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
        return measure(matcher, rows, "profileSent");
    }

    /** field 는 조회에 쓸 프로필 칸: profileSent(대화에서 만든 것) 또는 goldProfile(요청 문장대로 쓴 것). */
    static Score measure(CandidateMatcher matcher, List<JsonNode> rows, String field) {
        int fp = 0, miss = 0, asked = 0;
        List<String> fpCases = new ArrayList<>();
        List<String> missCases = new ArrayList<>();
        List<String> askedCases = new ArrayList<>();
        for (JsonNode row : rows) {
            List<String> expected = new ArrayList<>();
            row.path("expectedServers").forEach(e -> expected.add(e.asText()));
            List<MatchResult> results = matcher.match(profile(row.path(field)));
            for (MatchResult m : results) {
                if (m.fitsAsIs() && !m.fictional() && !expected.contains(m.serverId())) {
                    fp++;
                    fpCases.add(row.path("runId").asText() + " → " + m.serverId());
                }
            }
            // 놓침: 기대 서버가 없거나 불일치 조정으로 막힘. 되묻기: 애매함만으로 막힘.
            for (String id : expected) {
                MatchResult m = results.stream().filter(r -> r.serverId().equals(id)).findFirst().orElse(null);
                boolean mismatched = m != null && m.requestAdjustments().stream()
                        .anyMatch(a -> a.classification() == Classification.MISMATCH);
                if (m == null || (!m.fitsAsIs() && mismatched)) {
                    miss++;
                    missCases.add(row.path("runId").asText() + " → " + id);
                } else if (m.blockedOnlyByAmbiguity()) {
                    asked++;
                    askedCases.add(row.path("runId").asText() + " → " + id);
                }
            }
        }
        return new Score(rows.size(), fp, miss, asked, fpCases, missCases, askedCases);
    }

    @Test
    void 확정된_정답에서_잘못_걸림이_없다() {
        Score s = measure(matcher, confirmedOnly());
        assertEquals(0, s.falsePositives(), "잘못 걸림: " + s.falsePositiveCases());
    }

    @Test
    void 기준선을_출력한다() {
        Score s = measure(matcher, all());
        assertEquals(54, s.total(), "정답 모음이 비었거나 잘렸다");
        System.out.println(s);
    }

    @Test
    void 대조_층_정확도를_출력한다() {
        // 모든 정답 프로필이 유효한 요청(도메인 있음)이어야 한다 — 아니면 여기서 예외로 실패한다.
        all().forEach(r -> profile(r.path("goldProfile")));
        Score s = measure(matcher, all(), "goldProfile");
        assertEquals(54, s.total());
        System.out.println("대조 층(goldProfile) " + s);
    }

    private static final List<String> FIELDS = List.of("domain", "spatialScale", "environmentConditions",
            "objective", "comparisonAxes", "population");

    /** 둘 다 비었으면 일치. 한쪽만 비면 불일치. 둘 다 있으면 공백을 뺀 글이 같거나 한쪽이 다른 쪽을 품을 때 일치. */
    private static boolean sameField(JsonNode a, JsonNode b) {
        List<String> x = strings(a), y = strings(b);
        boolean xe = x == null || x.stream().allMatch(String::isBlank);
        boolean ye = y == null || y.stream().allMatch(String::isBlank);
        if (xe || ye) return xe && ye;
        String p = String.join("|", x).replaceAll("\s", ""), q = String.join("|", y).replaceAll("\s", "");
        return p.contains(q) || q.contains(p);
    }

    @Test
    void 해석_층_칸별_일치율을_출력한다() {
        List<JsonNode> rows = all();
        assertEquals(54, rows.size());
        StringBuilder out = new StringBuilder("해석 층(profileSent 대 goldProfile) 칸별 일치");
        for (String f : FIELDS) {
            long n = rows.stream().filter(r -> sameField(r.path("profileSent").path(f), r.path("goldProfile").path(f))).count();
            out.append(" · ").append(f).append(' ').append(n).append('/').append(rows.size());
        }
        System.out.println(out);
    }
}
