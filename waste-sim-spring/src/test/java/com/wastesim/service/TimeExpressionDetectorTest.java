package com.wastesim.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** DESIGN_DECISIONS.md D-03 — 이력 시각 승계 금지(이번 메시지 기준만)를 고정한다. */
class TimeExpressionDetectorTest {

    @Test
    void historyTimeIsNotInherited() {   // D-03
        // 이전 메시지에 시각이 있었어도, 이번 메시지 텍스트만으로 카운트한다.
        assertEquals(0, TimeExpressionDetector.count("그럼 그걸로 실행해줘"));   // 시각 없음 → 0
        assertEquals(1, TimeExpressionDetector.count("12시에 실행해줘"));         // 이번 메시지에 1개
    }

    @Test
    void basicDigitTime() {
        assertEquals(1, TimeExpressionDetector.count("12시에 수거해줘"));
        assertEquals(1, TimeExpressionDetector.count("8시반에 수거해줘"));
        assertEquals(1, TimeExpressionDetector.count("오전 8시 30분에 수거해줘"));
    }

    @Test
    void nativeKoreanNumberTime() {
        assertEquals(1, TimeExpressionDetector.count("아홉시에 수거하는 걸로 실행해줘"));
        assertEquals(1, TimeExpressionDetector.count("열두시에 실행해줘"));
        assertEquals(1, TimeExpressionDetector.count("열한시 반에 실행해줘"));
    }

    @Test
    void excludesDurationAndSeedWords() {
        assertEquals(0, TimeExpressionDetector.count("한 시간 걸려요"));
        assertEquals(0, TimeExpressionDetector.count("세 시간 뒤에 다시 알려줘"));
        assertEquals(0, TimeExpressionDetector.count("30일 30시드로 돌려줘"));
    }

    @Test
    void twoDistinctTimesCountsTwo() {
        assertEquals(2, TimeExpressionDetector.count("13시 교통량과 3시 교통량 비교해줘"));
    }

    @Test
    void duplicateTimeCountsOnce() {   // D-01
        assertEquals(1, TimeExpressionDetector.count("12시 12시에 수거해줘"));
    }
}
