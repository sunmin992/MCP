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
