package com.wastesim.broker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 브로커가 <b>근거와 함께</b> 고르는가.
 *
 * <p>후보가 하나뿐일 때는 물을 수 없던 질문이다 — 무엇을 고르든 정답이었다. 도메인이 같은
 * 둘 사이에서 갈리고, 왜 갈렸는지 말할 수 있어야 매칭이라 부를 수 있다.
 */
class CandidateMatcherTest {

    private final CandidateMatcher matcher = new CandidateMatcher(
            new CandidateRegistry(TestCards.jangnyang(),
                    "classpath*:/mcp/candidates/*.json"));

    /** 명세 1단계 발화에서 뽑은 프로필. */
    private RequestProfile 장량동_요청() {
        return new RequestProfile("쓰레기수거", "한 동네", List.of("평일 교통량"),
                "민원이 가장 적은 수거 시각", List.of("수거 시각"));
    }

    private RequestProfile 거주민_요청(String... population) {
        return new RequestProfile("쓰레기수거", "한 동네", null, null, null, List.of(population));
    }

    private MatchResult byId(List<MatchResult> results, String serverId) {
        return results.stream().filter(m -> m.serverId().equals(serverId)).findFirst()
                .orElseThrow(() -> new AssertionError(serverId + " 가 결과에 없다"));
    }

    @Test
    void 장량동은_그대로_맞고_후보A는_어긋난_점이_있다() {
        List<MatchResult> r = matcher.match(장량동_요청());
        assertTrue(byId(r, "jangnyang-waste-sim").fitsAsIs());
        assertFalse(byId(r, "district-waste-sim").fitsAsIs(),
                "점수가 없으니 갈리는 것은 어긋난 점이 있느냐다");
    }

    @Test
    void 도메인이_다른_후보는_아예_없다() {
        List<String> ids = matcher.match(장량동_요청()).stream().map(MatchResult::serverId).toList();
        assertFalse(ids.contains("water-leak-sim"),
                "도메인이 다른 서버는 다른 축이 아무리 맞아도 답을 낼 수 없다 — 점수로 깎지 않고 뺀다");
    }

    @Test
    void 장량동의_근거에_규모와_교통이_둘_다_적힌다() {
        MatchResult top = byId(matcher.match(장량동_요청()), "jangnyang-waste-sim");
        String 근거 = String.join(" | ", top.reasons());
        assertTrue(근거.contains("한 동네"), "요청의 어느 항목이 맞았는지 없다: " + 근거);
        assertTrue(근거.contains("평일 교통량"), "교통 근거가 없다: " + 근거);
        assertTrue(근거.contains("원룸촌") || 근거.contains("block"),
                "카드의 어느 칸과 맞았는지 없다 — 한쪽만 적으면 근거가 아니라 결론이다: " + 근거);
    }

    @Test
    void 후보A는_교통_미지원이_어긋난_점으로_적힌다() {
        MatchResult a = matcher.match(장량동_요청()).stream()
                .filter(m -> m.serverId().equals("district-waste-sim")).findFirst().orElseThrow();
        String 어긋남 = String.join(" | ", a.mismatches());
        assertTrue(어긋남.contains("교통"),
                "점수만 깎고 넘어가면 '왜 저 서버가 아닌가' 를 되물을 수 없다: " + 어긋남);
    }

    @Test
    void 상수도_요청에는_후보B만_남는다() {
        List<MatchResult> r = matcher.match(
                new RequestProfile("상수도 누수", null, null, "누수 지점을 찾고 싶다", null));
        assertEquals(List.of("water-leak-sim"), r.stream().map(MatchResult::serverId).toList());
    }

    @Test
    void 아무_도메인도_안_맞으면_빈_목록이다() {
        List<MatchResult> r = matcher.match(
                new RequestProfile("교통 신호 최적화", "교차로", null, null, null));
        assertEquals(List.of(), r, "억지로 일등을 만들면 사용자가 엉뚱한 서버로 간다");
    }

    @Test
    void 조건을_안_준_요청도_도메인만으로_후보를_낸다() {
        List<MatchResult> r = matcher.match(new RequestProfile("쓰레기수거", null, null, null, null));
        assertEquals(2, r.size());
        assertEquals(List.of("district-waste-sim", "jangnyang-waste-sim"),
                r.stream().map(MatchResult::serverId).sorted().toList());
    }

    @Test
    void 순서는_서버_id_오름차순이다() {
        // 조건이 갈리는 요청으로 본다 — 조건이 없으면 예전 점수도 동점이라 이 시험이 아무것도 가르지 못한다.
        List<String> ids = matcher.match(장량동_요청()).stream().map(MatchResult::serverId).toList();
        assertEquals(List.of("district-waste-sim", "jangnyang-waste-sim"), ids,
                "순서에 추천의 뜻이 없다 — 환경에 따라 흔들리지 않게 id 로 고정한다");
    }

    @Test
    void 미제시_조건은_근거로도_어긋남으로도_세지_않는다() {
        MatchResult top = matcher.match(
                new RequestProfile("쓰레기수거", null, null, null, null)).get(0);
        assertFalse(String.join(" ", top.reasons()).contains("null"));
        assertTrue(top.mismatches().isEmpty(),
                "묻지 않아 모르는 것을 어긋난 점으로 세면 서버가 못하는 일로 둔갑한다");
    }

    @Test
    void 거주민_조건이_맞으면_근거에_유형이_적힌다() {
        MatchResult j = byId(matcher.match(거주민_요청("학생이 많음", "주부가 많음")), "jangnyang-waste-sim");
        assertTrue(j.fitsAsIs(), j.mismatches().toString());
        String 근거 = String.join(" | ", j.reasons());
        assertTrue(근거.contains("학생이 많음") && 근거.contains("Student"), 근거);
        assertTrue(근거.contains("주부가 많음") && 근거.contains("Housewife"), 근거);
    }

    @Test
    void 생산직도_거주민으로_받는다() {
        MatchResult j = byId(matcher.match(거주민_요청("생산직이 많음")), "jangnyang-waste-sim");
        assertTrue(j.fitsAsIs(), j.mismatches().toString());
        assertTrue(String.join(" | ", j.reasons()).contains("BlueCollar"));
    }

    @Test
    void 한_구절에_직업이_둘이어도_통과한다() {
        MatchResult j = byId(matcher.match(거주민_요청("학생과 주부가 많음")), "jangnyang-waste-sim");
        assertTrue(j.fitsAsIs(), j.mismatches().toString());
    }

    @Test
    void 거주민을_적지_않은_카드는_거주민_조건에서_어긋난다() {
        MatchResult d = byId(matcher.match(거주민_요청("학생이 많음")), "district-waste-sim");
        assertTrue(String.join(" | ", d.mismatches()).contains("적지 않았습니다"), d.mismatches().toString());
    }

    @Test
    void 모델링하지_않는_거주민이면_다루는_거주민을_조정_제안으로_보인다() {
        MatchResult j = byId(matcher.match(거주민_요청("노인이 많음")), "jangnyang-waste-sim");
        assertFalse(j.fitsAsIs());
        RequestAdjustment a = j.requestAdjustments().stream()
                .filter(x -> x.axis().equals("population")).findFirst().orElseThrow();
        assertEquals("노인이 많음", a.current());
        assertTrue(a.suggestion().contains("생산직") && a.suggestion().contains("학생"), a.suggestion());
        assertFalse(a.changesPurpose());
    }

    @Test
    void 거주민을_말하지_않으면_거주민으로_거르지_않는다() {
        MatchResult j = byId(matcher.match(new RequestProfile("쓰레기수거", "한 동네", null, null, null)),
                "jangnyang-waste-sim");
        assertTrue(j.requestAdjustments().stream().noneMatch(a -> a.axis().equals("population")));
    }

    @Test
    void 거주민이_빈_목록이면_거르지_않는다() {
        MatchResult d = byId(matcher.match(거주민_요청()), "district-waste-sim");
        assertTrue(d.requestAdjustments().stream().noneMatch(a -> a.axis().equals("population")),
                "'거주민 조건 없음' 을 '거주민을 못 다룸' 으로 세면 안 된다");
    }
}
