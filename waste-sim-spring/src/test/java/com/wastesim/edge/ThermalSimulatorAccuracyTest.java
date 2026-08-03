package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 적분 정확도와 회복 정책 적용 범위 검증 (DEBUGGING_ISSUES.md E-01·E-02).
 *
 * <p>두 결함 모두 "그럴듯한 값이 나오지만 틀린" 부류라 기존 테스트가 잡지 못했다.
 * 에너지는 조금 크게 나올 뿐이고, R2는 정책 비교표에 값이 채워지긴 하기 때문이다.
 * 그래서 <b>해석적으로 정답을 아는 조건</b>을 만들어 고정한다.
 */
class ThermalSimulatorAccuracyTest {

    private final ThermalSimulator sim = new ThermalSimulator();

    /** 스로틀링이 없어 클럭이 계속 최대인 조건 — 소비전력이 상수라 에너지를 손으로 계산할 수 있다. */
    private ThermalParams coolParams() {
        return ThermalParams.preset(BoardType.PI4, CoolingPreset.ACTIVE, 25.0);
    }

    // ── E-02: 종료 시각 이후 한 스텝 추가 적분 ──────────────────────────

    @Test
    @DisplayName("소비전력이 상수면 에너지는 정확히 전력 × 실행시간이다")
    void energyMatchesPowerTimesDuration() {
        ThermalParams p = coolParams();
        double loadSec = 600;
        ThermalRun run = sim.run(BoardType.PI4,
                new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, loadSec,
                        RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true));

        assertFalse(run.throttlingExpected(), "이 테스트는 클럭이 계속 최대여야 성립한다");
        double expected = (p.idlePowerW() + p.dynamicPowerW()) * loadSec;
        assertEquals(expected, run.energyJ(), 0.5,
                "종료 시각에서 dt만큼 더 적분하면 에너지가 과대 계산된다");
    }

    @Test
    @DisplayName("팬 에너지도 정확히 팬 전력 × 실행시간이다")
    void fanEnergyMatchesPowerTimesDuration() {
        ThermalParams p = coolParams();
        double loadSec = 900;
        FanSpec fan = new FanSpec(5000, 5000, 0.5);
        ThermalRun run = sim.run(BoardType.PI4,
                new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, loadSec,
                        RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true, null, null, fan));

        assertEquals(fan.powerW() * loadSec, run.fanEnergyJ(), 0.05);
    }

    @Test
    @DisplayName("적분 간격을 바꿔도 에너지가 수렴한다 — dt에 비례해 커지면 안 된다")
    void energyDoesNotScaleWithStepSize() {
        ThermalParams p = coolParams();
        double loadSec = 600;
        double coarse = energyAt(p, loadSec, 0.5);
        double fine = energyAt(p, loadSec, 0.05);
        assertEquals(fine, coarse, 0.5,
                "dt가 클수록 에너지가 커지면 종료 시각 처리에 문제가 있다");
    }

    private double energyAt(ThermalParams p, double loadSec, double dt) {
        return sim.run(BoardType.PI4, new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT,
                30.0, loadSec, RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), dt, 5.0, true)).energyJ();
    }

    // ── E-01: R2 저부하가 최대 처리량 모드에서 무시됨 ────────────────────

    /**
     * R2는 "부하를 25%로 낮춰 식힌다"는 정책인데, 최대 처리량 모드에서는 목표 FPS를
     * 낮추는 경로를 타지 않아 사실상 무조치와 같았다. 정책 비교표에 값은 채워지므로
     * 학생은 "R2가 별 효과 없다"는 결론을 내리게 된다.
     */
    @Test
    @DisplayName("최대 처리량 모드에서도 R2는 부하를 낮춰 무조치보다 시원해야 한다")
    void lowLoadPolicyAppliesInMaxThroughputMode() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 30.0);
        ThermalRun none = recovery(p, RecoveryPolicy.NONE);
        ThermalRun r2 = recovery(p, RecoveryPolicy.R2_LOW_LOAD);

        assertTrue(r2.energyJ() < none.energyJ(),
                "R2가 부하를 줄였다면 소비 에너지가 더 적어야 한다 ("
                        + r2.energyJ() + " vs " + none.energyJ() + ")");
        assertTrue(r2.peakTempC() <= none.peakTempC(),
                "R2가 무조치보다 뜨거우면 정책이 적용되지 않은 것이다");
    }

    @Test
    @DisplayName("R1(완전 중지)·R2(저부하)·무조치가 서로 다른 결과를 낸다")
    void recoveryPoliciesRemainDistinct() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 30.0);
        double stop = recovery(p, RecoveryPolicy.R1_STOP).energyJ();
        double low = recovery(p, RecoveryPolicy.R2_LOW_LOAD).energyJ();
        double none = recovery(p, RecoveryPolicy.NONE).energyJ();

        assertTrue(stop < low, "완전 중지가 저부하보다 적게 써야 한다 (" + stop + " vs " + low + ")");
        assertTrue(low < none, "저부하가 무조치보다 적게 써야 한다 (" + low + " vs " + none + ")");
    }

    /** 회복 구간을 정확히 loadSec에서 시작시켜(triggerOnThrottle=false) 정책만 비교한다. */
    private ThermalRun recovery(ThermalParams p, RecoveryPolicy policy) {
        return sim.run(BoardType.PI5, new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT,
                30.0, 300, policy, 900, p.rJaKPerW(), 0.2, 10.0, false));
    }
}
