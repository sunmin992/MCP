package com.wastesim.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-119 — 생성 요청 판별.
 *
 * <p>이 판정기의 위험은 <b>과잉 매칭</b>이다. 즉시 실행돼야 할 요청이 잘못 걸리면
 * 사용자는 한 문장이면 끝날 일에 열 개 넘는 질문을 받게 된다. 그래서 대조군(걸리면 안 되는
 * 문장)을 매칭 대상만큼 촘촘히 둔다 — 새 게이트가 기존 대화를 가로채지 않는다는 것이
 * 이 계층 도입의 회귀 조건이다.
 */
class SimulatorCreationDetectorTest {

    @Test
    @DisplayName("대상 어휘와 생성 동사가 함께 있으면 생성 요청이다")
    void detectsCreationRequests() {
        assertTrue(SimulatorCreationDetector.isCreationRequest("장량동 원룸촌 시뮬레이터 만들어 줘"));
        assertTrue(SimulatorCreationDetector.isCreationRequest("시뮬레이션을 처음부터 구성해줘"));
        assertTrue(SimulatorCreationDetector.isCreationRequest("실험 환경을 설계해 주세요"));
        assertTrue(SimulatorCreationDetector.isCreationRequest("새로 시뮬레이터를 만들고 싶어요"));
        assertTrue(SimulatorCreationDetector.isCreationRequest("시나리오 구성을 세팅해줘"));
    }

    @Test
    @DisplayName("사용자가 수집 흐름을 직접 부르면 대상 어휘가 없어도 인정한다")
    void detectsExplicitStart() {
        assertTrue(SimulatorCreationDetector.isCreationRequest("필요한 값 하나씩 물어봐 줘"));
        assertTrue(SimulatorCreationDetector.isCreationRequest("무엇이 필요한지 질문해 주세요"));
        assertTrue(SimulatorCreationDetector.isCreationRequest("서브태스크대로 진행해줘"));
    }

    @Test
    @DisplayName("즉시 실행 요청을 가로채지 않는다 — 값이 이미 문장에 있는 요청은 종전대로 실행된다")
    void doesNotStealImmediateExecutionRequests() {
        // 이 문장들은 v1.12까지 즉시 실행되던 것이고, 계속 그래야 한다.
        assertFalse(SimulatorCreationDetector.isCreationRequest("수거 시각을 12시로 설정하고 시뮬레이션 실행해줘"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("8시 30분에 수거하면 민원이 몇 건이야?"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("소형 트럭으로 10시에 돌려줘"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("10시와 11시에 각각 수거해줘"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("이 시뮬레이션 모델에 대해 설명해줘"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("월별 배출량 시나리오 실행해줘"));
    }

    @Test
    @DisplayName("동사만 있거나 대상만 있는 문장은 걸리지 않는다")
    void requiresBothSignals() {
        assertFalse(SimulatorCreationDetector.isCreationRequest("표로 만들어 줘"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("보고서를 만들어 주세요"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("시뮬레이션이 뭐야?"));
        assertFalse(SimulatorCreationDetector.isCreationRequest("시뮬레이션 결과 보여줘"));
    }

    @Test
    @DisplayName("빈 입력은 생성 요청이 아니다")
    void handlesEmptyInput() {
        assertFalse(SimulatorCreationDetector.isCreationRequest(null));
        assertFalse(SimulatorCreationDetector.isCreationRequest(""));
        assertFalse(SimulatorCreationDetector.isCreationRequest("   "));
    }
}
