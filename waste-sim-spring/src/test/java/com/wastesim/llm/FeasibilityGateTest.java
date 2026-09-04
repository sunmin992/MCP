package com.wastesim.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 만들 수 없는 요청을 거부한다.
 *
 * <p>판정은 <b>LLM이 아니라 코드</b>가 한다. LLM은 "부산"이라는 단어를 뽑을 뿐이고
 * "부산은 지원하지 않는다"는 여기서 정한다 — 그래야 거부 동작을 테스트로 고정할 수 있다.
 *
 * <p>거부는 "안 됩니다"에서 끝나지 않는다. 통째로 막으면 사용자가 우회로가 있다는 것을
 * 모른다.
 */
class FeasibilityGateTest {

    private static RequestExtraction req(String region, String domain, String conclusion) {
        return new RequestExtraction(List.of(), region, domain, conclusion);
    }

    /** 다른 지역은 거부한다. */
    @Test
    void rejectsOtherRegions() {
        FeasibilityVerdict v = FeasibilityGate.judge(req("부산", null, null));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.OUT_OF_REGION, v.reason());
    }

    /**
     * <b>지역이 비었을 때는 거부하지 않는다.</b> "시뮬레이터 만들어 줘"처럼 지역을 생략한
     * 요청이 정상이므로, 침묵을 거부 근거로 쓰지 않는다.
     */
    @Test
    void silenceAboutRegionIsNotGroundsForRefusal() {
        assertTrue(FeasibilityGate.judge(req(null, null, null)).feasible());
        assertTrue(FeasibilityGate.judge(req("", null, null)).feasible());
    }

    /** 장량동·포항은 통과한다. */
    @Test
    void acceptsTheSupportedRegion() {
        assertTrue(FeasibilityGate.judge(req("장량동", null, null)).feasible());
        assertTrue(FeasibilityGate.judge(req("포항시 북구 장량동", null, null)).feasible());
    }

    /** 모델에 없는 축은 거부한다. */
    @Test
    void rejectsAxesNotInTheModel() {
        FeasibilityVerdict v = FeasibilityGate.judge(
                req(null, null, "종량제 봉투 가격을 올리면 배출량이 줄어드는가"));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.AXIS_NOT_IN_MODEL, v.reason());
    }

    /** 지점 단위 결론은 데이터가 없어 거부하되 구역 단위 대안을 안내한다. */
    @Test
    void rejectsSiteLevelConclusionsButOffersTheZoneAlternative() {
        FeasibilityVerdict v = FeasibilityGate.judge(
                req(null, null, "수거 지점 단위 최적 경로를 찾아줘"));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.DATA_UNAVAILABLE, v.reason());
        assertTrue(v.whatWouldBeNeeded().stream()
                        .anyMatch(m -> m.note() != null && m.note().contains("ZONE_PROXY_HYBRID")),
                "통째로 막으면 사용자가 우회로가 있다는 것을 모른다: " + v.whatWouldBeNeeded());
    }

    /** 조회성 요청은 시뮬레이션이 아니다. */
    @Test
    void rejectsLookupRequests() {
        FeasibilityVerdict v = FeasibilityGate.judge(
                req(null, null, "장량동 쓰레기 배출량 알려줘"));
        assertFalse(v.feasible());
        assertEquals(FeasibilityVerdict.Reason.NOT_A_SIMULATION, v.reason());
    }

    /** <b>모든 거부는 부족한 것 목록을 함께 낸다.</b> */
    @Test
    void everyRefusalCarriesWhatWouldBeNeeded() {
        List<RequestExtraction> refused = List.of(
                req("부산", null, null),
                req(null, null, "종량제 봉투 가격을 올리면"),
                req(null, null, "수거 지점 단위 최적 경로"),
                req(null, null, "장량동 배출량 알려줘"));

        for (RequestExtraction r : refused) {
            FeasibilityVerdict v = FeasibilityGate.judge(r);
            assertFalse(v.feasible());
            assertFalse(v.whatWouldBeNeeded().isEmpty(),
                    "'안 됩니다'로 끝나는 거부는 사용자가 다음에 무엇을 할지 모른다: " + v.reason());
            assertNotNull(v.message());
        }
    }

    /** 지역 거부 목록은 자동 수집 가능한 것과 아닌 것을 갈라 준다. */
    @Test
    void regionRefusalSeparatesObtainableFromNot() {
        FeasibilityVerdict v = FeasibilityGate.judge(req("부산", null, null));
        assertTrue(v.whatWouldBeNeeded().stream().anyMatch(FeasibilityVerdict.Missing::obtainable),
                "자동 수집 가능한 항목이 있어야 다음 작업의 재료가 된다");
        assertTrue(v.whatWouldBeNeeded().stream().anyMatch(m -> !m.obtainable()),
                "사람이 채워야 하는 항목을 숨기면 자동으로 될 것처럼 읽힌다");
    }

    /** 통과한 판정에는 거부 사유가 없다. */
    @Test
    void feasibleVerdictHasNoReason() {
        FeasibilityVerdict v = FeasibilityGate.judge(req("장량동", null, null));
        assertTrue(v.feasible());
        assertNull(v.reason());
        assertEquals(List.of(), v.whatWouldBeNeeded());
    }
}
