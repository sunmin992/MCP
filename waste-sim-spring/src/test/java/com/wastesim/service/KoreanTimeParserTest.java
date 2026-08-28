package com.wastesim.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KoreanTimeParserTest {

    @Test
    void digitTime() {
        assertEquals(13 * 60, KoreanTimeParser.parseFirst("13시에 방문하면"));
        assertEquals(9 * 60, KoreanTimeParser.parseFirst("9시 출발"));
    }

    @Test
    void ampmPrefix() {
        assertEquals(13 * 60, KoreanTimeParser.parseFirst("오후 1시 출발"));
        assertEquals(0, KoreanTimeParser.parseFirst("오전 12시 출발"));      // 자정
        assertEquals(9 * 60, KoreanTimeParser.parseFirst("오전 9시 출발"));  // 그대로
    }

    @Test
    void halfAndMinutes() {
        assertEquals(9 * 60 + 30, KoreanTimeParser.parseFirst("아홉시반에 방문"));
        assertEquals(8 * 60 + 30, KoreanTimeParser.parseFirst("8시 30분에 방문"));
    }

    @Test
    void colonFormat() {
        assertEquals(13 * 60 + 30, KoreanTimeParser.parseFirst("13:30 출발"));
    }

    @Test
    void nativeKoreanNumbers() {
        assertEquals(11 * 60, KoreanTimeParser.parseFirst("열한시 출발"));
        assertEquals(12 * 60, KoreanTimeParser.parseFirst("열두시 출발"));
    }

    @Test
    void noTimeReturnsNull() {
        assertNull(KoreanTimeParser.parseFirst("Node_A, Node_C 순서로 방문하면 얼마나 걸려?"));
        assertNull(KoreanTimeParser.parseFirst("한 시간 걸려요"));   // "시간" 제외(TimeExpressionDetector와 동일 어휘)
    }

    @Test
    void toHHMMFormatsWithLeadingZeros() {
        assertEquals("09:05", KoreanTimeParser.toHHMM(9 * 60 + 5));
        assertEquals("00:00", KoreanTimeParser.toHHMM(0));
        assertEquals("23:59", KoreanTimeParser.toHHMM(23 * 60 + 59));
    }

    @Test
    void wrapsPastMidnight() {
        assertEquals("00:30", KoreanTimeParser.toHHMM(1440 + 30));
    }

    @Test
    void parsesDistinctTimesInMentionOrder() {
        assertEquals(List.of(600, 660),
                KoreanTimeParser.parseAllDistinct("10시와 11시에 각각 수거해줘"));
    }

    @Test
    void normalizesAndDeduplicatesEquivalentTimes() {
        assertEquals(List.of(810, 90),
                KoreanTimeParser.parseAllDistinct("오후 1시 30분, 13:30, 새벽 1시 반 비교"));
    }
}
