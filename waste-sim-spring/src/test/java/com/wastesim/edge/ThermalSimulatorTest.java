package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 열 RC 시뮬레이터 검증. 핵심은 "그럴듯한 숫자가 나온다"가 아니라
 * <b>해석해와 물리적 단조성을 지키는가</b>다 — 이 두 가지가 깨지면 학생이 뽑는
 * TTT/TRT 표 전체가 무의미해진다.
 */
class ThermalSimulatorTest {

    private final ThermalSimulator sim = new ThermalSimulator();

    private ThermalSimulator.Spec spec(ThermalParams p, WorkloadMode mode, double loadSec,
                                       RecoveryPolicy policy, double recSec, double recRJa) {
        return new ThermalSimulator.Spec(p, mode, 30.0, loadSec, policy, recSec, recRJa, 0.2, 5.0, true);
    }

    @Test
    @DisplayName("스로틀링이 없는 조건에서는 1차 RC 해석해 T(τ)=T∞−(T∞−T0)/e 와 일치한다")
    void matchesAnalyticSolution() {
        ThermalParams p = ThermalParams.preset(BoardType.PI4, CoolingPreset.ACTIVE, 25.0);
        double tau = p.tauSeconds();
        double tInf = p.fullLoadSteadyTempC();
        double t0 = p.startTempC();
        assertTrue(tInf < p.softLimitC(), "이 테스트는 스로틀링이 없는 조건이어야 한다");

        ThermalRun run = sim.run(BoardType.PI4, spec(p, WorkloadMode.MAX_THROUGHPUT, 4 * tau,
                RecoveryPolicy.NONE, 0, p.rJaKPerW()));

        double expected = tInf - (tInf - t0) / Math.E;                 // t = τ 에서의 해석해
        double actual = run.series().stream()
                .filter(s -> Math.abs(s.tSec() - tau) <= 2.5)
                .mapToDouble(ThermalRun.Sample::socTempC).findFirst().orElseThrow();
        assertEquals(expected, actual, 0.5, "τ 시점 온도가 해석해와 0.5℃ 이상 어긋난다");

        double last = run.series().get(run.series().size() - 1).socTempC();
        assertEquals(tInf, last, 0.5, "4τ 뒤에는 정상상태(오차 2% 이내)에 도달해야 한다");
    }

    @Test
    @DisplayName("냉각이 좋을수록 정상상태 온도·시정수가 함께 낮아진다")
    void betterCoolingLowersTempAndTau() {
        double prevTemp = Double.MAX_VALUE, prevTau = Double.MAX_VALUE;
        for (CoolingPreset c : new CoolingPreset[]{CoolingPreset.BARE, CoolingPreset.PASSIVE, CoolingPreset.ACTIVE}) {
            ThermalParams p = ThermalParams.preset(BoardType.PI5, c, 25.0);
            assertTrue(p.fullLoadSteadyTempC() < prevTemp, c + " 정상상태 온도가 더 낮아야 한다");
            assertTrue(p.tauSeconds() < prevTau, c + " 시정수가 더 짧아야 한다");
            prevTemp = p.fullLoadSteadyTempC();
            prevTau = p.tauSeconds();
        }
    }

    @Test
    @DisplayName("Pi5 무냉각·주변 35℃에서는 하드 스로틀링(0x4)이 관측되고 TTT·에피소드가 산출된다")
    void detectsThrottlingOnHotBareBoard() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 35.0);
        ThermalRun run = sim.run(BoardType.PI5, spec(p, WorkloadMode.MAX_THROUGHPUT, 1200,
                RecoveryPolicy.NONE, 0, p.rJaKPerW()));

        assertTrue(run.throttlingExpected());
        assertNotNull(run.tttSec(), "TTT가 산출돼야 한다");
        assertTrue(run.tttSec() > 0 && run.tttSec() < 1200);
        assertTrue(run.softLimitEntrySec() < run.tttSec(), "소프트 제한(80℃)은 하드 스로틀링(85℃)보다 먼저 온다");
        assertFalse(run.episodes().isEmpty(), "스로틀링 에피소드(TED 원자료)가 있어야 한다");
        assertTrue(run.peakTempC() <= p.hardLimitC() + 1.0, "펌웨어가 잡아주므로 하드 제한을 크게 넘지 않는다");
        assertTrue(run.fpsDropPercent() > 10, "스로틀링이 걸리면 FPS가 눈에 띄게 떨어져야 한다");
    }

    @Test
    @DisplayName("소프트 제한만으로 열이 잡히는 조건에서는 0x4가 뜨지 않고 그 이유가 notes에 남는다")
    void softLimitOnlyIsExplained() {
        ThermalParams p = ThermalParams.preset(BoardType.PI4, CoolingPreset.BARE, 25.0);
        ThermalRun run = sim.run(BoardType.PI4, spec(p, WorkloadMode.MAX_THROUGHPUT, 1800,
                RecoveryPolicy.NONE, 0, p.rJaKPerW()));

        assertNull(run.tttSec(), "Pi4 무냉각 25℃는 80℃ 부근에 눌러앉아 하드 스로틀링까지 가지 않는다");
        assertNotNull(run.softLimitEntrySec(), "대신 소프트 제한 진입 시각은 관측된다");
        assertTrue(run.notes().stream().anyMatch(n -> n.contains("소프트")),
                "왜 0x4가 없는지 설명이 결과에 포함돼야 한다(학생이 '실험 실패'로 오해하지 않도록)");
    }

    @Test
    @DisplayName("회복 정책의 효과가 서열대로 나온다: R3(능동냉각) < R1(중지) < 무조치(회복 실패)")
    void recoveryPoliciesOrderedByEffectiveness() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 35.0);
        double activeRJa = BoardType.PI5.rJaKPerW(CoolingPreset.ACTIVE);

        ThermalRun none = sim.run(BoardType.PI5, spec(p, WorkloadMode.MAX_THROUGHPUT, 1200,
                RecoveryPolicy.NONE, 900, p.rJaKPerW()));
        ThermalRun r1 = sim.run(BoardType.PI5, spec(p, WorkloadMode.MAX_THROUGHPUT, 1200,
                RecoveryPolicy.R1_STOP, 900, p.rJaKPerW()));
        ThermalRun r3 = sim.run(BoardType.PI5, spec(p, WorkloadMode.MAX_THROUGHPUT, 1200,
                RecoveryPolicy.R3_ACTIVE_COOLING, 900, activeRJa));

        assertNull(none.trtStateSec(),
                "아무 조치도 안 하면 스로틀링이 계속 재발해 '회복'이 성립하지 않는다");
        assertTrue(none.notes().stream().anyMatch(n -> n.contains("회복되지 않는다")),
                "회복 실패를 결과에서 분명히 알려줘야 한다");

        assertNotNull(r1.trtStateSec());
        assertNotNull(r3.trtStateSec());
        assertTrue(r3.trtStateSec() <= r1.trtStateSec(),
                "팬을 100%로 돌리는 R3가 부하만 멈추는 R1보다 느리게 회복될 수는 없다");
        assertTrue(r3.trtServiceSec() <= r1.trtServiceSec(), "서비스 회복도 같은 순서여야 한다");
        assertNotNull(r1.trtFullSec(), "부하를 완전히 멈추면 유휴 온도까지 식어야 한다");
    }

    @Test
    @DisplayName("목표 FPS 모드는 최대 처리량 모드보다 덜 뜨겁다(사용률이 낮아 소비전력이 작다)")
    void targetFpsRunsCoolerThanMaxThroughput() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 25.0);
        var target = new ThermalSimulator.Spec(p, WorkloadMode.TARGET_FPS, 8.0, 900,
                RecoveryPolicy.NONE, 0, p.rJaKPerW(), 0.2, 5.0, true);
        var max = new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 8.0, 900,
                RecoveryPolicy.NONE, 0, p.rJaKPerW(), 0.2, 5.0, true);

        assertTrue(sim.run(BoardType.PI5, target).peakTempC() < sim.run(BoardType.PI5, max).peakTempC());
    }

    @Test
    @DisplayName("정상상태 온도는 최대 부하가 아니라 '이번 실행의 부하'로 계산된다")
    void steadyStateFollowsActualDuty() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 32.0);

        // 목표 10FPS(최대 28FPS의 36%) — 최대 처리량 기준으로 계산하면 110℃가 나오지만
        // 실제로는 CPU가 쉬는 시간이 있어 훨씬 낮은 온도에서 안정된다.
        var target = new ThermalSimulator.Spec(p, WorkloadMode.TARGET_FPS, 10.0, 4000,
                RecoveryPolicy.NONE, 0, p.rJaKPerW(), 0.2, 60.0, false);
        ThermalRun run = sim.run(BoardType.PI5, target);

        assertTrue(run.steadyStateTempC() < p.fullLoadSteadyTempC() - 20,
                "목표 FPS 모드는 최대 부하보다 훨씬 낮은 온도에서 안정돼야 한다");
        assertEquals(run.steadyStateTempC(), run.peakTempC(), 1.0,
                "요약의 정상상태 온도와 실제 시계열의 도달 온도가 일치해야 한다");
        assertFalse(run.throttlingExpected(), "이 조건은 실제로 스로틀링이 없다");
        assertNull(run.tttSec());

        // 같은 조건을 최대 처리량으로 돌리면 정상상태가 하드 제한을 넘는다
        var max = new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 10.0, 4000,
                RecoveryPolicy.NONE, 0, p.rJaKPerW(), 0.2, 60.0, false);
        ThermalRun hot = sim.run(BoardType.PI5, max);
        assertTrue(hot.steadyStateTempC() > run.steadyStateTempC() + 20);
        assertTrue(hot.throttlingExpected());
    }

    @Test
    @DisplayName("스로틀링이 없었던 실행에서는 TRT를 만들어내지 않는다(null)")
    void noThrottlingMeansNoRecoveryMetrics() {
        ThermalParams p = ThermalParams.preset(BoardType.PI4, CoolingPreset.ACTIVE, 22.0);
        ThermalRun run = sim.run(BoardType.PI4, spec(p, WorkloadMode.MAX_THROUGHPUT, 600,
                RecoveryPolicy.R1_STOP, 600, p.rJaKPerW()));
        assertNull(run.tttSec());
        assertNull(run.trtStateSec(), "스로틀링이 없었으면 '회복 시간'이라는 값 자체가 성립하지 않는다");
    }
}
