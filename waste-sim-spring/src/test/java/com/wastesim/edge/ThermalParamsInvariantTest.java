package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ThermalParams} 불변식 검증 (E-07 해소, 부록 B.2).
 *
 * <p>이 record는 public이라 {@link EdgeArgs}의 범위 검증을 거치지 않는 경로(캘리브레이션
 * 결과·방열판 모델·직접 생성)로도 만들어진다. 그 경로로 물리적으로 성립하지 않는 값이
 * 들어오면 시뮬레이터는 예외 없이 <b>정상 응답 모양의 틀린 결과</b>를 내놓는다 —
 * 발산한 온도나 영영 풀리지 않는 스로틀링 상태가 그대로 표에 실린다. fail-closed
 * 원칙(C3)이 도구 인자뿐 아니라 이 값 자체에도 걸려 있어야 하는 이유다.
 *
 * <p>여기서 막는 것은 <b>정책 범위가 아니라 불변식</b>이다 — "몇 ℃까지 허용하나"는
 * EdgeArgs의 몫이고, 이 테스트가 고정하는 것은 어떤 정책에서도 계산이 성립하지 않는 조합이다.
 */
class ThermalParamsInvariantTest {

    /** 프리셋을 기준선으로 두고 한 필드씩 망가뜨린다 — 무엇이 원인인지 테스트마다 하나로 좁힌다. */
    private final ThermalParams base = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 25.0);

    private ThermalParams with(double rJa, double cTh, double idle, double dyn,
                               int maxClock, int softFloor, int minClock,
                               double softLimit, double hardLimit, double hyst,
                               double maxFps, double startTemp) {
        return new ThermalParams(base.ambientC(), rJa, cTh, idle, dyn,
                maxClock, softFloor, minClock, softLimit, hardLimit, hyst, maxFps, startTemp);
    }

    @Test
    @DisplayName("정상 프리셋은 그대로 통과한다 — 불변식이 기존 동작을 막지 않는다")
    void presetsRemainValid() {
        for (BoardType b : BoardType.values()) {
            for (CoolingPreset c : CoolingPreset.values()) {
                assertDoesNotThrow(() -> ThermalParams.preset(b, c, 25.0),
                        b + "/" + c + " 프리셋이 불변식에 걸리면 안 된다");
            }
        }
    }

    @Test
    @DisplayName("R_ja가 0 이하면 거부한다 — 정상상태가 주변온도로 고정되거나 부하를 걸수록 식는다")
    void rejectsNonPositiveRJa() {
        assertThrows(IllegalArgumentException.class, () -> with(0.0, base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
        assertThrows(IllegalArgumentException.class, () -> with(-1.0, base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
    }

    @Test
    @DisplayName("C_th가 0 이하면 거부한다 — dT/dt가 0으로 나뉜다")
    void rejectsNonPositiveCTh() {
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), 0.0,
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
    }

    @Test
    @DisplayName("클럭은 0 < min ≤ softFloor ≤ max 여야 한다 — 뒤집히면 스로틀링 구간에서 클럭이 오른다")
    void rejectsInvertedClockOrder() {
        // softFloor < min — 소프트 제한이 하드 스로틀링보다 더 낮은 클럭으로 떨어진다
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), 2400, 800, 1000,
                base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
        // softFloor > max — 스로틀링이 걸리면 오히려 최대 클럭을 넘어선다
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), 2400, 2600, 1000,
                base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
        // min = 0 — 클럭이 0이면 FPS가 0이 되어 회복 지표가 정의되지 않는다
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), 2400, 1900, 0,
                base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
    }

    @Test
    @DisplayName("softLimit < hardLimit 이어야 한다 — 뒤집히면 소프트 구간이 존재할 수 없다")
    void rejectsSoftLimitAboveHardLimit() {
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), 90.0, 85.0, base.hysteresisC(),
                base.maxFps(), base.startTempC()));
        // 같아도 안 된다 — 소프트 구간의 폭이 0이면 두 비트가 동시에 켜진다
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), 85.0, 85.0, base.hysteresisC(),
                base.maxFps(), base.startTempC()));
    }

    @Test
    @DisplayName("음수 소비전력·음수 히스테리시스·0 이하 maxFps를 거부한다")
    void rejectsNegativeQuantities() {
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                -1.0, base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), -0.5,
                base.maxFps(), base.startTempC()));
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                0.0, base.startTempC()));
    }

    @Test
    @DisplayName("NaN·Infinity를 거부한다 — 범위 비교는 NaN에 대해 전부 false라 그냥 통과한다")
    void rejectsNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> with(Double.NaN, base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), base.startTempC()));
        assertThrows(IllegalArgumentException.class, () -> with(base.rJaKPerW(), base.cThJPerK(),
                base.idlePowerW(), base.dynamicPowerW(), base.maxClockMhz(), base.softFloorClockMhz(),
                base.minClockMhz(), base.softLimitC(), base.hardLimitC(), base.hysteresisC(),
                base.maxFps(), Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("with* 파생 메서드도 같은 불변식을 통과한다 — 우회 경로가 남으면 의미가 없다")
    void derivedInstancesAreValidatedToo() {
        assertThrows(IllegalArgumentException.class, () -> base.withRJa(0.0));
        assertThrows(IllegalArgumentException.class, () -> base.withCTh(-1.0));
        assertThrows(IllegalArgumentException.class, () -> base.withPower(-1.0, 5.0));
        assertDoesNotThrow(() -> base.withRJa(4.0).withCTh(20.0).withStartTemp(30.0));
    }
}
