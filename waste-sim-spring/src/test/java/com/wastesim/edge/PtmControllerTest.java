package com.wastesim.edge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 예측 냉각(PTM) 제어기 회귀 — <b>"예측이 이득이다"라는 결론이 우연히 나오지 않게</b> 고정한다.
 *
 * <p>핵심 테스트는 {@link #sameTemperatureDifferentFutureChangesDuty()}다. 제어기가 정말
 * 예측을 하는지는 "온도가 오르면 팬을 돌리는가"로는 확인할 수 없다 — 반응형도 그렇게 한다.
 * <b>같은 온도인데 앞으로 올 부하가 다르면 다르게 행동하는가</b>가 예측 제어의 정의이고,
 * 그것 하나만 확인하면 나머지는 물리가 따라온다.
 *
 * <p>이득만 고정하지 않고 <b>대가</b>도 함께 고정한다({@link #predictiveRunsHotterThanReactive()}) —
 * PTM은 허용된 온도 여유를 끝까지 쓰기 때문에 반응형보다 뜨겁게 돈다. 에너지만 보고
 * "PTM이 공짜로 좋다"고 읽으면 부품 수명 쪽 대가를 놓친다.
 */
class PtmControllerTest {

    private static final BoardType BOARD = BoardType.PI5;
    /** 무냉각으로는 목표 온도를 못 지키는 조건 — 팬을 돌릴 이유가 실제로 있는 실온이다. */
    private static final double AMBIENT_C = 40.0;

    private static AiLoadProfile profile(String id, List<AiLoadProfile.Segment> segments) {
        AiLoadProfile p = new AiLoadProfile();
        p.setId(id);
        p.setLabel(id);
        p.setSegments(segments);
        return p;
    }

    /** 2분 몰림 / 3분 한가 — 시정수(약 59초)와 견줄 만한 주기라 예측이 의미를 갖는다. */
    private static AiLoadProfile burst() {
        return profile("burst-test", List.of(
                new AiLoadProfile.Segment(120, 1.0, "몰림"),
                new AiLoadProfile.Segment(180, 0.2, "한가")));
    }

    private static AiLoadProfile constant(double level) {
        return profile("const-" + level, List.of(new AiLoadProfile.Segment(600, level, "일정")));
    }

    private static ThermalParams params() {
        return ThermalParams.preset(BOARD, CoolingPreset.PASSIVE, AMBIENT_C);
    }

    private static FanArraySpec fan() {
        return FanArraySpec.legacy(new FanSpec(5000, 5000, 0.5));
    }

    private static PtmController controller(PtmController.Mode mode, AiLoadProfile load, ThermalParams p) {
        double rPassive = p.rJaKPerW();
        double rActive = rPassive * (BOARD.rJaKPerW(CoolingPreset.ACTIVE) / BOARD.rJaKPerW(CoolingPreset.PASSIVE));
        return new PtmController(mode, p, load, WorkloadMode.MAX_THROUGHPUT, 10.0, fan(),
                rPassive, rActive, BOARD.rJcKPerW(),
                PtmController.DEFAULT_HORIZON_SEC, PtmController.DEFAULT_CONTROL_INTERVAL_SEC,
                PtmController.defaultTargetTempC(p), 0.5);
    }

    private static ThermalRun run(PtmController.Mode mode, AiLoadProfile load) {
        ThermalParams p = params();
        ThermalSimulator.Spec spec = new ThermalSimulator.Spec(
                p, WorkloadMode.MAX_THROUGHPUT, 10.0, 1800.0, RecoveryPolicy.NONE, 0.0,
                p.rJaKPerW(), 0.5, 30.0, false, load, null, fan(), controller(mode, load, p));
        return new ThermalSimulator().run(BOARD, spec);
    }

    // ── 예측 제어의 정의 ────────────────────────────────────────────────────

    /**
     * <b>이 테스트가 PTM의 정의다.</b> 온도가 같아도 앞으로 올 부하가 다르면 팬 회전수가 달라야
     * 한다 — 그렇지 않다면 이름만 예측일 뿐 온도 임계로 도는 반응형과 다르지 않다.
     */
    @Test
    void sameTemperatureDifferentFutureChangesDuty() {
        ThermalParams p = params();
        double sameTempC = 68.0;   // 목표(77℃)보다 아래 — 반응형이라면 지금 올릴 이유가 없다

        double busyAhead = controller(PtmController.Mode.PREDICTIVE, constant(1.0), p)
                .dutyAt(0.0, sameTempC);
        double idleAhead = controller(PtmController.Mode.PREDICTIVE, constant(0.05), p)
                .dutyAt(0.0, sameTempC);

        assertTrue(busyAhead > idleAhead, String.format(
                "같은 %.0f℃인데 부하가 몰려올 때(%.0f%%)가 한가할 때(%.0f%%)보다 세게 돌아야 한다",
                sameTempC, busyAhead * 100, idleAhead * 100));
        assertEquals(0.0, idleAhead, 1e-9, "앞으로 한가하면 지금 뜨겁더라도 팬을 돌릴 이유가 없다");
    }

    /** 대조 — 반응형은 미래를 보지 않으므로 같은 온도면 언제나 같은 답을 낸다. */
    @Test
    void reactiveIgnoresTheFuture() {
        ThermalParams p = params();
        double busy = controller(PtmController.Mode.REACTIVE, constant(1.0), p).dutyAt(0.0, 68.0);
        double idle = controller(PtmController.Mode.REACTIVE, constant(0.05), p).dutyAt(0.0, 68.0);
        assertEquals(busy, idle, 1e-9);
    }

    /** 목표 온도를 넘길 일이 없으면 예측형은 팬을 아예 돌리지 않는다 — "필요할 때만 돈다". */
    @Test
    void predictiveStaysOffWhenNoHeatIsComing() {
        ThermalParams p = params();
        PtmController c = controller(PtmController.Mode.PREDICTIVE, constant(0.05), p);
        assertEquals(0.0, c.dutyAt(0.0, p.idleSteadyTempC()), 1e-9);
    }

    @Test
    void alwaysMaxStaysAtFullSpeed() {
        ThermalRun r = run(PtmController.Mode.ALWAYS_MAX, burst());
        assertEquals(100.0, r.controlReport().meanDutyPercent(), 1e-6);
        assertEquals(0, r.controlReport().changeCount(), "회전수를 바꾸지 않는 방식이다");
    }

    // ── 이득과 대가 ─────────────────────────────────────────────────────────

    /**
     * 이 연구가 원하는 결론 — 예측형은 <b>스로틀링 없이</b> 두 기준선보다 에너지를 적게 쓴다.
     * 스로틀링 조건을 함께 걸어야 의미가 있다: 냉각을 포기해서 아낀 것은 절감이 아니다.
     */
    @Test
    void predictiveSavesEnergyWithoutThrottling() {
        ThermalRun max = run(PtmController.Mode.ALWAYS_MAX, burst());
        ThermalRun reactive = run(PtmController.Mode.REACTIVE, burst());
        ThermalRun ptm = run(PtmController.Mode.PREDICTIVE, burst());

        assertNull(ptm.tttSec(), "예측 제어에서 스로틀링이 나면 제어 목표를 못 지킨 것이다");
        assertTrue(ptm.totalEnergyJ() < max.totalEnergyJ(), String.format(
                "PTM %.0fJ < 항상최대 %.0fJ 여야 한다", ptm.totalEnergyJ(), max.totalEnergyJ()));
        assertTrue(ptm.totalEnergyJ() < reactive.totalEnergyJ(), String.format(
                "PTM %.0fJ < 반응형 %.0fJ 여야 한다", ptm.totalEnergyJ(), reactive.totalEnergyJ()));
        assertTrue(ptm.controlReport().meanDutyPercent() < reactive.controlReport().meanDutyPercent(),
                "절감의 출처는 낮은 평균 회전수다");
    }

    /**
     * <b>대가</b> — PTM은 허용된 온도 여유를 끝까지 쓰므로 반응형보다 뜨겁게 돈다.
     * 에너지만 보고 "공짜로 좋다"고 읽지 않도록 이 관계를 고정한다. 다만 목표 온도는 지켜야 한다.
     */
    @Test
    void predictiveRunsHotterThanReactive() {
        ThermalRun reactive = run(PtmController.Mode.REACTIVE, burst());
        ThermalRun ptm = run(PtmController.Mode.PREDICTIVE, burst());
        double target = PtmController.defaultTargetTempC(params());

        assertTrue(ptm.peakTempC() > reactive.peakTempC(),
                "여유를 쓰는 제어이므로 더 뜨거운 것이 정상이다");
        assertTrue(ptm.peakTempC() <= target + 1.0, String.format(
                "그래도 목표 %.1f℃는 지켜야 한다(실제 피크 %.1f℃)", target, ptm.peakTempC()));
    }

    /** 팬 에너지는 회전수의 3승을 따라간다 — 절감의 출처가 회전수라는 것을 고정한다. */
    @Test
    void fanEnergyFollowsCubeLaw() {
        FanArraySpec f = fan();
        assertEquals(f.arrayPowerWAt(1.0) / 8.0, f.arrayPowerWAt(0.5), 1e-9, "절반 회전수면 전력은 1/8");
        assertEquals(0.0, f.arrayPowerWAt(0.0), 1e-9);
    }

    // ── 하위호환·재현성 ─────────────────────────────────────────────────────

    /** 제어기를 넣지 않으면 기존 실행과 완전히 같아야 한다. */
    @Test
    void withoutControllerNothingChanges() {
        ThermalParams p = params();
        ThermalSimulator.Spec spec = new ThermalSimulator.Spec(
                p, WorkloadMode.MAX_THROUGHPUT, 10.0, 600.0, RecoveryPolicy.NONE, 0.0,
                p.rJaKPerW(), 0.5, 30.0, false, burst(), null, fan());
        ThermalRun r = new ThermalSimulator().run(BOARD, spec);

        assertNull(r.controlReport(), "제어기가 없으면 제어 보고서도 없어야 한다");
        assertNull(r.series().get(0).fanDutyPercent(), "제어를 안 쓰면 듀티 열 자체가 없어야 한다");
    }

    /** 같은 입력은 같은 결과 — 제어기가 상태를 들고 있어도 실행 간에 새지 않아야 한다(NFR-02). */
    @Test
    void sameInputGivesSameResult() {
        ThermalRun a = run(PtmController.Mode.PREDICTIVE, burst());
        ThermalRun b = run(PtmController.Mode.PREDICTIVE, burst());

        assertEquals(a.totalEnergyJ(), b.totalEnergyJ(), 1e-9);
        assertEquals(a.controlReport().meanDutyPercent(), b.controlReport().meanDutyPercent(), 1e-9);
        assertEquals(a.controlReport().changeCount(), b.controlReport().changeCount());
    }

    /** 제어 주기보다 자주 회전수를 바꾸지 않는다 — 팬이 떨리면 소음도 수명도 나빠진다. */
    @Test
    void fanSpeedChangesAreBoundedByControlInterval() {
        ThermalRun r = run(PtmController.Mode.PREDICTIVE, burst());
        int maxDecisions = (int) Math.ceil(1800.0 / PtmController.DEFAULT_CONTROL_INTERVAL_SEC);
        assertTrue(r.controlReport().changeCount() <= maxDecisions);
    }
}
