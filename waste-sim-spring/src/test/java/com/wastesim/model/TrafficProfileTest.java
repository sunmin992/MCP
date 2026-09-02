package com.wastesim.model;

import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrafficProfileTest {

    @Test
    void weightAtPeakIsRed() {   // UT-T1 — TMAP 실측상 전역 피크는 18시(퇴근), 배수 1.73
        TrafficProfile p = new TrafficDataService().find("jangryang-weekday");
        assertNotNull(p);
        assertEquals(1.73, p.weightAt(18 * 60, null), 0.001);
        assertTrue(p.isRed(18 * 60, null));
    }

    /**
     * 피크가 13시가 아니라는 것 자체를 고정한다. 2026-09-02 TMAP 24시간 실측 전까지 이
     * 프로파일은 통행량에서 유도돼 점심(13시) 피크를 말했는데, 실측 소요시간은 저녁 피크였다.
     * 통행량이 많은 것과 지체가 큰 것은 다르다 — 차선이 많은 간선은 통행량이 많아도 흐른다.
     */
    @Test
    void middayIsNotThePeakAnyMore() {
        TrafficProfile p = new TrafficDataService().find("jangryang-weekday");
        assertNotNull(p);
        assertFalse(p.isRed(13 * 60, null), "13시는 실측상 RED가 아니다");
        assertTrue(p.weightAt(13 * 60, null) < p.weightAt(18 * 60, null),
                "점심이 저녁보다 혼잡하다는 결론은 통행량 기반 프로파일의 것이었다");
    }

    /** 통행량 기반 프로파일은 비교용으로 남아 있고, 그쪽은 여전히 점심 피크를 말한다. */
    @Test
    void volumeProfileIsKeptForComparisonAndStillPeaksAtMidday() {
        TrafficProfile p = new TrafficDataService().find("jangryang-volume-weekday");
        assertNotNull(p, "비교용 통행량 프로파일이 로드되어야 합니다.");
        assertEquals(1.78, p.weightAt(13 * 60, null), 0.001);
        assertTrue(p.isRed(13 * 60, null));
    }

    @Test
    void weightAtOffPeakIsNotRed() {
        TrafficProfile p = new TrafficDataService().find("jangryang-weekday");
        assertNotNull(p);
        assertFalse(p.isRed(3 * 60, null));   // 03:00 — 한산
    }

    @Test
    void unknownProfileIdReturnsNull() {
        assertNull(new TrafficDataService().find("does-not-exist"));
    }
}
