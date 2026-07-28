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
