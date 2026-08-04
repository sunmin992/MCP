package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 지속 처리량 손실({@code throughputLossPercent})의 의미를 고정한다.
 *
 * <p>이 지표를 따로 둔 이유는 하나다 — <b>TTT가 없어도 성능은 이미 깎여 있을 수 있다.</b>
 * 펌웨어의 소프트 온도 제한은 온도가 하드 한계에 닿기 전에 클럭을 낮춰 온도를 붙잡는
 * 음성 되먹임이다. 그래서 "스로틀링 진입: 발생 안 함"이라는 결과가 나와도 그 대가로
 * 클럭이 묶여 있을 수 있고, TTT만 보면 "문제 없음"으로 오독하게 된다.
 *
 * <p><b>조건 의존성에 주의</b> — 여기서 고정하는 것은 특정 보드가 항상 어떤 상태에 있다는
 * 성질이 아니라, <i>주어진 조건에서</i> 관측되는 성질이다. 소프트 평형에 갇히는지 여부는
 * 주변 온도·냉각·부하에 따라 갈린다(같은 Pi4도 주변 30℃면 하드 제한에 도달한다).
 * 그래서 테스트 이름과 설명에 조건을 모두 적는다.
 */
class ThermalThroughputLossTest {

    /** 6시간 — 열 시정수(τ≈100초)의 200배가 넘어 평형에 충분히 수렴한다. */
    private static final double SIX_HOURS_SEC = 21600.0;

    private final ThermalSimulator sim = new ThermalSimulator();

    private ThermalRun maxThroughput(BoardType board, CoolingPreset cooling, double ambientC) {
        ThermalParams p = ThermalParams.preset(board, cooling, ambientC);
        return sim.run(board, new ThermalSimulator.Spec(
                p, WorkloadMode.MAX_THROUGHPUT, p.maxFps(), SIX_HOURS_SEC,
                RecoveryPolicy.NONE, 60.0, p.rJaKPerW(), 0.5, 60.0, false));
    }

    // ── 소프트 평형: 스로틀링 없이도 처리량을 잃는다 ────────────────────────

    /**
     * 무개입 정상상태는 87.7℃로 하드 한계(85℃)를 넘지만, 거기까지 가지 못한다.
     * 80℃에서 클럭이 1500→1200MHz(80%)로 내려가면 소비전력이 6.6W→5.82W가 되고,
     * 그 전력의 새 정상상태가 80.3℃라 소프트 구간 안에서 자기일관적으로 멈춘다.
     */
    @Test
    @DisplayName("Pi4 무냉각 25℃에서는 TTT 없이 소프트 평형에 머물며 약 20%의 처리량을 잃는다")
    void pi4BareAt25CStaysInSoftEquilibrium() {
        ThermalRun pi4 = maxThroughput(BoardType.PI4, CoolingPreset.BARE, 25.0);

        assertNotNull(pi4.softLimitEntrySec(), "소프트 온도 제한에는 진입한다");
        assertNull(pi4.tttSec(), "클럭 인하로 온도가 잡혀 하드 스로틀링까지는 가지 않는다");
        // 클럭 하한이 1200/1500 = 80%이므로 평형에 머무는 동안 손실은 20%로 수렴한다.
        assertEquals(20.0, pi4.throughputLossPercent(), 1.0);
    }

    /** 이 지표의 존재 이유를 그대로 못 박는 단정. */
    @Test
    @DisplayName("TTT가 없어도 소프트 클럭 제한만으로 지속 처리량 손실이 발생한다")
    void softLimitAloneCausesLoss() {
        ThermalRun pi4 = maxThroughput(BoardType.PI4, CoolingPreset.BARE, 25.0);

        assertNull(pi4.tttSec());
        assertTrue(pi4.throughputLossPercent() > 0,
                "TTT가 없어도 소프트 클럭 제한으로 지속 처리량 손실이 발생해야 한다");
    }

    // ── 하드 스로틀링: 손실이 더 크다 ──────────────────────────────────────

    /**
     * Pi5는 다이 전력이 훨씬 커서(12W vs 6.6W) 클럭을 80%로 낮춰도 소프트 평형이 90.8℃라
     * 하드 한계를 넘는다. 그래서 클럭이 하한(1000/2400)까지 떨어지고 손실이 Pi4보다 커진다.
     */
    @Test
    @DisplayName("Pi5 무냉각 25℃에서는 클럭을 낮춰도 못 버텨 TTT가 발생하고 손실이 Pi4보다 크다")
    void pi5BareAt25CHardThrottlesAndLosesMore() {
        ThermalRun pi4 = maxThroughput(BoardType.PI4, CoolingPreset.BARE, 25.0);
        ThermalRun pi5 = maxThroughput(BoardType.PI5, CoolingPreset.BARE, 25.0);

        assertNotNull(pi5.softLimitEntrySec());
        assertNotNull(pi5.tttSec(), "소프트 구간의 평형 온도가 하드 한계를 넘으므로 반드시 도달한다");
        assertTrue(pi5.throughputLossPercent() > pi4.throughputLossPercent(),
                String.format("하드 스로틀링까지 간 쪽이 더 많이 잃어야 한다 (Pi5 %.1f%% vs Pi4 %.1f%%)",
                        pi5.throughputLossPercent(), pi4.throughputLossPercent()));
    }

    // ── 조건 의존성: "Pi4는 항상 안전하다"가 아니다 ─────────────────────────

    /**
     * 위 성질을 보드의 고유 특성으로 일반화하면 안 된다는 것을 코드로 못 박는다.
     * 판정 기준은 "무개입 정상상태가 85℃를 넘는가"가 아니라 <b>"소프트 평형이 85℃를 넘는가"</b>다.
     * Pi4의 소프트 평형은 {@code 주변온도 + 5.82W × 9.5K/W}이므로 주변이 약 29.7℃를 넘으면
     * 클럭을 낮춰도 버티지 못한다.
     */
    @Test
    @DisplayName("같은 Pi4 무냉각이라도 주변 32℃면 소프트 평형이 하드 한계를 넘어 TTT가 발생한다")
    void pi4BareAtHigherAmbientDoesHardThrottle() {
        ThermalRun warm = maxThroughput(BoardType.PI4, CoolingPreset.BARE, 32.0);

        assertNotNull(warm.tttSec(), "주변 온도가 오르면 같은 보드·같은 냉각도 하드 제한에 도달한다");
        assertTrue(warm.throughputLossPercent()
                        > maxThroughput(BoardType.PI4, CoolingPreset.BARE, 25.0).throughputLossPercent(),
                "더 더운 환경에서 더 많이 잃어야 한다");
    }

    // ── 기준선: 손실이 0인 조건도 있어야 지표가 의미를 갖는다 ────────────────

    @Test
    @DisplayName("Pi4 팬 냉각 25℃는 소프트 제한에 닿지 않아 손실이 0%다")
    void adequateCoolingMeansNoLoss() {
        ThermalRun cooled = maxThroughput(BoardType.PI4, CoolingPreset.ACTIVE, 25.0);

        assertNull(cooled.softLimitEntrySec());
        assertNull(cooled.tttSec());
        assertEquals(0.0, cooled.throughputLossPercent(), 1e-6,
                "클럭이 한 번도 깎이지 않았으면 잃은 처리량도 없어야 한다");
    }
}
