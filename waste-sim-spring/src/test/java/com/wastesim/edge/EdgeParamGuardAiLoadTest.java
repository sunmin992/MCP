package com.wastesim.edge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 자연어에서 AI 부하 패턴을 결정론적으로 뽑아내는지 검증한다.
 *
 * <p>여기서 오탐은 단순한 불편이 아니다 — 패턴이 바뀌면 방열판 순위가 뒤집힐 수 있으므로,
 * 사용자가 말하지 않은 패턴이 켜지면 <b>그건 틀린 답이 아니라 다른 실험</b>이 된다.
 * 그래서 "최대 부하"·"고부하"처럼 세기만 말하는 표현이 패턴으로 새지 않는지도 함께 고정한다.
 */
class EdgeParamGuardAiLoadTest {

    private String load(String text) {
        ObjectNode n = EdgeParamGuard.fromText(text);
        return n.hasNonNull("aiLoadProfileId") ? n.get("aiLoadProfileId").asText() : null;
    }

    // ── 인식 ────────────────────────────────────────────────────────────

    @Test
    void detectsBurst() {
        assertEquals("burst", load("라즈베리파이 5 방열판으로 버스트 부하 20분 돌려줘"));
        assertEquals("burst", load("요청이 몰렸다 빠지는 조건으로 시뮬레이션해줘"));
        assertEquals("burst", load("부하가 출렁일 때 어떻게 돼?"));
        assertEquals("burst", load("부하 변동이 있으면 스로틀링이 언제 걸려?"));
    }

    @Test
    void detectsMixed() {
        assertEquals("mixed", load("실사용 패턴으로 돌려줘"));
        assertEquals("mixed", load("혼합 부하로 pi5 시뮬레이션"));
        assertEquals("mixed", load("하루 흐름을 반영해서 비교해줘"));
        assertEquals("mixed", load("데이터센터 부하 패턴으로 방열판 비교"));
    }

    @Test
    void detectsSteady() {
        assertEquals("steady", load("일정한 부하로 돌려줘"));
        assertEquals("steady", load("대조군으로 한 번 돌려줘"));
        assertEquals("steady", load("변동 없이 계속 돌리면?"));
    }

    @Test
    @DisplayName("혼합과 버스트가 함께 나오면 더 구체적인 혼합을 고른다")
    void mixedWinsOverBurst() {
        assertEquals("mixed", load("실사용처럼 버스트가 섞인 부하로 돌려줘"));
    }

    // ── 오탐 방지 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("세기만 말하는 표현은 패턴으로 새지 않는다 — 그건 workloadMode의 몫")
    void loadIntensityWordsAreNotPatterns() {
        assertNull(load("최대 처리량으로 20분 돌려줘"));
        assertNull(load("고부하로 돌리면 언제 스로틀링 걸려?"));
        assertNull(load("풀 로드로 돌려줘"));
    }

    @Test
    @DisplayName("저부하는 회복 정책(R2)이지 부하 패턴이 아니다")
    void lowLoadIsRecoveryPolicyNotPattern() {
        ObjectNode n = EdgeParamGuard.fromText("스로틀링 걸리면 저부하로 낮춰서 회복시켜줘");
        assertEquals("r2_low_load", n.get("recoveryPolicy").asText());
        assertFalse(n.hasNonNull("aiLoadProfileId"));
    }

    @Test
    void unrelatedRequestsHaveNoPattern() {
        assertNull(load("라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?"));
        assertNull(load("pi4랑 pi5 비교해줘"));
        assertNull(load(""));
        assertNull(load(null));
    }

    // ── 다른 축과의 독립성 ───────────────────────────────────────────────

    @Test
    @DisplayName("부하 패턴과 운용 모드는 독립된 축이라 함께 지정된다")
    void patternAndWorkloadModeAreIndependent() {
        ObjectNode n = EdgeParamGuard.fromText("pi5 방열판으로 최대 처리량 + 버스트 부하 20분");
        assertEquals("max_throughput", n.get("workloadMode").asText());
        assertEquals("burst", n.get("aiLoadProfileId").asText());
        assertEquals("pi5", n.get("board").asText());
        assertEquals("passive", n.get("cooling").asText());
    }

    @Test
    @DisplayName("결정론 판정이 LLM 출력을 이긴다 — 같은 문장은 항상 같은 실험으로 간다")
    void guardOverridesLlmExtraction() {
        var llm = EdgeParamGuard.fromText("");
        llm.put("aiLoadProfileId", "steady");   // LLM이 엉뚱하게 채운 상황
        ObjectNode merged = EdgeParamGuard.merge(llm, EdgeParamGuard.fromText("버스트 부하로 돌려줘"));
        assertEquals("burst", merged.get("aiLoadProfileId").asText());
    }

    /** 실제로 뽑은 값이 도구의 허용 값과 일치해야 한다 — 문자열이 어긋나면 fail-closed로 거부된다. */
    @Test
    void producedIdsAreAcceptedByTheProfileService() {
        AiLoadProfileService svc = new AiLoadProfileService();
        for (String text : new String[]{"버스트 부하", "실사용 패턴", "일정한 부하"}) {
            String id = load(text);
            assertNotNull(id, text);
            assertNotNull(svc.find(id), "도구가 모르는 id를 만들었다: " + id);
        }
    }
}
