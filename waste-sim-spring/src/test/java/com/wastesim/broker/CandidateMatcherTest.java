package com.wastesim.broker;

import com.wastesim.capability.CapabilityCardLoader;
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
            new CandidateRegistry(new CapabilityCardLoader().rawJson(),
                    "classpath*:/mcp/candidates/*.json"));

    /** 명세 1단계 발화에서 뽑은 프로필. */
    private RequestProfile 장량동_요청() {
        return new RequestProfile("쓰레기수거", "한 동네", List.of("평일 교통량"),
                "민원이 가장 적은 수거 시각", List.of("수거 시각"));
    }

    @Test
    void 장량동이_일등이고_후보A가_이등이다() {
        List<MatchResult> r = matcher.match(장량동_요청());
        assertEquals(List.of("jangnyang-waste-sim", "district-waste-sim"),
                r.stream().map(MatchResult::serverId).toList());
        assertTrue(r.get(0).score() > r.get(1).score(),
                "점수가 같으면 무엇 때문에 이겼는지 말할 수 없다");
    }

    @Test
    void 도메인이_다른_후보는_아예_없다() {
        List<String> ids = matcher.match(장량동_요청()).stream().map(MatchResult::serverId).toList();
        assertFalse(ids.contains("water-leak-sim"),
                "도메인이 다른 서버는 다른 축이 아무리 맞아도 답을 낼 수 없다 — 점수로 깎지 않고 뺀다");
    }

    @Test
    void 일등의_근거에_규모와_교통이_둘_다_적힌다() {
        MatchResult top = matcher.match(장량동_요청()).get(0);
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
    void 점수가_같으면_순서가_흔들리지_않는다() {
        RequestProfile p = new RequestProfile("쓰레기수거", null, null, null, null);
        assertEquals(matcher.match(p).stream().map(MatchResult::serverId).toList(),
                matcher.match(p).stream().map(MatchResult::serverId).toList());
        // 점수 동률이면 serverId 오름차순
        List<MatchResult> r = matcher.match(p);
        if (r.get(0).score() == r.get(1).score()) {
            assertTrue(r.get(0).serverId().compareTo(r.get(1).serverId()) < 0,
                    "동률 순서가 환경에 따라 달라지면 같은 요청이 같은 답을 낸다고 말할 수 없다");
        }
    }

    @Test
    void 미제시_조건은_근거로도_어긋남으로도_세지_않는다() {
        MatchResult top = matcher.match(
                new RequestProfile("쓰레기수거", null, null, null, null)).get(0);
        assertFalse(String.join(" ", top.reasons()).contains("null"));
        assertTrue(top.mismatches().isEmpty(),
                "묻지 않아 모르는 것을 어긋난 점으로 세면 서버가 못하는 일로 둔갑한다");
    }
}
