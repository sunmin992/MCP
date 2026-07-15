package com.wastesim.model;

import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrafficProfileTest {

    @Test
    void weightAtPeakIsRed() {   // UT-T1 — 실측 데이터상 전역 피크는 13시(점심), 가중치 1.78
        TrafficProfile p = new TrafficDataService().find("jangryang-weekday");
        assertNotNull(p);
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
