package com.wastesim.edge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.service.DomainIntentDetector;
import com.wastesim.service.EdgeToolSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 학생에게 배포할 "채팅 예문 목록"이 실제로 의도한 실험으로 라우팅되는지 고정한다.
 *
 * <p>예문은 문서에만 적어 두면 정규식이 바뀌는 순간 조용히 틀린 안내가 된다 —
 * 문장이 다른 도구로 새거나 조건 하나가 빠져도 학생은 결과를 보고 알아채기 어렵다.
 * 그래서 배포 문구 그대로를 테스트에 넣어 회귀로 잡는다.
 */
class ChatPhraseCatalogTest {

    private void check(String text, String expectedTool, String... expectedPairs) {
        assertEquals(DomainIntentDetector.Domain.EDGE_THERMAL,
                DomainIntentDetector.classify(text), "엣지 도메인이어야 한다: " + text);
        assertEquals(expectedTool, EdgeToolSelector.select(text), "도구 라우팅: " + text);
        ObjectNode n = EdgeParamGuard.fromText(text);
        for (int i = 0; i < expectedPairs.length; i += 2) {
            String field = expectedPairs[i], want = expectedPairs[i + 1];
            assertTrue(n.hasNonNull(field), "'" + field + "'를 못 뽑았다: " + text);
            assertEquals(want, n.get(field).asText(), field + " 판정: " + text);
        }
    }

    // ── 1. 기본 발열 실험 ────────────────────────────────────────────────

    @Test
    void basicThrottlingPhrases() {
        check("라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?",
                EdgeToolSelector.TOOL_THROTTLING, "board", "pi5", "cooling", "bare");
        check("pi5 방열판 달고 최대 처리량으로 돌리면 TTT가 얼마야?",
                EdgeToolSelector.TOOL_THROTTLING,
                "board", "pi5", "cooling", "passive", "workloadMode", "max_throughput");
        check("라즈베리파이 4 무냉각, 주변 온도 35도로 시뮬레이션해줘",
                EdgeToolSelector.TOOL_THROTTLING, "board", "pi4", "cooling", "bare");
        check("pi5에 팬 달고 15fps로 돌리면 어떻게 돼?",
                EdgeToolSelector.TOOL_THROTTLING,
                "board", "pi5", "cooling", "active", "workloadMode", "target_fps");
    }

    @Test
    void ambientTemperatureIsRead() {
        ObjectNode n = EdgeParamGuard.fromText("라즈베리파이 5 무냉각, 주변 온도 45도로 20분");
        assertEquals(45.0, n.get("ambientTempC").asDouble(), 1e-9);
    }

    // ── 2. AI 부하 패턴 (이번에 추가) ────────────────────────────────────

    @Test
    void aiLoadPatternPhrases() {
        check("라즈베리파이 5 방열판으로 버스트 부하 20분 돌려줘",
                EdgeToolSelector.TOOL_THROTTLING,
                "board", "pi5", "cooling", "passive", "aiLoadProfileId", "burst");
        check("pi5 무냉각에서 실사용 패턴으로 돌리면 스로틀링 걸려?",
                EdgeToolSelector.TOOL_THROTTLING,
                "board", "pi5", "cooling", "bare", "aiLoadProfileId", "mixed");
        check("pi5 방열판, 일정한 부하로 대조군 실행해줘",
                EdgeToolSelector.TOOL_THROTTLING,
                "board", "pi5", "cooling", "passive", "aiLoadProfileId", "steady");
    }

    @Test
    void aiLoadPatternWithHeatsinkComparison() {
        check("pi5에 방열판 어디에 붙여야 제일 시원한지 버스트 부하 조건으로 비교해줘",
                EdgeToolSelector.TOOL_HEATSINK, "board", "pi5", "aiLoadProfileId", "burst");
        check("실사용 패턴에서 방열판 배치 순위가 어떻게 되는지 비교해줘",
                EdgeToolSelector.TOOL_HEATSINK, "aiLoadProfileId", "mixed");
    }

    // ── 3. 회복 정책(TRT) ───────────────────────────────────────────────

    @Test
    void recoveryPolicyPhrases() {
        check("스로틀링 걸린 다음 팬 100%로 켜면 얼마나 빨리 회복돼?",
                EdgeToolSelector.TOOL_THROTTLING, "recoveryPolicy", "r3_active_cooling");
        check("스로틀링 걸리면 추론을 완전 중지했을 때 회복 시간은?",
                EdgeToolSelector.TOOL_THROTTLING, "recoveryPolicy", "r1_stop");
        check("스로틀링 후 저부하로 낮추면 얼마나 걸려 회복돼?",
                EdgeToolSelector.TOOL_THROTTLING, "recoveryPolicy", "r2_low_load");
    }

    /** R3에서 "팬"은 회복 조치지 부하 구간의 냉각 조건이 아니다 — 처음부터 팬이 돌면
     *  스로틀링이 안 걸려서 정작 물어본 회복 시간을 못 잰다. */
    @Test
    void fanInRecoveryContextDoesNotBecomeLoadPhaseCooling() {
        ObjectNode n = EdgeParamGuard.fromText("스로틀링 걸린 다음 팬 100%로 켜면 얼마나 빨리 회복돼?");
        assertEquals("r3_active_cooling", n.get("recoveryPolicy").asText());
        assertFalse(n.hasNonNull("cooling"), "부하 구간 냉각으로 새면 안 된다");
    }

    // ── 4. 방열판 배치 비교 ─────────────────────────────────────────────

    @Test
    void heatsinkLayoutPhrases() {
        check("pi5에 방열판 어디에 붙여야 제일 시원해?", EdgeToolSelector.TOOL_HEATSINK, "board", "pi5");
        check("방열판이 15mm 어긋나면 온도가 얼마나 올라가?", EdgeToolSelector.TOOL_HEATSINK);
        check("구리 방열판이랑 알루미늄 방열판 차이가 커?", EdgeToolSelector.TOOL_HEATSINK);
        check("pi5 방열판 핀 개수를 늘리면 정말 더 시원해져?", EdgeToolSelector.TOOL_HEATSINK, "board", "pi5");
    }

    /**
     * 배포 예문은 <b>엣지 도메인 어휘를 하나 이상</b> 포함해야 한다. 실제로 걸린 사례:
     * "핀 개수를 늘리면 정말 더 시원해져?"는 '핀'·'시원'이 판정 어휘가 아니라 양쪽
     * 점수가 0이 되어 UNKNOWN으로 빠졌다 — 학생은 답 대신 되물음을 받게 된다.
     */
    @Test
    void phrasesWithoutDomainVocabularyFallToUnknown() {
        assertEquals(DomainIntentDetector.Domain.UNKNOWN,
                DomainIntentDetector.classify("핀 개수를 늘리면 정말 더 시원해져?"));
        assertEquals(DomainIntentDetector.Domain.EDGE_THERMAL,
                DomainIntentDetector.classify("pi5 방열판 핀 개수를 늘리면 정말 더 시원해져?"));
    }

    // ── 방열판 질량·재질 (2노드) ────────────────────────────────────────

    @Test
    @DisplayName("질량을 말하면 특정 방열판 지정 — 발열 시뮬레이션으로 가고 2노드로 계산된다")
    void heatsinkMassRoutesToThrottlingNotLayout() {
        check("pi5에 90g 알루미늄 방열판 달고 최대 처리량으로 버스트 부하 돌려줘",
                EdgeToolSelector.TOOL_THROTTLING,
                "board", "pi5", "cooling", "passive",
                "heatsinkMassG", "90.0", "heatsinkMaterial", "aluminum",
                "aiLoadProfileId", "burst");
        check("라즈베리파이 5에 구리 방열판 120그램 달고 20분 돌려줘",
                EdgeToolSelector.TOOL_THROTTLING,
                "heatsinkMassG", "120.0", "heatsinkMaterial", "copper");
    }

    @Test
    @DisplayName("질량 없이 재질만 말하면 여전히 배치·재질 비교다")
    void materialWithoutMassStillGoesToLayout() {
        check("구리 방열판이랑 알루미늄 방열판 차이가 커?", EdgeToolSelector.TOOL_HEATSINK);
        assertFalse(EdgeParamGuard.fromText("구리랑 알루미늄 차이가 커?").hasNonNull("heatsinkMassG"));
    }

    /** 실측 회귀 — "90g 알루미늄과 90g 구리 비교"가 구리 하나만 골라 한 번 실행됐다.
     *  어느 쪽을 고르든 사용자가 물어본 비교가 아니라 한쪽 결과일 뿐이다. */
    @Test
    @DisplayName("재질을 둘 다 말하면 비교 요청 — 한쪽을 골라 확정하지 않는다")
    void materialComparisonDoesNotSilentlyPickOne() {
        String text = "90g 알루미늄과 90g 구리 방열판 비교해줘";
        assertTrue(EdgeParamGuard.isMaterialComparison(text));

        ObjectNode n = EdgeParamGuard.fromText(text);
        assertEquals(90.0, n.get("heatsinkMassG").asDouble(), 1e-9, "질량은 공통이라 확정한다");
        assertFalse(n.hasNonNull("heatsinkMaterial"),
                "재질을 확정하면 비교가 아니라 한쪽 실행이 된다");
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select(text));
    }

    @Test
    @DisplayName("재질이 하나면 비교가 아니라 그 재질로 확정한다")
    void singleMaterialIsNotAComparison() {
        assertFalse(EdgeParamGuard.isMaterialComparison("90g 알루미늄 방열판 달고 돌려줘"));
        assertEquals("aluminum",
                EdgeParamGuard.fromText("90g 알루미늄 방열판 달고 돌려줘").get("heatsinkMaterial").asText());
        assertEquals("copper",
                EdgeParamGuard.fromText("90g 구리 방열판 달고 돌려줘").get("heatsinkMaterial").asText());
    }

    @Test
    @DisplayName("질량 없이 재질만 둘이면 배치·재질 비교 도구의 몫이다")
    void twoMaterialsWithoutMassIsNotHandledHere() {
        assertFalse(EdgeParamGuard.isMaterialComparison("구리랑 알루미늄 차이가 커?"));
        assertEquals(EdgeToolSelector.TOOL_HEATSINK, EdgeToolSelector.select("구리랑 알루미늄 차이가 커?"));
    }

    // ── 팬 유무 비교 ────────────────────────────────────────────────────

    @Test
    @DisplayName("팬 유무를 물으면 비교 요청 — 회전수를 확정하지 않는다")
    void fanComparisonIsDetected() {
        for (String t : new String[]{
                "pi5에 팬 있을 때와 없을 때 비교해줘",
                "팬 유무에 따라 얼마나 차이나?",
                "팬 켰을 때랑 껐을 때 비교해줘"}) {
            assertTrue(EdgeParamGuard.isFanComparison(t), t);
            assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select(t), t);
        }
    }

    /** 실측 회귀 — "팬 없을 때"가 단어("팬")만 보고 팬 냉각으로 실행돼서,
     *  "있을 때"와 "없을 때"가 완전히 같은 결과를 냈다. */
    @Test
    @DisplayName("'팬 없을 때'는 팬 냉각이 아니다 — 부정을 봐야 두 조건이 갈린다")
    void fanNegationIsNotActiveCooling() {
        for (String t : new String[]{
                "pi5 최대 처리량으로 팬 없을 때 20분 돌려줘",
                "팬 없이 20분 돌리면 어떻게 돼?",
                "팬을 끄고 돌려줘"}) {
            assertEquals("passive", EdgeParamGuard.fromText(t).get("cooling").asText(),
                    "팬을 뗀 것이지 방열판까지 없앤 게 아니다: " + t);
        }
        // 반대쪽은 그대로 팬 냉각이어야 한다 — 두 조건이 실제로 갈리는지 확인
        assertEquals("active",
                EdgeParamGuard.fromText("pi5 최대 처리량으로 팬 있을 때 20분 돌려줘").get("cooling").asText());
    }

    /** 실측 회귀 — "팬 있을 때 방열판 없이"가 무냉각으로 실행돼서, 팬을 켜든 끄든
     *  결과가 같았다. 프리셋(무냉각/방열판/방열판+팬)에 없는 조합이라 계산할 수 없다. */
    @Test
    @DisplayName("방열판 없이 팬만 다는 조합은 계산 불가로 잡아낸다")
    void fanWithoutHeatsinkIsFlaggedUnsupported() {
        assertTrue(EdgeParamGuard.isUnsupportedCoolingCombo("pi4에 팬 있을 때 방열판 없이 25분 돌려줘"));
        assertTrue(EdgeParamGuard.isUnsupportedCoolingCombo("무냉각에 팬만 달고 돌려줘"));
    }

    @Test
    @DisplayName("양쪽 다 없거나 방열판이 있으면 정상 조합이다")
    void supportedCoolingCombosAreNotFlagged() {
        // 팬도 방열판도 없음 = 무냉각(프리셋 있음)
        assertFalse(EdgeParamGuard.isUnsupportedCoolingCombo("pi4에 팬 없을 때 방열판 없이 돌려줘"));
        assertFalse(EdgeParamGuard.isUnsupportedCoolingCombo("pi5 무냉각으로 20분 돌려줘"));
        // 방열판이 있으면 팬을 달든 안 달든 프리셋이 있다
        assertFalse(EdgeParamGuard.isUnsupportedCoolingCombo("pi5 방열판에 팬 달고 돌려줘"));
        assertFalse(EdgeParamGuard.isUnsupportedCoolingCombo("팬 있을 때와 없을 때 비교해줘"));
    }

    @Test
    @DisplayName("팬을 한쪽으로만 말하면 비교가 아니다 — 회복 정책과 헷갈리면 안 된다")
    void singleSidedFanMentionIsNotComparison() {
        assertFalse(EdgeParamGuard.isFanComparison("pi5에 팬 달고 20분 돌려줘"));
        // R3 회복 정책 — "팬 켜면"이 비교로 오인되면 회복 실험이 통째로 바뀐다
        String r3 = "스로틀링 걸리면 팬 100%로 켜면 얼마나 빨리 회복돼?";
        assertFalse(EdgeParamGuard.isFanComparison(r3));
        assertEquals("r3_active_cooling", EdgeParamGuard.fromText(r3).get("recoveryPolicy").asText());
    }

    @Test
    @DisplayName("명시적 회전수는 그대로 쓰고, 유무 비교일 때는 확정하지 않는다")
    void explicitRpmIsExtractedExceptInComparison() {
        assertEquals(2500.0,
                EdgeParamGuard.fromText("pi5 팬 2500rpm으로 돌려줘").get("fanRpm").asDouble(), 1e-9);
        assertFalse(EdgeParamGuard.fromText("팬 5000rpm 있을 때와 없을 때 비교해줘").hasNonNull("fanRpm"),
                "유무 비교는 호출측이 0과 정격으로 두 번 돌린다");
    }

    @Test
    void kilogramIsConvertedToGrams() {
        assertEquals(1200.0,
                EdgeParamGuard.fromText("pi5에 1.2kg 방열판 달고 돌려줘").get("heatsinkMassG").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("g가 붙은 무관한 표기는 질량으로 오인하지 않는다")
    void unrelatedUnitsAreNotMistakenForMass() {
        for (String t : new String[]{"pi5 8GB로 20분 돌려줘", "40mm 방열판 배치 비교",
                                      "라즈베리파이 5 무냉각 20분"}) {
            assertFalse(EdgeParamGuard.fromText(t).hasNonNull("heatsinkMassG"), t);
        }
    }

    @Test
    @DisplayName("배치 어휘가 명시되면 질량이 있어도 배치 비교다")
    void explicitLayoutWordsStillWinOverMass() {
        assertEquals(EdgeToolSelector.TOOL_HEATSINK,
                EdgeToolSelector.select("90g 알루미늄 방열판 배치를 비교해줘"));
    }

    /** "방열판만 상태에서"는 냉각 조건이지 배치 비교 대상이 아니다(회귀 케이스). */
    @Test
    void heatsinkAsCoolingConditionStaysOnThrottlingTool() {
        check("라즈베리파이 5를 방열판만 상태에서 최대 처리량으로 20분 돌려줘",
                EdgeToolSelector.TOOL_THROTTLING, "cooling", "passive");
    }

    // ── 5. 보드 비교 ────────────────────────────────────────────────────

    @Test
    void boardComparisonPhrases() {
        assertTrue(EdgeParamGuard.isBoardComparison("pi4랑 pi5 중에 뭐가 더 빨리 뜨거워져?"));
        assertTrue(EdgeParamGuard.isBoardComparison("라즈베리파이 4와 5를 방열판 상태에서 비교해줘"));
        // 비교 요청은 보드를 하나로 확정하지 않는다(두 번 실행해야 하므로)
        assertFalse(EdgeParamGuard.fromText("pi4랑 pi5 비교해줘").hasNonNull("board"));
    }

    // ── 6. 실측 보정 ────────────────────────────────────────────────────

    @Test
    void calibrationPhrases() {
        assertEquals(EdgeToolSelector.TOOL_CALIBRATE,
                EdgeToolSelector.select("실측 데이터로 모델 보정해줘"));
        assertEquals(EdgeToolSelector.TOOL_CALIBRATE,
                EdgeToolSelector.select("측정한 CSV로 캘리브레이션 하는 방법 알려줘"));
    }
}
