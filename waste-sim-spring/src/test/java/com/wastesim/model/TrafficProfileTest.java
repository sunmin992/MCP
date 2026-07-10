package com.wastesim.model;

import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrafficProfileTest {

    @Test
    void weightAtPeakIsRed() {   // UT-T1
        TrafficProfile p = new TrafficDataService().find("jangryang-weekday");
        assertNotNull(p);
        assertEquals(2.2, p.weightAt(8 * 60 + 30, null), 0.001);
        assertTrue(p.isRed(8 * 60 + 30, null));
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
