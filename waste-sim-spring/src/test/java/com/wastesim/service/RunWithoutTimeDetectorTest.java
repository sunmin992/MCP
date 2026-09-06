package com.wastesim.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실행 동사는 있는데 수거 시각이 없는 문장을 가려낸다.
 *
 * <p>"장량동 26개 동으로 한 달 돌려줘"는 지금 갈 곳이 없다. 즉시 실행 게이트는 시각이
 * 정확히 1개일 것을 요구해서 받지 않고({@link TimeExpressionDetector}가 0을 센다),
 * {@link SimulatorCreationDetector}는 "돌려"·"실행"을 일부러 제외한다. 그래서 조건을
 * 다 말한 문장이 일반 답변으로 떨어진다.
 *
 * <p><b>되묻고 즉시 실행하는 방식은 쓰지 않는다.</b> 즉시 실행 경로의 추출 스키마에는
 * {@code numBuildings}가 없고 프롬프트가 히스토리 이어받기를 금지하므로, 시각만 받아
 * 실행하면 "26개 동"이 조용히 사라진다. 그래서 수집 경로로 보낸다 — 말한 조건은 살고,
 * 말하지 않은 시각만 물어진다.
 *
 * <p>판정 조건은 셋이고 셋 다 필요하다. {@link ExecutionIntentDetector}는 기본값이
 * {@code true}라 긍정 신호로 쓸 수 없다(거부권일 뿐이다) — 그래서 실행 동사를 직접 본다.
 */
class RunWithoutTimeDetectorTest {

    /** 이 판정기가 존재하는 이유 그 자체. 조건은 다 말했고 시각만 없다. */
    @Test
    void runVerbWithScenarioConditionGoesToCollection() {
        assertTrue(RunWithoutTimeDetector.isRunWithoutTime("장량동 26개 동으로 한 달 돌려줘"));
    }

    /** "돌려줘"와 "실행해줘"는 처음부터 같은 취급이었다 — 그 사실을 고정한다. */
    @Test
    void bothRunVerbsBehaveIdentically() {
        assertEquals(RunWithoutTimeDetector.isRunWithoutTime("장량동 26개 동으로 한 달 돌려줘"),
                RunWithoutTimeDetector.isRunWithoutTime("장량동 26개 동으로 한 달 실행해줘"),
                "두 동사가 갈리면 사용자가 쓴 단어에 따라 결과가 달라진다");
    }

    /**
     * 시각이 있는 문장은 이 경로가 아니다.
     *
     * <p>{@code ChatController}가 즉시 실행 게이트를 먼저 보므로 순서로도 막히지만,
     * 판정기 자체도 "시각은 시나리오 조건이 아니다"를 알아야 한다 — 순서 하나에만 기대면
     * 게이트를 옮기는 순간 "10시에 수거로 돌려줘"가 34문항으로 샌다.
     */
    @Test
    void aClockTimeIsNotAScenarioCondition() {
        assertFalse(RunWithoutTimeDetector.isRunWithoutTime("10시에 수거로 돌려줘"));
    }

    /** 시나리오 조건이 없으면 사용법 질문이다 — 수집을 시작할 근거가 없다. */
    @Test
    void runVerbWithoutAnyConditionIsJustAQuestion() {
        assertFalse(RunWithoutTimeDetector.isRunWithoutTime("이거 어떻게 실행해?"));
    }

    /**
     * 실행 동사가 없으면 조회다 — <b>조건이 다 있어도</b> 그렇다.
     *
     * <p>조건 없는 문장("장량동 배출량 알려줘")으로만 확인하면 시나리오 조건 가드가 먼저
     * 걸러서 이 테스트가 통과해 버린다. 그러면 실행 동사 가드를 통째로 지워도 아무 테스트가
     * 깨지지 않는다 — 실제로 변이로 확인했다. 그래서 조건은 갖추고 동사만 뺀 문장을 쓴다.
     */
    @Test
    void noRunVerbIsNotThisPath() {
        assertFalse(RunWithoutTimeDetector.isRunWithoutTime("장량동 26개 동 한 달 배출량 알려줘"));
    }

    /** 실행하지 말라고 한 문장을 실행 준비로 읽으면 안 된다. */
    @Test
    void explicitRefusalToRunIsHonored() {
        assertFalse(RunWithoutTimeDetector.isRunWithoutTime("26개 동으로 한 달 돌리지 말고 설명만 해줘"));
    }

    /** 기간 표현은 숫자 없이도 조건이다 — 사용자가 실제로 쓴 말이다. */
    @Test
    void wordyDurationsCountAsConditions() {
        assertTrue(RunWithoutTimeDetector.isRunWithoutTime("일주일치 돌려줘"));
        assertTrue(RunWithoutTimeDetector.isRunWithoutTime("30일 실행해줘"));
    }

    @Test
    void nullAndBlankAreNotRequests() {
        assertFalse(RunWithoutTimeDetector.isRunWithoutTime(null));
        assertFalse(RunWithoutTimeDetector.isRunWithoutTime("   "));
    }
}
