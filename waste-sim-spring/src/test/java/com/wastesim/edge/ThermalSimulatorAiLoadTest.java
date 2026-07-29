package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시변 AI 부하 패턴이 시뮬레이터에 제대로 반영되는지 검증한다.
 *
 * <p>여기서 지켜야 할 것은 "숫자가 그럴듯한가"가 아니라 <b>연구 질문이 성립하는가</b>다.
 * 이 실험의 가설은 "부하가 출렁이면 열용량이 온도 진폭을 좌우해서, 상수 부하일 때와
 * 방열판 순위가 달라질 수 있다"인데, 그러려면 최소한 (1) 패턴이 실제로 온도 곡선을
 * 바꿔야 하고 (2) 같은 평균 부하라도 모양이 다르면 피크 온도가 달라야 하며
 * (3) 열용량이 큰 쪽이 진폭을 더 눌러야 한다. 셋 중 하나라도 깨지면 패턴을 넣는
 * 의미 자체가 없어지므로 회귀로 고정한다.
 */
class ThermalSimulatorAiLoadTest {

    private final ThermalSimulator sim = new ThermalSimulator();
    private final AiLoadProfileService loads = new AiLoadProfileService();

    private ThermalSimulator.Spec spec(ThermalParams p, AiLoadProfile load, double loadSec) {
        return new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, loadSec,
                RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true, load);
    }

    /** 스로틀링이 끼면 클럭 거버너가 곡선을 꺾어 패턴 효과와 뒤섞인다 —
     *  패턴 자체의 영향만 보려면 스로틀링이 없는 조건이어야 한다. */
    private ThermalParams coolParams() {
        return ThermalParams.preset(BoardType.PI4, CoolingPreset.ACTIVE, 25.0);
    }

    // ── 하위호환 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("패턴이 null이면 기존 상수 부하와 완전히 같은 결과가 나온다")
    void nullProfileIsIdenticalToConstantLoad() {
        ThermalParams p = coolParams();
        ThermalRun withoutField = sim.run(BoardType.PI4,
                new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, 600,
                        RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true));
        ThermalRun withNull = sim.run(BoardType.PI4, spec(p, null, 600));

        assertEquals(withoutField.peakTempC(), withNull.peakTempC(), 1e-9);
        assertEquals(withoutField.steadyStateTempC(), withNull.steadyStateTempC(), 1e-9);
        assertEquals(withoutField.energyJ(), withNull.energyJ(), 1e-9);
    }

    @Test
    @DisplayName("steady 패턴은 배율이 항상 1.0이라 패턴 없음과 온도가 같다")
    void steadyProfileMatchesNoProfile() {
        ThermalParams p = coolParams();
        ThermalRun none = sim.run(BoardType.PI4, spec(p, null, 600));
        ThermalRun steady = sim.run(BoardType.PI4, spec(p, loads.find("steady"), 600));
        assertEquals(none.peakTempC(), steady.peakTempC(), 1e-6);
    }

    // ── 패턴이 실제로 곡선을 바꾸는가 ────────────────────────────────────

    @Test
    @DisplayName("버스트 패턴은 부하가 빠지는 구간에서 온도가 실제로 내려간다")
    void burstProducesOscillatingTemperature() {
        ThermalRun run = sim.run(BoardType.PI4, spec(coolParams(), loads.find("burst"), 900));
        List<ThermalRun.Sample> s = run.series();

        // 상수 부하라면 온도는 단조 증가한다. 내려가는 구간이 있다는 것 자체가 패턴이 먹혔다는 증거.
        boolean everFell = false;
        for (int i = 1; i < s.size(); i++) {
            if (s.get(i).socTempC() < s.get(i - 1).socTempC() - 1e-6) { everFell = true; break; }
        }
        assertTrue(everFell, "버스트의 한가 구간에서 온도가 내려가야 한다");
    }

    @Test
    @DisplayName("버스트는 평균 부하가 낮으므로 상수 부하보다 피크 온도가 낮다")
    void burstPeaksLowerThanConstantFullLoad() {
        ThermalParams p = coolParams();
        ThermalRun steady = sim.run(BoardType.PI4, spec(p, loads.find("steady"), 1800));
        ThermalRun burst = sim.run(BoardType.PI4, spec(p, loads.find("burst"), 1800));

        assertTrue(burst.peakTempC() < steady.peakTempC(),
                "burst(평균 배율 0.52) 피크 " + burst.peakTempC()
                        + "℃가 steady 피크 " + steady.peakTempC() + "℃보다 낮아야 한다");
        assertTrue(burst.energyJ() < steady.energyJ(), "버스트는 총 소비 에너지도 적어야 한다");
    }

    // ── 연구 질문의 핵심: 열용량이 진폭을 누르는가 ────────────────────────

    @Test
    @DisplayName("같은 열저항이라도 열용량이 크면 버스트에서 온도 진폭이 작다 — 순위 역전의 물리적 근거")
    void higherThermalMassDampensOscillation() {
        ThermalParams light = coolParams();
        ThermalParams heavy = new ThermalParams(light.ambientC(), light.rJaKPerW(),
                light.cThJPerK() * 4, light.idlePowerW(), light.dynamicPowerW(),
                light.maxClockMhz(), light.softFloorClockMhz(), light.minClockMhz(),
                light.softLimitC(), light.hardLimitC(), light.hysteresisC(),
                light.maxFps(), light.startTempC());

        double lightSwing = swingAfterWarmup(sim.run(BoardType.PI4, spec(light, loads.find("burst"), 3600)));
        double heavySwing = swingAfterWarmup(sim.run(BoardType.PI4, spec(heavy, loads.find("burst"), 3600)));

        assertTrue(heavySwing < lightSwing,
                "열용량 4배인 쪽의 진폭(" + heavySwing + "℃)이 더 작아야 한다(가벼운 쪽 " + lightSwing + "℃)");
    }

    /** 초기 과도(데워지는 구간)를 빼고, 후반부의 최고-최저 온도차(진동 진폭)를 잰다. */
    private double swingAfterWarmup(ThermalRun run) {
        List<ThermalRun.Sample> s = run.series();
        List<ThermalRun.Sample> tail = s.subList(s.size() * 2 / 3, s.size());
        double hi = tail.stream().mapToDouble(ThermalRun.Sample::socTempC).max().orElse(0);
        double lo = tail.stream().mapToDouble(ThermalRun.Sample::socTempC).min().orElse(0);
        return hi - lo;
    }

    // ── 해석을 돕는 notes ────────────────────────────────────────────────

    @Test
    @DisplayName("패턴을 쓰면 시간 규모 판정과 진동 범위를 notes로 알려준다")
    void notesExplainPatternBehaviour() {
        ThermalRun run = sim.run(BoardType.PI4, spec(coolParams(), loads.find("burst"), 900));
        String all = String.join("\n", run.notes());
        assertTrue(all.contains("AI 부하 패턴"), "어떤 패턴으로 돌렸는지 남겨야 한다");
        assertTrue(all.contains("과도응답"), "시간 규모 판정을 설명해야 한다");
        assertTrue(all.contains("진동"), "정상상태가 하나가 아님을 설명해야 한다");
    }

    @Test
    @DisplayName("느린 패턴을 넣으면 준정상상태라고 경고한다 — 실험이 실패하는 대표 사례")
    void slowPatternIsFlaggedAsQuasiStatic() {
        AiLoadProfile diurnal = new AiLoadProfile();
        diurnal.setId("diurnal");
        diurnal.setLabel("일주기");
        diurnal.setSegments(List.of(
                new AiLoadProfile.Segment(3600, 1.0, "낮"),
                new AiLoadProfile.Segment(3600, 0.2, "밤")));

        ThermalRun run = sim.run(BoardType.PI4, spec(coolParams(), diurnal, 600));
        assertTrue(String.join("\n", run.notes()).contains("준정상상태"));
    }

    // ── 스로틀링 판정이 피크 기준인지 ────────────────────────────────────

    @Test
    @DisplayName("평균 부하로는 안 걸려도 피크에서 걸릴 수 있는 조건은 '스로틀링 예상'으로 판정한다")
    void throttlingExpectedUsesPeakLevelNotMean() {
        // 무냉각·고온 — 최대 부하면 확실히 스로틀링, 평균 배율(0.52)이면 애매한 조건
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 35.0);
        ThermalRun burst = sim.run(BoardType.PI5, spec(p, loads.find("burst"), 900));

        assertTrue(burst.throttlingExpected(),
                "피크 배율 1.0에서 정상상태가 하드 제한을 넘으므로 '예상'이어야 한다");
        // 보고되는 정상상태 온도는 진동의 중심(평균 배율)이라 피크 기준보다 낮다
        assertTrue(burst.steadyStateTempC() < p.ambientC()
                        + (p.idlePowerW() + p.dynamicPowerW()) * p.rJaKPerW(),
                "보고 값은 평균 배율 기준이라 최대 배율 정상상태보다 낮아야 한다");
    }
}
