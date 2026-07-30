package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2노드 열모델(SoC ↔ 방열판) 검증.
 *
 * <p>가장 중요한 성질은 <b>정상상태가 1노드와 같아야 한다</b>는 것이다
 * ({@code R_ja = R_int + R_hs}, 직렬 분해). 이게 깨지면 2노드로 바꾼 순간 기존에
 * 검증해 둔 모든 절대 온도가 어긋나므로, 개선이 아니라 회귀가 된다. 달라져야 하는 것은
 * <b>과도응답뿐</b>이고, 그 차이가 이 연구의 핵심(부하가 출렁일 때 방열판 질량이 피크를
 * 흡수해 순위가 뒤집히는 현상)이다.
 */
class TwoNodeThermalModelTest {

    private final ThermalSimulator sim = new ThermalSimulator();
    private final AiLoadProfileService loads = new AiLoadProfileService();

    /** 스로틀링이 끼면 거버너가 곡선을 꺾어 모델 차이와 뒤섞인다 — 순수 비교용 조건. */
    private ThermalParams coolParams() {
        return ThermalParams.preset(BoardType.PI4, CoolingPreset.ACTIVE, 25.0);
    }

    private ThermalSimulator.Spec spec(ThermalParams p, AiLoadProfile load, HeatsinkMass hs, double loadSec) {
        return new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, loadSec,
                RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true, load, hs);
    }

    // ── 정상상태 동등성 (가장 중요) ──────────────────────────────────────

    @Test
    @DisplayName("2노드의 정상상태는 1노드와 같다 — R_ja = R_int + R_hs 직렬 분해")
    void steadyStateMatchesOneNodeModel() {
        ThermalParams p = coolParams();
        HeatsinkMass hs = new HeatsinkMass(40.0, 1.5);

        ThermalRun one = sim.run(BoardType.PI4, spec(p, null, null, 7200));
        ThermalRun two = sim.run(BoardType.PI4, spec(p, null, hs, 7200));

        // 충분히 오래 돌린 뒤의 최종 온도가 일치해야 한다(과도응답만 다르다)
        double endOne = one.series().get(one.series().size() - 1).socTempC();
        double endTwo = two.series().get(two.series().size() - 1).socTempC();
        assertEquals(endOne, endTwo, 0.15,
                "정상상태가 어긋나면 2노드 전환이 회귀가 된다");
        assertEquals(one.steadyStateTempC(), two.steadyStateTempC(), 1e-9,
                "이론 정상상태는 열저항만으로 정해지므로 완전히 같아야 한다");
    }

    @Test
    @DisplayName("손으로 검산 — T_ss = T_주변 + P·(R_int + R_hs)")
    void steadyStateIsHandCheckable() {
        ThermalParams p = coolParams();
        HeatsinkMass hs = new HeatsinkMass(40.0, 1.0);
        double expected = p.ambientC() + (p.idlePowerW() + p.dynamicPowerW()) * p.rJaKPerW();

        ThermalRun run = sim.run(BoardType.PI4, spec(p, null, hs, 7200));
        double end = run.series().get(run.series().size() - 1).socTempC();
        assertEquals(expected, end, 0.3);
    }

    // ── 하위호환 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("heatsink가 null이면 기존 1노드와 완전히 동일하다")
    void nullHeatsinkIsUnchanged() {
        ThermalParams p = coolParams();
        ThermalRun legacy = sim.run(BoardType.PI4,
                new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, 600,
                        RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true));
        ThermalRun explicitNull = sim.run(BoardType.PI4, spec(p, null, null, 600));
        assertEquals(legacy.peakTempC(), explicitNull.peakTempC(), 1e-9);
        assertEquals(legacy.energyJ(), explicitNull.energyJ(), 1e-9);
    }

    // ── 과도응답 — 연구의 핵심 ──────────────────────────────────────────

    @Test
    @DisplayName("방열판 질량이 클수록 버스트에서 온도 진폭이 작다")
    void heavierHeatsinkDampensOscillation() {
        ThermalParams p = coolParams();
        AiLoadProfile burst = loads.find("burst");

        double lightSwing = swing(sim.run(BoardType.PI4,
                spec(p, burst, new HeatsinkMass(10.0, 1.5), 3600)));
        double heavySwing = swing(sim.run(BoardType.PI4,
                spec(p, burst, new HeatsinkMass(120.0, 1.5), 3600)));

        assertTrue(heavySwing < lightSwing,
                "무거운 쪽 진폭(" + heavySwing + "℃)이 가벼운 쪽(" + lightSwing + "℃)보다 작아야 한다");
    }

    @Test
    @DisplayName("같은 열저항·다른 질량이면 상수 부하에서는 무승부, 버스트에서는 무거운 쪽이 이긴다")
    void massOnlyMattersUnderVaryingLoad() {
        ThermalParams p = coolParams();
        HeatsinkMass light = new HeatsinkMass(10.0, 1.5);
        HeatsinkMass heavy = new HeatsinkMass(120.0, 1.5);

        // 상수 부하 — 열용량은 정상상태 식에 없으므로 피크가 같아야 한다
        double steadyLight = sim.run(BoardType.PI4, spec(p, loads.find("steady"), light, 5400)).peakTempC();
        double steadyHeavy = sim.run(BoardType.PI4, spec(p, loads.find("steady"), heavy, 5400)).peakTempC();
        assertEquals(steadyLight, steadyHeavy, 0.3, "상수 부하에서는 질량이 결과를 바꾸면 안 된다");

        // 버스트 — 무거운 쪽이 피크를 흡수한다
        double burstLight = sim.run(BoardType.PI4, spec(p, loads.find("burst"), light, 5400)).peakTempC();
        double burstHeavy = sim.run(BoardType.PI4, spec(p, loads.find("burst"), heavy, 5400)).peakTempC();
        assertTrue(burstHeavy < burstLight,
                "버스트에서는 무거운 쪽 피크(" + burstHeavy + "℃)가 낮아야 한다(가벼운 쪽 " + burstLight + "℃)");
    }

    /**
     * 이 연구의 결론 형태 — 다만 역전은 <b>무조건 일어나지 않는다</b>. 질량이 벌어주는
     * 진폭 감쇠가 열저항 열세가 만드는 평균 온도 상승보다 커야만 뒤집힌다.
     *
     * <p>실측 기준(Pi4·팬·25℃, 질량비 12.5배)으로 임계는 열저항 격차 약 0.4~0.5 K/W다.
     * 이 수치가 실험 설계에 그대로 쓰인다 — 학생들의 CFD에서 형상 간 열저항 차이가
     * 이보다 크게 벌어지면, 부하 패턴을 아무리 넣어도 순위는 뒤집히지 않는다.
     */
    @Test
    @DisplayName("역전은 조건부다 — 열저항 격차가 작을 때만 무거운 쪽이 이긴다")
    void rankingFlipDependsOnResistanceGap() {
        ThermalParams p = coolParams();
        HeatsinkMass lightSink = new HeatsinkMass(12.0, 1.0);
        HeatsinkMass heavySink = new HeatsinkMass(150.0, 1.0);
        ThermalParams pa = p.withRJa(2.6);                       // A — 열저항 우수, 가벼움

        // 상수 부하에서는 항상 열저항이 좋은 A가 이긴다(열용량은 정상상태 식에 없다)
        double steadyA = sim.run(BoardType.PI4, spec(pa, loads.find("steady"), lightSink, 5400)).peakTempC();
        double steadyB = sim.run(BoardType.PI4,
                spec(p.withRJa(2.9), loads.find("steady"), heavySink, 5400)).peakTempC();
        assertTrue(steadyA < steadyB, "상수 부하에서는 A가 이겨야 한다");

        double burstA = sim.run(BoardType.PI4, spec(pa, loads.find("burst"), lightSink, 5400)).peakTempC();

        // 격차가 작으면(0.3 K/W) 질량 이점이 이겨 순위가 뒤집힌다
        double burstNarrow = sim.run(BoardType.PI4,
                spec(p.withRJa(2.9), loads.find("burst"), heavySink, 5400)).peakTempC();
        assertTrue(burstNarrow < burstA,
                "격차가 작으면 무거운 쪽이 이겨야 한다 (A " + burstA + "℃ vs B " + burstNarrow + "℃)");

        // 격차가 크면(0.8 K/W) 질량으로도 못 뒤집는다 — 이 경계가 실험 설계의 근거다
        double burstWide = sim.run(BoardType.PI4,
                spec(p.withRJa(3.4), loads.find("burst"), heavySink, 5400)).peakTempC();
        assertTrue(burstWide > burstA,
                "격차가 크면 질량으로도 못 뒤집는다 (A " + burstA + "℃ vs B " + burstWide + "℃)");
    }

    // ── 질량 → 열용량 변환 ──────────────────────────────────────────────

    @Test
    @DisplayName("질량과 재질로 열용량을 만든다 — 같은 질량이면 알루미늄이 구리보다 열용량이 크다")
    void massToCapacityConversion() {
        HeatsinkMass al = HeatsinkMass.ofMass(50, HeatsinkLayout.Material.ALUMINUM, 1.5);
        HeatsinkMass cu = HeatsinkMass.ofMass(50, HeatsinkLayout.Material.COPPER, 1.5);
        assertEquals(45.0, al.cThJPerK(), 1e-9);    // 0.05kg × 900
        assertEquals(19.25, cu.cThJPerK(), 1e-9);   // 0.05kg × 385
        assertTrue(al.cThJPerK() > cu.cThJPerK(),
                "비열이 알루미늄 900 > 구리 385이므로 같은 질량이면 알루미늄이 크다");
    }

    // ── 방어 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("내부 열저항이 전체보다 크면 거부한다 — 음수 저항으로 발산하는 것을 막는다")
    void internalResistanceCannotExceedTotal() {
        HeatsinkMass hs = new HeatsinkMass(40.0, 5.0);
        ThermalParams p = coolParams();   // active 프리셋은 전체 열저항이 5.0보다 작다
        assertThrows(IllegalArgumentException.class,
                () -> sim.run(BoardType.PI4, spec(p, null, hs, 600)));
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> new HeatsinkMass(0, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new HeatsinkMass(40, 0));
    }

    @Test
    @DisplayName("상수 부하로 2노드를 돌리면 효과가 안 드러난다고 알려준다")
    void notesWarnWhenTwoNodeIsPointlessUnderConstantLoad() {
        ThermalRun run = sim.run(BoardType.PI4,
                spec(coolParams(), loads.find("steady"), new HeatsinkMass(40.0, 1.5), 600));
        String all = String.join("\n", run.notes());
        assertTrue(all.contains("2노드"));
        assertTrue(all.contains("burst"), "패턴을 함께 쓰라고 안내해야 한다");
    }

    private double swing(ThermalRun run) {
        List<ThermalRun.Sample> s = run.series();
        List<ThermalRun.Sample> tail = s.subList(s.size() * 2 / 3, s.size());
        return tail.stream().mapToDouble(ThermalRun.Sample::socTempC).max().orElse(0)
             - tail.stream().mapToDouble(ThermalRun.Sample::socTempC).min().orElse(0);
    }
}
