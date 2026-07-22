package com.wastesim.model;

import com.wastesim.service.OpenAiService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** DESIGN_DECISIONS.md D-02 — 수거 시각 문자열 정규화(트림 허용, HH:MM 강제). */
class SimulationConfigTest {

    @Test
    void isValidCollectionTimeRequiresZeroPaddedHhmm() {
        assertTrue(OpenAiService.isValidCollectionTime("08:30"));
        assertTrue(OpenAiService.isValidCollectionTime("00:00"));
        assertTrue(OpenAiService.isValidCollectionTime("23:59"));
        assertFalse(OpenAiService.isValidCollectionTime("8:30"));    // 한 자리 시 무효(D-02)
        assertFalse(OpenAiService.isValidCollectionTime("24:00"));
        assertFalse(OpenAiService.isValidCollectionTime(null));
        assertFalse(OpenAiService.isValidCollectionTime(""));
    }

    @Test
    void collectionTimeLabelTrimsWhitespace() {
        SimulationConfig c = new SimulationConfig();
        c.setCollectionTimeLabel("  12:00  ");
        assertEquals("12:00", c.getCollectionTimeLabel());
    }

    @Test
    void collectionTimeLabelRoundTripsThroughMinutes() {
        SimulationConfig c = new SimulationConfig();
        c.setCollectionTimeLabel("8:30");   // 파싱은 관대(한 자리도 허용)하지만
        assertEquals("08:30", c.getCollectionTimeLabel());   // 다시 읽으면 항상 정규화된 형태
    }
}
