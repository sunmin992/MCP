package com.wastesim.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도메인 라우팅 검증. 이 서버는 성격이 전혀 다른 두 모델을 한 엔드포인트로 서비스하므로,
 * <b>잘못 갈라지면 엉뚱한 모델이 실행된다</b>. 특히 중요한 것은 두 방향의 비대칭이다.
 *
 * <ul>
 *   <li>엣지 요청이 장량동으로 새면 → 학생이 원한 답이 아예 안 나온다</li>
 *   <li>장량동 요청이 엣지로 새면 → <b>기존 기능이 망가진다(회귀)</b></li>
 * </ul>
 *
 * 그래서 후자를 더 촘촘히 검증한다.
 */
class DomainRoutingTest {

    private boolean isEdge(String text) {
        return DomainIntentDetector.detect(text) == DomainIntentDetector.Domain.EDGE_THERMAL;
    }

    @Test
    @DisplayName("라즈베리파이 발열 요청은 엣지 도메인으로 간다")
    void edgeRequestsRouteToEdge() {
        assertTrue(isEdge("라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?"));
        assertTrue(isEdge("pi4에 방열판 달면 온도 얼마나 내려가?"));
        assertTrue(isEdge("스로틀링 걸린 다음 팬 100%로 켜면 얼마나 빨리 회복돼?"));
        assertTrue(isEdge("방열판을 어디에 붙여야 제일 시원해?"));
        assertTrue(isEdge("TTT랑 TRT 비교해줘"));
        assertTrue(isEdge("SoC 온도가 85도 넘으면 FPS가 얼마나 떨어져?"));
    }

    @Test
    @DisplayName("기존 장량동 요청은 하나도 엣지로 새지 않는다(회귀 방지)")
    void wasteRequestsStayOnExistingPath() {
        assertFalse(isEdge("12시에 수거하면 민원이 몇 건이나 생겨?"));
        assertFalse(isEdge("오전 8시 30분으로 시뮬레이션 실행해줘"));
        assertFalse(isEdge("소형 트럭 3대로 교통 정체 반영해서 돌려줘"));
        assertFalse(isEdge("Node_A, Node_C, Node_B 순서로 방문하면 얼마나 걸려?"));
        assertFalse(isEdge("분리배출 시나리오 실험 보여줘"));
        assertFalse(isEdge("거주민 구성별로 최적 수거시각 비교해줘"));
        assertFalse(isEdge("파이썬 엔진으로 실행해줘"));
        assertFalse(isEdge("안녕하세요"));
        assertFalse(isEdge(""));
        assertFalse(isEdge(null));
    }

    @Test
    @DisplayName("두 도메인 어휘가 섞이면 더 많이 언급된 쪽으로 간다")
    void mixedVocabularyGoesToStrongerSide() {
        // "수거 트럭 경로"가 주제고 온도는 곁가지 → 장량동 유지
        assertFalse(isEdge("수거 트럭 경로랑 수거시각 정하는데 바깥 온도도 영향 있어?"));
        // 보드·스로틀링·방열판이 주제 → 엣지
        assertTrue(isEdge("라즈베리파이 스로틀링 때문에 수거 영상 추론이 밀리는데 방열판 어떻게 붙여?"));
    }

    // ── 루트 시작화면용 3분기 판정(classify) ──────────────────────────────
    //
    // detect()는 "엣지가 아니면 장량동"이라는 폴백을 갖고 있는데, 도메인이 아직
    // 정해지지 않은 시작화면에서는 그 폴백이 틀린다 — 사용자가 고르지도 않은
    // 도메인으로 조용히 끌려가기 때문이다. classify()는 그 경우를 UNKNOWN으로
    // 분리해 되물을 수 있게 한다.

    @Test
    @DisplayName("단서가 없는 첫 메시지는 UNKNOWN — 장량동으로 새지 않는다")
    void clueslessMessagesAreUnknown() {
        assertEquals(DomainIntentDetector.Domain.UNKNOWN, DomainIntentDetector.classify("안녕하세요"));
        assertEquals(DomainIntentDetector.Domain.UNKNOWN, DomainIntentDetector.classify("뭘 할 수 있어?"));
        assertEquals(DomainIntentDetector.Domain.UNKNOWN, DomainIntentDetector.classify("도와줘"));
        assertEquals(DomainIntentDetector.Domain.UNKNOWN, DomainIntentDetector.classify(""));
        assertEquals(DomainIntentDetector.Domain.UNKNOWN, DomainIntentDetector.classify(null));
    }

    @Test
    @DisplayName("단서가 있으면 classify도 detect와 같은 쪽으로 간다")
    void classifyAgreesWithDetectWhenThereIsEvidence() {
        assertEquals(DomainIntentDetector.Domain.EDGE_THERMAL,
                DomainIntentDetector.classify("라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?"));
        assertEquals(DomainIntentDetector.Domain.WASTE_SIM,
                DomainIntentDetector.classify("12시에 수거하면 민원이 몇 건이나 생겨?"));
        // 동점은 detect()와 같은 기준으로 장량동
        assertEquals(DomainIntentDetector.Domain.WASTE_SIM,
                DomainIntentDetector.classify("수거 트럭 경로랑 수거시각 정하는데 바깥 온도도 영향 있어?"));
    }

    @Test
    @DisplayName("classify는 절대 null을 반환하지 않는다")
    void classifyNeverReturnsNull() {
        String[] samples = {"안녕", "", "발열", "수거", "라즈베리파이 수거 트럭", null};
        for (String s : samples) {
            assertNotNull(DomainIntentDetector.classify(s), "입력: " + s);
        }
    }

    @Test
    @DisplayName("기존 detect()의 반환 규약은 그대로다(회귀 방지)")
    void detectContractUnchanged() {
        // classify를 추가하면서 detect가 UNKNOWN을 흘리기 시작하면, 이 값을
        // EDGE_THERMAL과만 비교하는 기존 채팅 파이프라인이 조용히 오동작한다.
        assertNull(DomainIntentDetector.detect("안녕하세요"));
        assertNull(DomainIntentDetector.detect("12시에 수거하면 민원이 몇 건이나 생겨?"));
        assertEquals(DomainIntentDetector.Domain.EDGE_THERMAL,
                DomainIntentDetector.detect("pi5 스로틀링 언제 걸려?"));
    }

    @Test
    @DisplayName("팬 회전수 요청이 도메인 게이트에서 UNKNOWN으로 끊기지 않는다(v1.9)")
    void fanSpeedRequestsReachEdgeDomain() {
        // 실측 결함: sweep_fan_rpm(FR-97~103)을 추가하면서 EdgeToolSelector에는
        // rpm·pwm·회전수 어휘를 넣었는데 그보다 먼저 도는 이 도메인 게이트에는
        // 빠져 있었다. 그래서 아래 문장들이 양쪽 점수 0으로 UNKNOWN이 되어,
        // 도구 선택기가 어휘를 전부 알고 있는데도 호출조차 되지 않았다.
        String[] fanRequests = {
                "팬 rpm 몇이 가성비가 제일 좋아?",
                "pwm 50% 로 스윕 돌려줘",
                "최적 회전수 찾아줘",
                "팬 rpm 스윕해서 최적 운전점 알려줘",
                "적정 회전수가 어느 정도야?",
        };
        for (String req : fanRequests) {
            assertEquals(DomainIntentDetector.Domain.EDGE_THERMAL,
                    DomainIntentDetector.classify(req),
                    "팬 회전수 요청이 엣지로 가야 한다: " + req);
            assertEquals(EdgeToolSelector.TOOL_SWEEP, EdgeToolSelector.select(req),
                    "도메인을 통과했으면 스윕 도구로 가야 한다: " + req);
        }
    }

    @Test
    @DisplayName("장량동의 sweep·가성비 어휘는 엣지로 새지 않는다(위 수정의 반대 방향 회귀)")
    void wasteSweepVocabularyDoesNotLeakToEdge() {
        // 팬 어휘를 넓힐 때 "스윕"·"가성비"까지 넣으면 여기가 깨진다 —
        // 장량동에도 수거시각 sweep 시나리오가 있고, "가성비"는 트럭 선택에도
        // 쓰이는 중립 어휘다. 그래서 rpm·pwm·회전수·운전점만 넣었다.
        String[] wasteRequests = {
                "수거시각 sweep (06~18시)",
                "수거시각 스윕 돌려줘",
                "트럭 가성비 어떤 게 제일 좋아?",
        };
        for (String req : wasteRequests) {
            assertNull(DomainIntentDetector.detect(req),
                    "장량동 요청이 엣지로 새면 안 된다: " + req);
            assertEquals(DomainIntentDetector.Domain.WASTE_SIM,
                    DomainIntentDetector.classify(req),
                    "장량동으로 판정돼야 한다: " + req);
        }
    }

    @Test
    @DisplayName("엣지 요청 안에서 도구 선택도 결정론적으로 갈린다")
    void toolSelectionWithinEdgeDomain() {
        assertEquals(EdgeToolSelector.TOOL_THROTTLING,
                EdgeToolSelector.select("pi5 무냉각이면 몇 초 만에 스로틀링 걸려?"));
        assertEquals(EdgeToolSelector.TOOL_HEATSINK,
                EdgeToolSelector.select("방열판을 어디에 붙이는 게 제일 좋아?"));
        assertEquals(EdgeToolSelector.TOOL_CALIBRATE,
                EdgeToolSelector.select("측정 데이터로 모델 보정해줘"));
        // 두 어휘가 겹치면 더 구체적인 요청(방열판 배치)을 고른다
        assertEquals(EdgeToolSelector.TOOL_HEATSINK,
                EdgeToolSelector.select("방열판을 어떻게 배치해야 스로틀링이 덜 걸릴까?"));
        // 기본값은 발열 시뮬레이션
        assertEquals(EdgeToolSelector.TOOL_THROTTLING, EdgeToolSelector.select("발열 어때?"));
    }
}
