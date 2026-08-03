package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 비교 결과의 결론 한 줄이 <b>실제로 갈린 지표</b>를 짚는지 검증한다.
 *
 * <p>기준 지표를 정상상태로 고정하면 안 된다 — 열저항이 같은 비교(재질·질량만 다름)는
 * 정상상태가 아예 동일해서(정상상태 식에 열용량이 없다) "0℃ 더 뜨겁다"는 무의미한
 * 문장이 나온다. 실측으로 재현된 회귀라 케이스로 고정한다.
 */
class EdgeComparisonVerdictTest {

    /** 도구 출력 모양을 최소한으로 흉내 낸다(포매터가 읽는 키만). */
    private Map<String, Object> run(String board, Double ttt, double steady, double peak) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tttSec", ttt);
        m.put("softLimitEntrySec", null);
        m.put("steadyStateTempC", steady);
        m.put("peakTempC", peak);
        m.put("meanFpsDuringLoad", 10.0);
        m.put("fpsDropPercent", 0.0);
        m.put("tauHeatingSec", 58.5);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("board", board);
        out.put("cooling", "passive");
        out.put("workloadMode", "max_throughput");
        out.put("loadSeconds", 900.0);
        out.put("metrics", m);
        out.put("notes", List.of());
        return out;
    }

    @Test
    @DisplayName("정상상태가 같으면 피크 온도로 판정한다 — 재질 비교의 회귀 케이스")
    void tiedSteadyStateFallsBackToPeak() {
        String text = EdgeChatFormatter.materialComparison(List.of(
                run("Raspberry Pi 5", null, 43.2, 45.8),    // 알루미늄 — 열용량 큼
                run("Raspberry Pi 5", null, 43.2, 46.5)));   // 구리 — 열용량 작음

        assertFalse(text.contains("0℃ 더 뜨겁"), "0℃ 차이라는 문장이 나오면 안 된다:\n" + text);
        assertTrue(text.contains("피크 온도"), "피크로 판정해야 한다:\n" + text);
        assertTrue(text.contains("구리"), "더 뜨거운 쪽을 지목해야 한다:\n" + text);
        assertTrue(text.contains("0.7℃"), "피크 차이를 수치로 적어야 한다:\n" + text);
        assertTrue(text.contains("열용량"), "왜 갈렸는지 설명해야 한다:\n" + text);
    }

    @Test
    @DisplayName("정상상태도 피크도 같으면 조건을 바꾸라고 안내한다")
    void bothTiedTellsUserToChangeConditions() {
        String text = EdgeChatFormatter.materialComparison(List.of(
                run("Raspberry Pi 5", null, 43.2, 45.8),
                run("Raspberry Pi 5", null, 43.2, 45.82)));
        // "0℃"만 찾으면 "소프트 제한(80℃)"에 걸린다 — 문제였던 문구를 정확히 짚는다.
        assertFalse(text.contains("0℃ 더 뜨겁") || text.contains("0℃ 차이"), text);
        assertTrue(text.contains("사실상 같다"), text);
        assertTrue(text.contains("burst"), "부하 패턴을 넣으라고 안내해야 한다:\n" + text);
    }

    @Test
    @DisplayName("열저항이 다르면 기존대로 정상상태로 판정한다 — 보드 비교는 그대로")
    void differingSteadyStateStillJudgedBySteady() {
        String text = EdgeChatFormatter.boardComparison(List.of(
                run("Raspberry Pi 4B", null, 60.0, 62.0),
                run("Raspberry Pi 5", null, 76.8, 79.0)));
        assertTrue(text.contains("더 뜨겁게 안정된다"), text);
        assertTrue(text.contains("16.8℃"), "정상상태 차이를 적어야 한다:\n" + text);
        assertTrue(text.contains("Raspberry Pi 5"), text);
    }

    @Test
    @DisplayName("둘 다 스로틀링에 걸리면 TTT로 판정하고, 정상상태가 같을 때 0℃라고 하지 않는다")
    void bothThrottledReportsTttAndAvoidsZeroDelta() {
        String text = EdgeChatFormatter.materialComparison(List.of(
                run("Raspberry Pi 5", 150.0, 103.0, 85.0),
                run("Raspberry Pi 5", 120.0, 103.0, 85.0)));
        assertTrue(text.contains("먼저 스로틀링에 걸린다"), text);
        assertTrue(text.contains("양쪽 103℃로 같다") || text.contains("양쪽 103.0℃로 같다"), text);
        assertFalse(text.contains("0℃ 차이"), text);
    }

    // ── 한국어 조사 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("받침에 따라 조사를 고른다 — '팬 없음가' 같은 문장을 막는다")
    void particleFollowsFinalConsonant() {
        assertEquals("이", EdgeChatFormatter.particle("팬 없음", "이", "가"));   // 음 — 받침 O
        assertEquals("이", EdgeChatFormatter.particle("팬 가동", "이", "가"));   // 동 — 받침 O
        assertEquals("이", EdgeChatFormatter.particle("알루미늄", "이", "가"));  // 늄 — 받침 O
        assertEquals("가", EdgeChatFormatter.particle("구리", "이", "가"));      // 리 — 받침 X
        assertEquals("는", EdgeChatFormatter.particle("구리", "은", "는"));
    }

    @Test
    @DisplayName("숫자로 끝나면 읽는 소리의 받침을 따른다 — Pi 5는 '오'라 받침이 없다")
    void particleHandlesTrailingDigits() {
        assertEquals("가", EdgeChatFormatter.particle("Raspberry Pi 5", "이", "가"));   // 오
        assertEquals("가", EdgeChatFormatter.particle("Raspberry Pi 4", "이", "가"));   // 사
        assertEquals("이", EdgeChatFormatter.particle("후보 3", "이", "가"));            // 삼
        assertEquals("이", EdgeChatFormatter.particle("후보 1", "이", "가"));            // 일
        assertEquals("가", EdgeChatFormatter.particle(null, "이", "가"));
    }

    @Test
    @DisplayName("결론 문장에 조사가 실제로 적용된다")
    void verdictUsesCorrectParticle() {
        String text = EdgeChatFormatter.fanComparison(List.of(
                run("Raspberry Pi 5", null, 76.8, 76.8),
                run("Raspberry Pi 5", null, 61.2, 61.2)));
        assertTrue(text.contains("팬 없음이"), "받침 있는 라벨에는 '이':\n" + text);
        assertFalse(text.contains("팬 없음가"), text);
        assertFalse(text.contains("팬 가동가"), text);
    }

    @Test
    @DisplayName("한쪽만 걸리면 경계선이라고 알려준다(보드에 국한된 문구를 쓰지 않는다)")
    void onlyOneThrottledIsCalledABoundary() {
        String text = EdgeChatFormatter.materialComparison(List.of(
                run("Raspberry Pi 5", 200.0, 88.0, 85.0),
                run("Raspberry Pi 5", null, 88.0, 79.0)));
        assertTrue(text.contains("경계선"), text);
        assertFalse(text.contains("두 보드의"), "재질 비교인데 '보드'라고 쓰면 안 된다:\n" + text);
    }
}
