package com.wastesim.edge;

import java.util.Comparator;
import java.util.List;

/**
 * RPM 스윕 전체 결과 — 곡선(모든 지점), 제약, 선택된 최적 운전점
 * (FAN_RPM_SWEEP_DESIGN.md §9).
 *
 * <h3>적합 지점이 없으면 최적점을 만들지 않는다</h3>
 * 모든 지점이 제약을 어겼는데 "그중 제일 나은 것"을 최적점이라고 내놓으면, 사용자는
 * 스로틀링이 걸리는 운전점을 권장값으로 읽는다. 그래서 {@code optimal}은 null이 되고
 * 상태가 {@code NO_FEASIBLE_POINT}가 된다(§8).
 *
 * @param objective    적용한 목적함수
 * @param status       {@code OPTIMAL_FOUND} 또는 {@code NO_FEASIBLE_POINT}
 * @param optimal      선택된 운전점. 적합 지점이 없으면 null
 * @param optimalReason 왜 이 지점이 뽑혔는지 — 결과만 보고 재현할 수 있게 남긴다
 * @param points       실행한 모든 지점(탈락 포함). 발표용 곡선의 원본이다
 * @param constraints  적용한 제약값
 * @param tieToleranceRelative 목적함수 동률 판정의 상대 허용 오차(§6.3)
 */
public record FanSweepResult(
        Objective objective,
        String status,
        FanSweepPoint optimal,
        String optimalReason,
        List<FanSweepPoint> points,
        Constraints constraints,
        double tieToleranceRelative) {

    public static final String STATUS_FOUND = "OPTIMAL_FOUND";
    public static final String STATUS_NONE = "NO_FEASIBLE_POINT";

    /**
     * 동률 허용 오차 — 목적함수 값이 이 비율 안에서 같으면 같은 값으로 보고 §6.3의
     * 순서(낮은 PWM → 낮은 RPM → 낮은 피크 온도)로 고른다.
     *
     * <p>0으로 두면 총에너지가 27000.0J과 27000.1J인 두 지점에서 <b>부동소수점 끝자리가
     * 운전점을 정한다</b> — 물리적으로 구분되지 않는 차이로 PWM 100%가 50%를 이길 수 있다.
     */
    public static final double TIE_TOLERANCE = 1e-3;

    /** 목적함수(§7.2). 전부 "작을수록 좋다"로 통일해 비교 로직을 하나로 유지한다. */
    public enum Objective {
        MIN_TOTAL_ENERGY, MIN_ENERGY_PER_FRAME, MIN_FAN_ENERGY, MIN_PWM;

        public static Objective parse(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase()) {
                case "min_total_energy" -> MIN_TOTAL_ENERGY;
                case "min_energy_per_frame" -> MIN_ENERGY_PER_FRAME;
                case "min_fan_energy" -> MIN_FAN_ENERGY;
                case "min_pwm" -> MIN_PWM;
                default -> null;
            };
        }

        public String wire() { return name().toLowerCase(); }

        /**
         * 운용 모드별 기본 목적함수(§6). 최대 처리량 모드에서 총에너지를 최소화하면
         * <b>일을 덜 한 저RPM 지점</b>이 이기므로 프레임당 에너지가 기본이다.
         */
        public static Objective defaultFor(WorkloadMode mode) {
            return mode == WorkloadMode.MAX_THROUGHPUT ? MIN_ENERGY_PER_FRAME : MIN_TOTAL_ENERGY;
        }

        public String labelKo() {
            return switch (this) {
                case MIN_TOTAL_ENERGY -> "총에너지 최소";
                case MIN_ENERGY_PER_FRAME -> "프레임당 에너지 최소";
                case MIN_FAN_ENERGY -> "팬 에너지 최소";
                case MIN_PWM -> "최저 PWM";
            };
        }
    }

    /**
     * 적합 판정 기준(§5.1).
     *
     * <p>{@code tttSec == null}만 보면 안 되는 이유가 이 record가 존재하는 이유다 —
     * 하드 스로틀링 없이 소프트 제한에 눌러앉아 처리량을 20% 잃는 조건이 "정상"으로
     * 뽑히면, 스윕이 찾아 준 최적점이 실제로는 성능을 포기한 지점이 된다.
     *
     * @param maxPeakTempC             허용 최고 온도(℃)
     * @param maxThroughputLossPercent 허용 지속 처리량 손실률(%)
     * @param minTargetFpsRatio        목표 FPS 대비 최소 유지 비율(TARGET_FPS 모드 전용)
     */
    public record Constraints(double maxPeakTempC, double maxThroughputLossPercent,
                              double minTargetFpsRatio) {
        public static final double DEFAULT_MAX_PEAK_TEMP_C = 85.0;
        public static final double DEFAULT_MAX_THROUGHPUT_LOSS_PERCENT = 1.0;
        public static final double DEFAULT_MIN_TARGET_FPS_RATIO = 0.99;

        public static Constraints defaults() {
            return new Constraints(DEFAULT_MAX_PEAK_TEMP_C, DEFAULT_MAX_THROUGHPUT_LOSS_PERCENT,
                    DEFAULT_MIN_TARGET_FPS_RATIO);
        }
    }

    /**
     * 적합 지점 중 목적함수가 가장 작은 지점을 고른다. 동률이면 §6.3 순서를 따른다.
     *
     * @return 최적점. 적합 지점이 하나도 없거나 전부 목적함수 값을 낼 수 없으면 null
     */
    public static FanSweepPoint select(List<FanSweepPoint> points, Objective objective) {
        List<FanSweepPoint> feasible = points.stream()
                .filter(FanSweepPoint::feasible)
                .filter(p -> p.objectiveValue(objective) != null)
                .toList();
        if (feasible.isEmpty()) return null;

        double best = feasible.stream()
                .mapToDouble(p -> p.objectiveValue(objective))
                .min().orElseThrow();
        // 동률 판정은 상대 오차로 한다 — 총에너지는 수만 J, PWM은 0~100이라 절대 오차
        // 하나로는 두 축을 같은 기준으로 다룰 수 없다.
        double tolerance = Math.abs(best) * TIE_TOLERANCE;
        return feasible.stream()
                .filter(p -> p.objectiveValue(objective) <= best + tolerance)
                .min(Comparator.comparingDouble(FanSweepPoint::commandedPwmPercent)
                        .thenComparingDouble(FanSweepPoint::effectiveRpm)
                        .thenComparingDouble(FanSweepPoint::peakTempC))
                .orElse(null);
    }
}
