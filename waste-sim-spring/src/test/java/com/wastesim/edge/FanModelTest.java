package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 냉각팬 모델 검증 — 팬을 <b>전력을 쓰는 대가로 냉각을 사는 요소</b>로 다루는지 본다.
 *
 * <p>이 모델이 없으면 최적해가 언제나 최대 RPM이 된다(비용이 0이므로). 그러면 연구가
 * 도달해야 할 결론("최고 성능보다 적정 수준이 지속가능하다")과 모델이 정반대를 가리킨다.
 * 그래서 전력·냉각의 지수 차이와, 팬 전력이 SoC를 데우지 않는다는 점을 회귀로 고정한다.
 */
class FanModelTest {

    private final ThermalSimulator sim = new ThermalSimulator();

    private static final double RATED_RPM = 5000;
    private static final double RATED_W = 0.5;

    private ThermalSimulator.Spec spec(ThermalParams p, FanSpec fan, double loadSec) {
        return new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, loadSec,
                RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true, null, null, fan);
    }

    // ── 전력 법칙 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("전력은 회전수의 3승 — 절반만 돌리면 1/8만 쓴다")
    void powerFollowsCubeLaw() {
        assertEquals(0.0, FanSpec.off(RATED_RPM, RATED_W).powerW(), 1e-9);
        assertEquals(RATED_W / 8, new FanSpec(2500, RATED_RPM, RATED_W).powerW(), 1e-9);
        assertEquals(RATED_W, new FanSpec(5000, RATED_RPM, RATED_W).powerW(), 1e-9);
        // 정격을 넘겨 불러도 정격 이상으로는 세지 않는다
        assertEquals(RATED_W, new FanSpec(9000, RATED_RPM, RATED_W).powerW(), 1e-9);
    }

    @Test
    @DisplayName("냉각은 풍속의 0.8승 — 전력보다 훨씬 완만하게 좋아진다")
    void coolingGainIsMuchFlatterThanPowerCost() {
        double passive = 3.9, active = 2.6, rJc = 1.5;   // Pi5 프리셋
        double half = new FanSpec(2500, RATED_RPM, RATED_W).effectiveRJa(passive, active, rJc);
        double full = new FanSpec(5000, RATED_RPM, RATED_W).effectiveRJa(passive, active, rJc);

        assertEquals(passive, FanSpec.off(RATED_RPM, RATED_W).effectiveRJa(passive, active, rJc), 1e-9,
                "팬을 끄면 수동 냉각과 같아야 한다");
        assertEquals(active, full, 1e-9, "정격에서는 능동 냉각 프리셋과 같아야 한다");
        assertTrue(half > active && half < passive, "중간 회전수는 두 프리셋 사이여야 한다: " + half);

        // 절반 회전수가 벌어들이는 냉각이 전체 이득의 절반을 훨씬 넘는다 —
        // 전력은 1/8만 쓰면서. 이 비대칭이 "적정 수준" 결론의 근거다.
        double gainToHalf = passive - half;
        double gainHalfToFull = half - active;
        assertTrue(gainToHalf > gainHalfToFull * 2,
                String.format("절반까지의 이득(%.3f)이 나머지(%.3f)보다 훨씬 커야 한다", gainToHalf, gainHalfToFull));
    }

    // ── 팬 전력은 SoC를 데우지 않는다 (가장 중요) ────────────────────────

    @Test
    @DisplayName("팬 전력은 온도 계산에 들어가지 않는다 — 같은 열저항이면 온도가 동일하다")
    void fanPowerDoesNotHeatTheSoC() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.ACTIVE, 30.0);
        // 열저항을 직접 고정한 두 실행 — 하나는 팬 전력이 있고 하나는 없다.
        ThermalRun noFan = sim.run(BoardType.PI5, spec(p, null, 1200));
        ThermalRun withFan = sim.run(BoardType.PI5, spec(p, new FanSpec(5000, RATED_RPM, 5.0), 1200));

        assertEquals(noFan.peakTempC(), withFan.peakTempC(), 1e-9,
                "팬 전력이 온도에 새면 팬을 켤수록 뜨거워지는 거꾸로 된 모델이 된다");
        assertEquals(noFan.energyJ(), withFan.energyJ(), 1e-9, "SoC 에너지도 같아야 한다");
        assertTrue(withFan.fanEnergyJ() > 0, "팬 에너지는 따로 집계돼야 한다");
    }

    @Test
    @DisplayName("총 에너지는 SoC + 팬 — 가성비 판정의 분모다")
    void totalEnergyIsSocPlusFan() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.ACTIVE, 30.0);
        ThermalRun run = sim.run(BoardType.PI5, spec(p, new FanSpec(5000, RATED_RPM, RATED_W), 900));
        assertEquals(run.energyJ() + run.fanEnergyJ(), run.totalEnergyJ(), 0.2);
    }

    @Test
    @DisplayName("팬이 없으면 팬 에너지는 0이고 총합은 SoC와 같다 — 기존 동작 보존")
    void noFanKeepsLegacyBehaviour() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 30.0);
        ThermalRun legacy = sim.run(BoardType.PI5,
                new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, 600,
                        RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true));
        assertEquals(0.0, legacy.fanEnergyJ(), 1e-9);
        assertEquals(legacy.energyJ(), legacy.totalEnergyJ(), 1e-9);
    }

    // ── 있을 때 / 없을 때 비교 ──────────────────────────────────────────

    @Test
    @DisplayName("팬을 켜면 온도는 내려가지만 총 에너지는 올라간다 — 트레이드오프가 수치로 보인다")
    void fanTradesEnergyForTemperature() {
        double passive = 3.9, active = 2.6, rJc = 1.5;
        ThermalParams base = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 30.0);

        FanSpec off = FanSpec.off(RATED_RPM, RATED_W);
        FanSpec on = new FanSpec(5000, RATED_RPM, RATED_W);

        ThermalRun offRun = sim.run(BoardType.PI5,
                spec(base.withRJa(off.effectiveRJa(passive, active, rJc)), off, 1800));
        ThermalRun onRun = sim.run(BoardType.PI5,
                spec(base.withRJa(on.effectiveRJa(passive, active, rJc)), on, 1800));

        assertTrue(onRun.peakTempC() < offRun.peakTempC(),
                "팬을 켜면 더 시원해야 한다 (" + onRun.peakTempC() + " vs " + offRun.peakTempC() + ")");
        assertTrue(onRun.totalEnergyJ() > offRun.totalEnergyJ(),
                "팬을 켜면 총 에너지는 늘어야 한다 (" + onRun.totalEnergyJ() + " vs " + offRun.totalEnergyJ() + ")");
    }

    @Test
    @DisplayName("실행 결과에 팬 조건과 에너지 분해를 남긴다")
    void notesExplainFanCostAndCooling() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.ACTIVE, 30.0);
        String notes = String.join("\n",
                sim.run(BoardType.PI5, spec(p, new FanSpec(2500, RATED_RPM, RATED_W), 600)).notes());
        assertTrue(notes.contains("냉각팬"), notes);
        assertTrue(notes.contains("3승"), "전력 법칙을 설명해야 한다:\n" + notes);
        assertTrue(notes.contains("분모"), "가성비 분모가 총합이라고 알려야 한다:\n" + notes);
    }

    // ── 표현이 달라도 같은 팬 (실측 회귀) ───────────────────────────────

    /**
     * "팬 냉각"이라고만 해도 팬 전력이 집계돼야 한다.
     *
     * <p>실측 회귀: cooling=active는 프리셋 열저항만 가져오고 팬 객체를 만들지 않아
     * 냉각 효과는 그대로인데 비용이 0이었다. 같은 팬인데 "fanRpm=5000"이라고 말할
     * 때만 900J이 붙어서, <b>표현에 따라 결론이 달라지는</b> 상태였다.
     */
    @Test
    @DisplayName("cooling=active만 줘도 정격으로 도는 팬으로 보고 전력을 집계한다")
    void activeCoolingCountsFanEnergy() {
        FanSpec byPreset = EdgeToolSupport.fan(args("{\"cooling\":\"active\"}"));
        assertNotNull(byPreset, "'팬 냉각'인데 팬이 없으면 비용이 사라진다");
        assertEquals(byPreset.ratedRpm(), byPreset.rpm(), 1e-9, "회전수를 안 밝히면 정격으로 본다");
        assertTrue(byPreset.powerW() > 0);

        FanSpec byRpm = EdgeToolSupport.fan(args("{\"fanRpm\":5000,\"fanRatedRpm\":5000}"));
        assertEquals(byRpm.powerW(), byPreset.powerW(), 1e-9, "두 표현이 같은 팬이어야 한다");
    }

    @Test
    @DisplayName("회전수를 명시하면 그 값이 이긴다 — '팬 냉각 + 2500rpm'은 절반 속도")
    void explicitRpmWinsOverPreset() {
        FanSpec f = EdgeToolSupport.fan(args("{\"cooling\":\"active\",\"fanRpm\":2500,\"fanRatedRpm\":5000}"));
        assertEquals(2500.0, f.rpm(), 1e-9);
        assertEquals(0.5 / 8, f.powerW(), 1e-9, "절반 회전수는 전력이 1/8");
    }

    @Test
    @DisplayName("방열판·무냉각은 팬을 만들지 않는다 — 기존 동작 그대로")
    void nonActiveCoolingHasNoFan() {
        assertNull(EdgeToolSupport.fan(args("{\"cooling\":\"passive\"}")));
        assertNull(EdgeToolSupport.fan(args("{\"cooling\":\"bare\"}")));
        assertNull(EdgeToolSupport.fan(args("{}")));
    }

    private EdgeArgs args(String json) {
        try {
            return new EdgeArgs(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 방어 ────────────────────────────────────────────────────────────

    @Test
    void rejectsInvalidSpecs() {
        assertThrows(IllegalArgumentException.class, () -> new FanSpec(1000, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new FanSpec(-1, 5000, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new FanSpec(1000, 5000, -0.5));
    }

    @Test
    @DisplayName("내부 열저항이 전체보다 크면 거부한다 — 공기로 나갈 몫이 없다")
    void rejectsImpossibleResistanceSplit() {
        assertThrows(IllegalArgumentException.class,
                () -> new FanSpec(2500, RATED_RPM, RATED_W).effectiveRJa(3.9, 2.6, 5.0));
    }
}
