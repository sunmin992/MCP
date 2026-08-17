package com.wastesim.edge;

import java.util.ArrayList;
import java.util.List;

/**
 * 냉각팬 <b>배열</b> — 단일 {@link FanSpec} 위에 얹는 층. 기존 FanSpec은 팬 1개의
 * 전력·냉각 물리를 그대로 담당하고, 이 클래스는 그것을 <b>여러 개로 묶고, 불확실성과
 * 출처(실측인지 추정인지)를 정확히 보존</b>한다.
 *
 * <h3>왜 이 층이 따로 필요한가</h3>
 * Raspberry Pi 5 전용 4핀 팬 커넥터는 40×40×10 mm 팬을 2개 물릴 수 있고, PWM으로
 * 속도를 제어하며 TACH로 실제 RPM을 읽는다. 그런데 지금 확보한 전력 수치는 아직
 * <b>검증 전 추정값</b>이고, 그 값이 팬 1개 기준인지 2개 전체 기준인지도 확정되지
 * 않았다. 이 불확실성을 숫자에 섞어 버리면 나중에 "이 결과가 실측이었나 추정이었나"를
 * 복원할 수 없다 — 그래서 RPM·전력·배치·측정범위를 <b>출처와 함께</b> 들고 다닌다.
 *
 * <h3>실측 전에는 위치별 냉각 차이를 만들지 않는다</h3>
 * 팬 위치·송풍 방향({@link FanPlacement})은 실험 조건을 보존하는 <b>메타데이터</b>일
 * 뿐, 실측 보정 전까지 냉각계수를 임의로 만들어 내지 않는다(실험 설계 §10). 냉각(열저항)은
 * 배열의 유효 회전수 하나로만 결정되고, 팬을 2개로 늘렸다고 임의로 더 시원해지지 않는다.
 *
 * <h3>전력만은 배열이 의미가 있다</h3>
 * 온도(냉각)와 달리 <b>비용(전력)</b>은 팬 개수에 실제로 좌우된다. 다만 정격값이 팬별인지
 * 배열 전체인지({@link RatedValueScope})를 반드시 검사해 이중 계산을 막는다 — 배열 전체
 * 정격을 팬 개수로 다시 곱하면 비용이 2배로 부풀려진다.
 */
public record FanArraySpec(
        String presetId,
        List<Fan> fans,
        PowerSource powerSource,
        double supplyVoltageV,
        ControlMode controlMode,
        double commandedPwmPercent,
        boolean tachometerAvailable,
        Double measuredArrayRpm,
        MeasurementScope measurementScope,
        double ratedRpm,
        double ratedPowerW,
        double ratedCurrentA,
        RatedValueScope ratedValueScope,
        Double measuredCurrentA,
        Startup startup,
        SourceStatus sourceStatus,
        boolean verified) {

    /** 팬 1개. 위치 필드는 메타데이터라 미지정(null)일 수 있다. */
    public record Fan(String fanId, double widthMm, double heightMm, double thicknessMm,
                      Double offsetXmm, Double offsetYmm, Double distanceMm, FlowDirection flowDirection) {}

    /**
     * 팬 위치·송풍 방향 메타데이터. 실측 보정 전에는 조건 보존용으로만 쓴다(냉각계수 생성 X).
     * (실험 설계 §10)
     */
    public record FanPlacement(String fanId, double offsetXmm, double offsetYmm,
                               double distanceMm, FlowDirection flowDirection) {}

    /** 기동 순간 특성 — 전체 실행 동안 지속되는 전력이 아니라 켜는 순간의 피크다. */
    public record Startup(double peakCurrentA, double peakPowerW, Double durationSec) {}

    public enum PowerSource { PI5_DEDICATED_FAN_HEADER, EXTERNAL_SUPPLY, UNKNOWN }
    public enum ControlMode { PWM, VOLTAGE, ON_OFF }
    public enum FlowDirection {
        SUPPLY_DOWNWARD, SUPPLY_UPWARD, SUPPLY_HORIZONTAL, EXHAUST_UPWARD, PUSH_PULL;
        /** "SUPPLY"/"EXHAUST" 같은 느슨한 표기도 받아 대표 방향으로 매핑한다. */
        public static FlowDirection parse(String s) {
            if (s == null) return null;
            String u = s.trim().toUpperCase().replace("-", "_").replace(" ", "_");
            return switch (u) {
                case "SUPPLY", "SUPPLY_DOWN", "DOWN" -> SUPPLY_DOWNWARD;
                case "SUPPLY_UP", "UP" -> SUPPLY_UPWARD;
                case "SUPPLY_HORIZONTAL", "HORIZONTAL", "SIDE" -> SUPPLY_HORIZONTAL;
                case "EXHAUST", "EXHAUST_UP" -> EXHAUST_UPWARD;
                case "PUSH_PULL", "PUSHPULL" -> PUSH_PULL;
                default -> {
                    try { yield FlowDirection.valueOf(u); } catch (IllegalArgumentException e) { yield null; }
                }
            };
        }
    }
    /** 측정값이 팬별인지 배열 전체인지 — 아직 확정 못 했으면 UNKNOWN_PER_FAN_OR_TOTAL. */
    public enum MeasurementScope { PER_FAN, DUAL_FAN_TOTAL, UNKNOWN_PER_FAN_OR_TOTAL }
    /** 정격값이 팬별인지 배열 전체 가정인지 — 전력 이중 계산을 막는 기준. */
    public enum RatedValueScope { PER_FAN, DUAL_FAN_ARRAY_ASSUMED, ARRAY_TOTAL_MEASURED }
    /** 이 사양의 신뢰도. 임시 추정값을 실측처럼 표시하지 않기 위해 항상 함께 다닌다. */
    public enum SourceStatus { PRELIMINARY_ESTIMATE, PRELIMINARY_MIDPOINT, LEGACY_INPUT, MEASURED }
    /** 유효 회전수가 어디서 왔는지 — 결과의 신뢰도를 읽는 데 필요하다. */
    public enum RpmSource { TACH_MEASURED, CALIBRATION_CURVE, PWM_ESTIMATE, RATED_ASSUMED }

    public int fanCount() { return fans == null ? 0 : fans.size(); }

    // ── 회전수 결정 (실험 설계 §6 우선순위) ──────────────────────────────────
    // 1) TACH 실측  2) PWM-RPM 보정곡선(미구현 — 향후)  3) PWM 듀티 추정  4) 정격

    public RpmSource rpmSource() {
        if (measuredArrayRpm != null) return RpmSource.TACH_MEASURED;
        if (controlMode == ControlMode.PWM) return RpmSource.PWM_ESTIMATE;
        return RpmSource.RATED_ASSUMED;
    }

    public double effectiveRpm() {
        if (measuredArrayRpm != null) return measuredArrayRpm;
        if (controlMode == ControlMode.PWM) return ratedRpm * commandedPwmPercent / 100.0;
        return ratedRpm;   // PWM이 아니면 정격을 가정한다
    }

    /** 정격 대비 유효 회전수 비율(0~1). 냉각(열저항) 계산에 쓰인다. */
    public double dutyRatio() {
        return ratedRpm <= 0 ? 0.0 : Math.min(1.0, effectiveRpm() / ratedRpm);
    }

    /**
     * 냉각(열저항) 계산용 대표 팬. 팬을 몇 개로 묶든 냉각은 유효 회전수 하나로만
     * 결정한다 — 실측 전 위치·개수별 냉각 차이를 만들지 않기 위해서다(§10).
     */
    public FanSpec coolingFan() {
        return new FanSpec(effectiveRpm(), ratedRpm, ratedPowerW);
    }

    // ── 전력 (실험 설계 §7) — 이중 계산 방지가 핵심 ──────────────────────────

    /** 이 배열 전체의 소비전력(W). 실측 전류가 있으면 5V×I, 없으면 팬 상사법칙. */
    public double arrayPowerW() {
        if (measuredCurrentA != null) {
            double p = supplyVoltageV * measuredCurrentA;
            // 실측이 팬 1개 값이면 개수만큼 합산, 전체 값이면 그대로.
            return measurementScope == MeasurementScope.PER_FAN ? p * fanCount() : p;
        }
        double duty3 = Math.pow(dutyRatio(), FanSpec.POWER_EXPONENT);
        double base = ratedPowerW * duty3;
        // 정격이 팬별이면 개수만큼, 배열 전체 가정이면 그대로 — 배열 정격을 개수로
        // 다시 곱하면 비용이 2배가 된다.
        return ratedValueScope == RatedValueScope.PER_FAN ? base * fanCount() : base;
    }

    /**
     * 지정한 듀티(0~1)에서의 냉각 대표 팬 — 제어기가 매 순간 회전수를 바꿀 때 쓴다
     * ({@link PtmController}). {@link #coolingFan()}은 이 배열에 고정된 듀티를 쓰는 형태다.
     */
    public FanSpec coolingFanAt(double duty) {
        double d = Math.max(0.0, Math.min(1.0, duty));
        return new FanSpec(ratedRpm * d, ratedRpm, ratedPowerW);
    }

    /**
     * 지정한 듀티에서의 배열 전체 소비전력(W). 고정 운전용 {@link #arrayPowerW()}와 같은
     * 물리를 쓰되 듀티만 갈아끼운다.
     *
     * <p>실측 전류가 있으면 그 값을 버리지 않는다 — 실측은 <b>측정한 그 한 점</b>의 전력이므로,
     * 그 점을 기준으로 상사법칙(3승)으로 늘리고 줄인다. 실측을 무시하고 정격으로 되돌아가면
     * 애써 잰 값이 제어 실행에서만 조용히 빠지게 된다.
     */
    public double arrayPowerWAt(double duty) {
        double d = Math.max(0.0, Math.min(1.0, duty));
        double cube = Math.pow(d, FanSpec.POWER_EXPONENT);
        if (measuredCurrentA != null) {
            double measuredDuty = dutyRatio();
            // 측정 지점이 정지 상태면 기준으로 삼을 수 없다 — 정격 경로로 물러선다.
            if (measuredDuty > 1e-9) {
                return arrayPowerW() * cube / Math.pow(measuredDuty, FanSpec.POWER_EXPONENT);
            }
        }
        double base = ratedPowerW * cube;
        return ratedValueScope == RatedValueScope.PER_FAN ? base * fanCount() : base;
    }

    /** 이 회전수에서의 배열 전체 소비전류(A) 추정. 실측이 있으면 그 값. */
    public Double arrayCurrentA() {
        if (measuredCurrentA != null) {
            return measurementScope == MeasurementScope.PER_FAN
                    ? measuredCurrentA * fanCount() : measuredCurrentA;
        }
        return supplyVoltageV <= 0 ? null : arrayPowerW() / supplyVoltageV;
    }

    // ── 기동 전력 (실험 설계 §8) — 피크는 지속 전력이 아니다 ─────────────────

    /**
     * 기동 에너지(J). 기동 지속시간을 모르면 {@code null} — 피크 전력을 전체 실행시간
     * 동안 지속되는 것으로 계산하면 비용이 터무니없이 커진다. 총에너지에 넣지 않고
     * 경고({@code STARTUP_ENERGY_NOT_INCLUDED})만 남긴다.
     */
    public Double startupEnergyJ() {
        if (startup == null || startup.durationSec() == null) return null;
        return startup.peakPowerW() * startup.durationSec();
    }

    // ── 경고 (실험 설계 §11) — 이 프로젝트는 warnings 채널이 없어 notes로 나간다 ──

    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        if (!verified) w.add("FAN_SPEC_NOT_VERIFIED");
        if (rpmSource() == RpmSource.PWM_ESTIMATE) w.add("RPM_ESTIMATED_FROM_PWM");
        if (measurementScope == MeasurementScope.UNKNOWN_PER_FAN_OR_TOTAL) w.add("FAN_POWER_SCOPE_UNKNOWN");
        if (startup != null && startup.durationSec() == null) w.add("STARTUP_ENERGY_NOT_INCLUDED");
        if (tachometerAvailable && measuredArrayRpm != null
                && measurementScope == MeasurementScope.UNKNOWN_PER_FAN_OR_TOTAL) {
            w.add("TACH_SIGNAL_SCOPE_UNKNOWN");
        }
        return w;
    }

    // ── 프리셋·변환 ──────────────────────────────────────────────────────────

    /**
     * Raspberry Pi 5 전용 4핀 헤더 + 40 mm 팬 2개, 검증 전 임시 사양(실험 설계 §3~5).
     * 전력 프로파일은 아직 팬 1개 기준인지 배열 전체인지 확정되지 않아
     * {@code UNKNOWN_PER_FAN_OR_TOTAL} / {@code PRELIMINARY_ESTIMATE}로 둔다.
     */
    public static FanArraySpec pi5DualPreliminary(double commandedPwmPercent) {
        List<Fan> fans = List.of(
                new Fan("FAN_1", 40, 40, 10, null, null, null, null),
                new Fan("FAN_2", 40, 40, 10, null, null, null, null));
        return new FanArraySpec(
                "PI5_DUAL_40MM_PRELIMINARY", fans,
                PowerSource.PI5_DEDICATED_FAN_HEADER, 5.0, ControlMode.PWM,
                clampPwm(commandedPwmPercent), true, null,
                MeasurementScope.UNKNOWN_PER_FAN_OR_TOTAL,
                7750.0, 0.625, 0.125,          // §5 대표 중앙값(배열 전체 가정)
                RatedValueScope.DUAL_FAN_ARRAY_ASSUMED, null,
                new Startup(0.2, 1.0, null),   // §4 기동 피크, 지속시간 미확정
                SourceStatus.PRELIMINARY_ESTIMATE, false);
    }

    /**
     * 기존 단일 {@link FanSpec} 입력을 팬 1개짜리 배열로 감싼다(하위호환, §13).
     * 정격은 팬별({@code PER_FAN}), 출처는 {@code LEGACY_INPUT}으로 표시한다.
     */
    public static FanArraySpec legacy(FanSpec fan) {
        if (fan == null) return null;
        double pwm = fan.ratedRpm() <= 0 ? 0.0 : Math.min(100.0, fan.rpm() / fan.ratedRpm() * 100.0);
        List<Fan> fans = List.of(new Fan("FAN_1", 0, 0, 0, null, null, null, null));
        return new FanArraySpec(
                "LEGACY_SINGLE_FAN", fans,
                PowerSource.UNKNOWN, 5.0, ControlMode.PWM, pwm, false, null,
                MeasurementScope.PER_FAN,
                fan.ratedRpm(), fan.ratedPowerW(), 0.0,
                RatedValueScope.PER_FAN, null, null,
                SourceStatus.LEGACY_INPUT, false);
    }

    private static double clampPwm(double pwm) {
        return Math.max(0.0, Math.min(100.0, pwm));
    }
}
