package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실측 역추정 검증. <b>파라미터를 알고 있는 합성 곡선</b>을 만들어 넣고 원래 값을 되찾는지
 * 본다(inverse-problem 자기검증). 잡음 없는 경우와 실제 로그 수준의 잡음(±0.3℃, 0.1℃ 양자화)이
 * 있는 경우를 모두 확인한다 — 잡음에서 무너지는 적합법은 현장 데이터에 쓸 수 없다.
 */
class ThermalCalibratorTest {

    private final ThermalCalibrator cal = new ThermalCalibrator();

    private List<ThermalCalibrator.Sample> synthetic(double r, double c, double amb, double loadW,
                                                      double idleW, int loadSec, int coolSec,
                                                      double noiseSd, long seed) {
        double tau = r * c;
        double tInf = amb + loadW * r;
        double t0 = amb + idleW * r;
        double tIdle = amb + idleW * r;
        Random rnd = new Random(seed);
        List<ThermalCalibrator.Sample> out = new ArrayList<>();
        for (int t = 0; t <= loadSec; t += 5) {
            double T = tInf - (tInf - t0) * Math.exp(-t / tau) + rnd.nextGaussian() * noiseSd;
            out.add(new ThermalCalibrator.Sample(t, Math.round(T * 10) / 10.0, loadW, 1500.0, 12.0, T >= 85));
        }
        double tEnd = tInf - (tInf - t0) * Math.exp(-loadSec / tau);
        for (int t = loadSec + 5; t <= loadSec + coolSec; t += 5) {
            double T = tIdle + (tEnd - tIdle) * Math.exp(-(t - loadSec) / tau) + rnd.nextGaussian() * noiseSd;
            out.add(new ThermalCalibrator.Sample(t, Math.round(T * 10) / 10.0, idleW, 1500.0, 0.0, false));
        }
        return out;
    }

    @Test
    @DisplayName("잡음 없는 곡선에서 R_ja·C_th·τ를 1% 이내로 되찾는다")
    void recoversParametersExactly() {
        var s = synthetic(6.2, 13.5, 24.0, 6.4, 2.7, 900, 900, 0.0, 1);
        var r = cal.calibrate(s, 24.0, BoardType.PI4, 900.0);

        assertEquals(6.2, r.rJaKPerW(), 0.062);
        assertEquals(13.5, r.cThJPerK(), 0.135);
        assertEquals(83.7, r.heating().tauSec(), 1.0);
        assertTrue(r.heating().rSquared() > 0.999);
        assertNotNull(r.cooling(), "냉각 구간이 있으면 τ_c도 추정해야 한다");
        assertEquals(83.7, r.cooling().tauSec(), 3.0);
    }

    @Test
    @DisplayName("실제 로그 수준의 잡음(±0.3℃)에서도 5% 이내로 추정한다")
    void robustToRealisticNoise() {
        var s = synthetic(6.2, 13.5, 24.0, 6.4, 2.7, 900, 900, 0.3, 42);
        var r = cal.calibrate(s, 24.0, BoardType.PI4, 900.0);

        assertEquals(6.2, r.rJaKPerW(), 6.2 * 0.05);
        assertEquals(83.7, r.heating().tauSec(), 83.7 * 0.05);
        assertTrue(r.heating().rSquared() > 0.95);
        assertTrue(r.quality().startsWith("양호"));
    }

    @Test
    @DisplayName("전력 측정이 없으면 보드 기본값으로 계산하고 그 사실을 경고한다")
    void warnsWhenPowerIsMissing() {
        var s = new ArrayList<ThermalCalibrator.Sample>();
        for (var x : synthetic(6.2, 13.5, 24.0, BoardType.PI4.fullLoadPowerW(), 2.7, 900, 0, 0.0, 7)) {
            s.add(new ThermalCalibrator.Sample(x.tSec(), x.socTempC(), null, null, null, null));
        }
        var r = cal.calibrate(s, 24.0, BoardType.PI4, 900.0);
        assertEquals("board-default", r.powerSource());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("전력")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("throttled")));
    }

    @Test
    @DisplayName("throttled 열이 있으면 실측 TTT·TED를 그대로 뽑아낸다")
    void extractsMeasuredThrottlingMetrics() {
        List<ThermalCalibrator.Sample> s = new ArrayList<>();
        for (int t = 0; t <= 300; t += 5) {
            boolean th = t >= 100 && t < 140;                    // 40초짜리 에피소드 하나
            s.add(new ThermalCalibrator.Sample(t, 50 + t * 0.1, 6.4, 1500.0, 12.0, th));
        }
        var r = cal.calibrate(s, 25.0, BoardType.PI4, 300.0);
        assertEquals(100.0, r.measuredTttSec(), 1e-9);
        assertEquals(List.of(40.0), r.measuredTeds());
    }

    @Test
    @DisplayName("샘플이 너무 적거나 상승 곡선이 아니면 예외로 거부한다(엉뚱한 파라미터를 만들지 않는다)")
    void rejectsUnusableData() {
        var few = List.of(
                new ThermalCalibrator.Sample(0, 40, null, null, null, null),
                new ThermalCalibrator.Sample(5, 41, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> cal.calibrate(few, 25.0, BoardType.PI4, 5.0));

        List<ThermalCalibrator.Sample> flatFalling = new ArrayList<>();
        for (int t = 0; t <= 100; t += 5) {
            flatFalling.add(new ThermalCalibrator.Sample(t, 60 - t * 0.1, 6.4, null, null, null));
        }
        assertThrows(IllegalArgumentException.class,
                () -> cal.calibrate(flatFalling, 25.0, BoardType.PI4, 100.0));
    }
}
