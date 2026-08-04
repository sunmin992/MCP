package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FPS·처리량 손실의 시간 가중 계산과 종료 샘플의 상태 동결
 * (EDGE_PRIORITY_FIX_PLAN.md §3·§4).
 *
 * <p>두 결함은 뿌리가 같다 — <b>시간이 흐른 만큼이 아니라 반복한 횟수만큼</b> 지표를 쌓았다.
 * 그래서 종료 시각을 맞추려고 넣는 짧은 부분 스텝이 정상 스텝과 같은 무게를 갖고,
 * 시간이 전혀 흐르지 않는 마지막 반복에서도 클럭이 한 번 더 회복했다.
 */
class TimeWeightedMetricsTest {

    private final ThermalSimulator sim = new ThermalSimulator();

    private ThermalRun run(BoardType board, CoolingPreset cooling, double ambient,
                           WorkloadMode mode, double targetFps, double loadSec, double dt) {
        ThermalParams p = ThermalParams.preset(board, cooling, ambient);
        return sim.run(board, new ThermalSimulator.Spec(
                p, mode, targetFps, loadSec, RecoveryPolicy.NONE, 60.0, p.rJaKPerW(), dt, 60.0, false));
    }

    // ── §3: 시간 가중 평균 ───────────────────────────────────────────────

    /**
     * 부하 시간이 dt의 배수면 부분 스텝이 생기지 않으므로, 가중 방식을 바꿔도 결과가 같아야 한다
     * — 이 테스트가 깨지면 리팩터링이 정상 경로까지 건드린 것이다.
     */
    @Test
    @DisplayName("dt의 배수인 부하 시간에서는 기존 결과가 그대로 유지된다")
    void exactMultipleUnchanged() {
        // 6시간은 dt=0.5의 정확한 배수라 부분 스텝이 없다. 평형에 도달한 뒤가 대부분이므로
        // 초기 무스로틀 구간의 희석이 무시할 수준이고, 값이 소프트 평형(80% 클럭)에 수렴한다.
        ThermalRun r = run(BoardType.PI4, CoolingPreset.BARE, 25, WorkloadMode.MAX_THROUGHPUT, 12, 21600.0, 0.5);
        assertEquals(9.62, r.meanFpsLoad(), 0.05);
        assertEquals(19.8, r.throughputLossPercent(), 0.3);
    }

    /**
     * 마지막 스텝이 0.1초뿐인 조건. 개수로 평균하면 그 0.1초가 0.5초와 같은 무게를 갖는다.
     * 시간 가중이면 손계산과 맞아야 한다.
     */
    @Test
    @DisplayName("부분 스텝이 생겨도 평균 FPS가 시간 평균과 일치한다")
    void partialStepWeightedByTime() {
        // 스로틀링이 전혀 없는 조건(방열판+낮은 목표 FPS)에서는 FPS가 내내 목표치로 일정하므로
        // 시간 평균이 정확히 목표치여야 한다 — 부분 스텝이 있든 없든.
        ThermalRun exact = run(BoardType.PI4, CoolingPreset.ACTIVE, 25, WorkloadMode.TARGET_FPS, 5.0, 100.0, 0.5);
        ThermalRun partial = run(BoardType.PI4, CoolingPreset.ACTIVE, 25, WorkloadMode.TARGET_FPS, 5.0, 100.1, 0.5);
        assertEquals(5.0, exact.meanFpsLoad(), 1e-6);
        assertEquals(5.0, partial.meanFpsLoad(), 1e-6,
                "부분 스텝이 남아도 일정한 FPS의 시간 평균은 그 값 그대로여야 한다");
    }

    @Test
    @DisplayName("부분 스텝 유무가 지속 손실률을 흔들지 않는다")
    void partialStepDoesNotSkewLoss() {
        ThermalRun exact = run(BoardType.PI4, CoolingPreset.BARE, 25, WorkloadMode.MAX_THROUGHPUT, 12, 600.0, 0.5);
        ThermalRun partial = run(BoardType.PI4, CoolingPreset.BARE, 25, WorkloadMode.MAX_THROUGHPUT, 12, 600.1, 0.5);
        assertEquals(exact.throughputLossPercent(), partial.throughputLossPercent(), 0.1);
    }

    // ── §3: 손실률의 물리적 의미 ─────────────────────────────────────────

    @Test
    @DisplayName("스로틀링이 전혀 없으면 지속 손실률은 0%다")
    void noThrottlingMeansNoLoss() {
        // 팬 냉각 + 최대 처리량 — 정상상태가 소프트 제한(80℃)에 한참 못 미친다.
        ThermalRun r = run(BoardType.PI4, CoolingPreset.ACTIVE, 25, WorkloadMode.MAX_THROUGHPUT, 12, 600.0, 0.5);
        assertNull(r.tttSec());
        assertNull(r.softLimitEntrySec());
        assertEquals(0.0, r.throughputLossPercent(), 1e-6);
    }

    // 소프트 평형에서 TTT 없이 처리량을 잃는 성질은 ThermalThroughputLossTest가 소유한다 —
    // 이 파일은 §3·§4의 "시간 가중" 계산 방식만 다룬다.

    /**
     * 목표 FPS 모드에서 목표치가 충분히 낮으면, 소프트 제한으로 클럭이 깎여도 목표는 계속
     * 달성된다 — 그때 손실률은 0이어야 한다. 분모를 maxFps로 잡으면 이 경우가 "손실"로
     * 잘못 잡히므로, 분모가 <b>요구량</b>이라는 점을 고정한다.
     */
    @Test
    @DisplayName("목표 FPS를 계속 달성하면 소프트 제한이 있어도 손실률은 0%다")
    void targetFpsMetMeansNoLoss() {
        // 주변 38℃ + 목표 6 FPS(최대 12의 절반) — 정상상태 82.2℃라 소프트 제한(80℃)에는
        // 들어가지만 하드(85℃)에는 못 미치고, 소프트 구간의 달성가능 FPS 9.6은 목표 6보다
        // 여전히 높아 목표치가 계속 채워진다.
        ThermalRun r = run(BoardType.PI4, CoolingPreset.BARE, 38, WorkloadMode.TARGET_FPS, 6.0, 3600.0, 0.5);
        assertNotNull(r.softLimitEntrySec(), "이 조건은 소프트 제한에 들어간다");
        assertNull(r.tttSec(), "하드 스로틀링까지는 가지 않는다");
        assertEquals(6.0, r.meanFpsLoad(), 0.01, "목표치는 계속 달성된다");
        assertEquals(0.0, r.throughputLossPercent(), 1e-6,
                "분모가 maxFps가 아니라 '요구량'이어야 이 경우가 손실로 잡히지 않는다");
    }

    // ── §4: 종료 샘플에서 상태가 변하지 않는다 ───────────────────────────

    /**
     * 마지막 시계열 샘플의 클럭은 직전 적분 결과 그대로여야 한다. 종료 반복에서 dt만큼
     * 클럭을 더 회복시키면, 시간이 흐르지 않았는데 성능이 좋아진 것으로 기록된다.
     */
    @Test
    @DisplayName("시간이 흐르지 않는 종료 샘플에서 클럭이 더 회복하지 않는다")
    void terminalSampleDoesNotAdvanceClock() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 25);
        // 회복 구간을 두어 클럭이 실제로 상승하는 중에 실행이 끝나게 만든다.
        ThermalRun r = sim.run(BoardType.PI5, new ThermalSimulator.Spec(
                p, WorkloadMode.MAX_THROUGHPUT, p.maxFps(), 600.0,
                RecoveryPolicy.R1_STOP, 30.0, p.rJaKPerW(), 0.5, 0.5, false));

        var series = r.series();
        assertTrue(series.size() >= 2);
        var last = series.get(series.size() - 1);
        var prev = series.get(series.size() - 2);
        // 마지막 두 샘플의 간격이 0이면(=종료 샘플이 직전 샘플과 같은 시각) 클럭도 같아야 한다.
        if (Math.abs(last.tSec() - prev.tSec()) < 1e-9) {
            assertEquals(prev.clockMhz(), last.clockMhz(),
                    "시각이 같은데 클럭이 달라지면 종료 반복이 상태를 바꾼 것이다");
        }
    }

    /**
     * dt를 바꾸면 적분 오차만큼만 달라져야 한다. 종료 반복이 dt에 비례해 클럭을 밀어올리면
     * dt가 클수록 마지막 FPS가 계통적으로 높아진다 — 수렴하지 않는다.
     */
    @Test
    @DisplayName("dt를 줄여도 종료 시점 지표가 수렴한다")
    void resultsConvergeAcrossTimeSteps() {
        double[] dts = {0.5, 0.2, 0.05};
        double[] loss = new double[dts.length];
        double[] mean = new double[dts.length];
        for (int i = 0; i < dts.length; i++) {
            ThermalRun r = run(BoardType.PI4, CoolingPreset.BARE, 25,
                    WorkloadMode.MAX_THROUGHPUT, 12, 900.3, dts[i]);
            loss[i] = r.throughputLossPercent();
            mean[i] = r.meanFpsLoad();
        }
        for (int i = 1; i < dts.length; i++) {
            assertEquals(loss[0], loss[i], 1.0, "dt=" + dts[i] + "에서 손실률이 벗어난다");
            assertEquals(mean[0], mean[i], 0.15, "dt=" + dts[i] + "에서 평균 FPS가 벗어난다");
        }
    }

    /**
     * 에너지는 이미 {@code powerW * step}으로 적분하므로 종료 반복에서 늘지 않아야 한다
     * (E-02에서 고친 성질 — 여기서 깨지지 않았는지 함께 지킨다).
     */
    @Test
    @DisplayName("상수 전력 조건에서 에너지가 전력×시간과 정확히 일치한다")
    void energyStillExact() {
        // 팬 냉각 + 목표 FPS — 스로틀링이 없어 전력이 일정하다.
        ThermalParams p = ThermalParams.preset(BoardType.PI4, CoolingPreset.ACTIVE, 25);
        double target = 5.0;
        ThermalRun r = sim.run(BoardType.PI4, new ThermalSimulator.Spec(
                p, WorkloadMode.TARGET_FPS, target, 300.0,
                RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.5, 60.0, false));
        double util = Math.min(1.0, target / p.maxFps());
        double expected = (p.idlePowerW() + p.dynamicPowerW() * util) * 300.0;
        assertEquals(expected, r.energyJ(), expected * 0.001);
    }
}
