package com.wastesim.edge;

/**
 * 열·전력 모델 파라미터 한 벌. {@link ThermalSimulator}가 그대로 쓰는 최종 값이며,
 * 세 경로로 만들어진다.
 * <ol>
 *   <li>{@link #preset(BoardType, CoolingPreset, double)} — 보드·냉각 프리셋 기본값(문헌 추정치)</li>
 *   <li>{@link HeatsinkThermalModel} — 방열판 형상·배치로 계산한 R_ja를 끼워넣음</li>
 *   <li>{@code calibrate_edge_thermal_model} 결과 — 실측 시계열로 역추정한 값(가장 신뢰도 높음)</li>
 * </ol>
 *
 * @param ambientC        주변(실내) 온도 ℃
 * @param rJaKPerW        전체 열저항 K/W — 정상상태 온도 = ambient + P × rJa
 * @param cThJPerK        등가 열용량 J/K — 시정수 τ = rJa × cTh
 * @param idlePowerW      유휴 소비전력 W
 * @param dynamicPowerW   최대 클럭·100% 사용률에서의 추가 소비전력 W
 * @param maxClockMhz     최대 ARM 클럭
 * @param softFloorClockMhz 소프트 온도 제한 구간에서 낮아지는 클럭(MHz)
 * @param minClockMhz     하드 스로틀링 시 클럭 하한
 * @param softLimitC      소프트 온도 제한(℃) — 이 온도부터 클럭을 서서히 낮춘다(get_throttled bit 3 = 0x8)
 * @param hardLimitC      하드 스로틀링 온도(℃) — 클럭을 하한까지 떨어뜨린다(bit 2 = 0x4)
 * @param hysteresisC     스로틀링 해제 히스테리시스(℃) — hardLimit − hysteresis 아래로 내려가야 비트가 풀린다
 * @param maxFps          스로틀링이 없을 때의 최대 추론 처리량(FPS)
 * @param startTempC      실험 시작 시점 온도(idle 상태 온도) ℃
 */
public record ThermalParams(
        double ambientC,
        double rJaKPerW,
        double cThJPerK,
        double idlePowerW,
        double dynamicPowerW,
        int maxClockMhz,
        int softFloorClockMhz,
        int minClockMhz,
        double softLimitC,
        double hardLimitC,
        double hysteresisC,
        double maxFps,
        double startTempC) {

    /**
     * 물리적으로 성립하지 않는 파라미터 한 벌을 생성 시점에 막는다(E-07 해소).
     *
     * <p>이 record는 public이라 도구 계층({@link EdgeArgs})의 범위 검증을 거치지 않고도
     * 만들어질 수 있다 — 캘리브레이션 결과·방열판 모델·테스트가 직접 생성자를 부른다.
     * 그 경로로 rJa=0이나 softLimit>hardLimit 같은 값이 들어오면 시뮬레이터는 예외 없이
     * 발산하거나 스로틀링 상태머신이 영영 풀리지 않는 결과를 <b>정상 응답으로</b> 내놓는다.
     * fail-closed 원칙(C3)은 도구 인자뿐 아니라 이 값 자체에도 걸려 있어야 한다.
     *
     * <p>범위가 아니라 <b>불변식</b>만 검사한다. "몇 ℃까지 허용할 것인가" 같은 정책 범위는
     * EdgeArgs의 몫이고, 여기서 막는 것은 어떤 정책에서도 계산이 성립하지 않는 조합이다.
     */
    public ThermalParams {
        requireFinite(ambientC, "ambientC");
        requireFinite(startTempC, "startTempC");
        requireFinite(idlePowerW, "idlePowerW");
        requireFinite(dynamicPowerW, "dynamicPowerW");
        requireFinite(hysteresisC, "hysteresisC");

        // R_ja = 0이면 정상상태 온도가 주변온도로 고정되고, 음수면 부하를 걸수록 식는다.
        if (!(rJaKPerW > 0)) {
            throw new IllegalArgumentException("rJaKPerW는 0보다 커야 한다: " + rJaKPerW);
        }
        // C_th = 0이면 dT/dt가 0으로 나뉜다.
        if (!(cThJPerK > 0)) {
            throw new IllegalArgumentException("cThJPerK는 0보다 커야 한다: " + cThJPerK);
        }
        if (idlePowerW < 0 || dynamicPowerW < 0) {
            throw new IllegalArgumentException(
                    "소비전력은 음수일 수 없다: idle=" + idlePowerW + ", dynamic=" + dynamicPowerW);
        }
        if (!(maxFps > 0)) {
            throw new IllegalArgumentException("maxFps는 0보다 커야 한다: " + maxFps);
        }
        // 거버너가 클럭을 내리는 순서 그대로의 부등식. 뒤집히면 스로틀링 구간에서
        // 오히려 클럭이 올라간다.
        if (!(0 < minClockMhz && minClockMhz <= softFloorClockMhz && softFloorClockMhz <= maxClockMhz)) {
            throw new IllegalArgumentException("클럭은 0 < min ≤ softFloor ≤ max 여야 한다: min="
                    + minClockMhz + ", softFloor=" + softFloorClockMhz + ", max=" + maxClockMhz);
        }
        // 소프트 제한이 하드보다 높으면 소프트 구간이 존재할 수 없다.
        if (!(softLimitC < hardLimitC)) {
            throw new IllegalArgumentException(
                    "softLimitC < hardLimitC 여야 한다: soft=" + softLimitC + ", hard=" + hardLimitC);
        }
        // 히스테리시스가 음수면 해제 온도가 진입 온도보다 높아져 상태가 진동한다.
        if (hysteresisC < 0) {
            throw new IllegalArgumentException("hysteresisC는 음수일 수 없다: " + hysteresisC);
        }
    }

    private static void requireFinite(double v, String field) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(field + "는 유한한 값이어야 한다: " + v);
        }
    }

    /** 라즈베리파이 펌웨어 기본 소프트 제한(℃). */
    public static final double DEFAULT_SOFT_LIMIT_C = 80.0;
    /** 라즈베리파이 펌웨어 기본 하드 스로틀링 온도(℃). */
    public static final double DEFAULT_HARD_LIMIT_C = 85.0;
    public static final double DEFAULT_HYSTERESIS_C = 2.0;

    /** 보드·냉각 프리셋 기본값. startTemp는 해당 냉각 조건의 유휴 정상상태 온도로 잡는다. */
    public static ThermalParams preset(BoardType board, CoolingPreset cooling, double ambientC) {
        double rJa = board.rJaKPerW(cooling);
        return new ThermalParams(
                ambientC, rJa, board.cThJPerK(),
                board.idlePowerW(), board.dynamicPowerW(),
                board.maxClockMhz(), board.softFloorClockMhz(), board.minClockMhz(),
                DEFAULT_SOFT_LIMIT_C, DEFAULT_HARD_LIMIT_C, DEFAULT_HYSTERESIS_C,
                board.maxFps(),
                ambientC + board.idlePowerW() * rJa);
    }

    public ThermalParams withRJa(double newRJa) {
        return new ThermalParams(ambientC, newRJa, cThJPerK, idlePowerW, dynamicPowerW,
                maxClockMhz, softFloorClockMhz, minClockMhz, softLimitC, hardLimitC, hysteresisC, maxFps,
                ambientC + idlePowerW * newRJa);
    }

    public ThermalParams withStartTemp(double t) {
        return new ThermalParams(ambientC, rJaKPerW, cThJPerK, idlePowerW, dynamicPowerW,
                maxClockMhz, softFloorClockMhz, minClockMhz, softLimitC, hardLimitC, hysteresisC, maxFps, t);
    }

    /**
     * 주변 온도만 바꾼다 — 시작 온도도 같은 폭만큼 평행이동한다. 실측 캘리브레이션으로 얻은
     * R_ja·C_th는 그대로 두고 "같은 보드를 더운 방에서 돌리면?"을 외삽할 때 쓴다.
     */
    public ThermalParams withAmbient(double newAmbient) {
        double delta = newAmbient - ambientC;
        return new ThermalParams(newAmbient, rJaKPerW, cThJPerK, idlePowerW, dynamicPowerW,
                maxClockMhz, softFloorClockMhz, minClockMhz, softLimitC, hardLimitC, hysteresisC,
                maxFps, startTempC + delta);
    }

    public ThermalParams withMaxFps(double fps) {
        return new ThermalParams(ambientC, rJaKPerW, cThJPerK, idlePowerW, dynamicPowerW,
                maxClockMhz, softFloorClockMhz, minClockMhz, softLimitC, hardLimitC, hysteresisC, fps, startTempC);
    }

    public ThermalParams withCTh(double c) {
        return new ThermalParams(ambientC, rJaKPerW, c, idlePowerW, dynamicPowerW,
                maxClockMhz, softFloorClockMhz, minClockMhz, softLimitC, hardLimitC, hysteresisC, maxFps, startTempC);
    }

    public ThermalParams withPower(double idleW, double dynW) {
        return new ThermalParams(ambientC, rJaKPerW, cThJPerK, idleW, dynW,
                maxClockMhz, softFloorClockMhz, minClockMhz, softLimitC, hardLimitC, hysteresisC, maxFps, startTempC);
    }

    /** 가열 시정수 τ_h = R_ja × C_th (초). */
    public double tauSeconds() { return rJaKPerW * cThJPerK; }

    /** 스로틀링이 없다고 가정한 최대부하 정상상태 온도(℃). 이 값이 hardLimit보다 낮으면 스로틀링이 발생하지 않는다. */
    public double fullLoadSteadyTempC() { return ambientC + (idlePowerW + dynamicPowerW) * rJaKPerW; }

    /** 유휴 정상상태 온도(℃). */
    public double idleSteadyTempC() { return ambientC + idlePowerW * rJaKPerW; }

    public double minClockRatio() { return (double) minClockMhz / maxClockMhz; }

    /** 소프트 제한 구간의 클럭비 — 하드 스로틀링(minClockRatio)보다는 완만하다. */
    public double softFloorRatio() { return (double) softFloorClockMhz / maxClockMhz; }
}
