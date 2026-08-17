package com.wastesim.edge;

/**
 * 예측 냉각(PTM, Predictive Thermal Management) 제어기 — <b>팬 회전수를 시간에 따라 정한다.</b>
 *
 * <h3>왜 필요한가</h3>
 * 지금까지 이 시뮬레이터의 팬은 실행 내내 <b>한 회전수로 고정</b>돼 있었다. 그래서 답할 수
 * 있는 질문이 "이 조건에서 가장 싼 고정 회전수는?"({@code sweep_fan_rpm})까지였다. 그런데
 * 부하가 시간에 따라 오르내리면({@link AiLoadProfile}) 고정 운전은 반드시 손해다 — 한가한
 * 구간에서도 피크에 맞춘 회전수로 계속 돌리거나, 피크에 맞추지 못해 스로틀링을 맞거나
 * 둘 중 하나다. <b>"부하가 크지 않은데 팬이 항상 돌 필요는 없다"</b>가 이 제어기가 답하는
 * 질문이고, 그것이 이 연구의 지속가능성 결론과 같은 방향이다.
 *
 * <h3>세 가지 모드를 나란히 두는 이유</h3>
 * <ul>
 *   <li>{@link Mode#ALWAYS_MAX} — 항상 정격. 스로틀링은 확실히 막지만 전력이 가장 크다(순진한 기준선)</li>
 *   <li>{@link Mode#REACTIVE} — <b>온도를 보고</b> 올린다(라즈베리파이 공식 팬 커브 근사).
 *       실제 보드가 하는 일이라 이것이 "현실 기준선"이다</li>
 *   <li>{@link Mode#PREDICTIVE} — <b>다가올 부하를 보고</b> 미리 올린다. 이것이 PTM이다</li>
 * </ul>
 * 셋을 같은 조건에서 돌려 비교해야 "예측이 실제로 이득인가"에 답이 된다. 예측만 돌리면
 * 얼마를 아꼈는지 알 수 없다.
 *
 * <h3>예측이 반응보다 나을 수 있는 이유는 열 지연이다</h3>
 * 온도는 부하를 시정수 τ만큼 늦게 따라온다. 반응형은 <b>이미 뜨거워진 뒤에야</b> 팬을 올리므로
 * 그 지연만큼 온도가 오버슈트하고, 오버슈트를 되돌리려면 한동안 정격으로 돌려야 한다. 예측형은
 * 부하가 오기 <b>전에</b> 미리 식혀 두므로 같은 피크 온도를 더 낮은 평균 회전수로 지킬 수 있다.
 * 전력은 회전수의 3승이라 평균 회전수를 조금만 낮춰도 에너지가 크게 준다 — 이 비대칭이
 * PTM의 이득이 나오는 자리다.
 *
 * <h3>제어기는 모델을 완벽히 알지 못한다(의도적)</h3>
 * 예측은 <b>1노드</b> RC로 하고 스로틀링이 없다고 가정한다. 실제 적분은 2노드일 수도 있고
 * 거버너가 클럭을 낮추기도 한다. 제어기에 완전한 모델을 주면 "예측이 좋다"는 결론이 모델을
 * 두 번 쓴 결과가 되어 실험이 자기 자신을 증명하게 된다. 실제 장치에 올릴 때도 제어기는
 * 간이 모델만 들고 있으므로, 이 쪽이 현실적이면서 결과를 부풀리지 않는다.
 */
public final class PtmController {

    /** 제어 방식. */
    public enum Mode {
        /** 항상 정격 회전수(순진한 기준선). */
        ALWAYS_MAX,
        /** 현재 온도만 보고 단계적으로 올린다(라즈베리파이 공식 팬 커브 근사). */
        REACTIVE,
        /** 다가올 부하를 내다보고 미리 올린다 — PTM. */
        PREDICTIVE,
        /** 사용자가 지정한 고정 듀티로 계속 돈다(스윕이 찾은 운전점을 넣어 비교할 때). */
        FIXED;

        public static Mode parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase().replace("-", "_").replace(" ", "_")) {
                case "always_max", "max", "full" -> ALWAYS_MAX;
                case "reactive", "thermostat", "curve" -> REACTIVE;
                case "predictive", "ptm", "predict" -> PREDICTIVE;
                case "fixed", "constant" -> FIXED;
                default -> null;
            };
        }

        public String labelKo() {
            return switch (this) {
                case ALWAYS_MAX -> "항상 최대";
                case REACTIVE -> "반응형(온도 기반)";
                case PREDICTIVE -> "예측형(PTM)";
                case FIXED -> "고정 회전수";
            };
        }
    }

    /**
     * 라즈베리파이 공식 팬 커브 근사 — {온도(℃), 듀티(0~1)} 계단.
     * 반응형 기준선을 임의로 만들지 않고 실제 보드가 쓰는 곡선을 그대로 쓴다.
     */
    private static final double[][] REACTIVE_CURVE = {
            {50.0, 0.30}, {60.0, 0.50}, {67.5, 0.70}, {75.0, 1.00}
    };
    /** 계단을 내려올 때 필요한 여유(℃) — 임계 근처에서 팬이 떨렸다 붙었다 하는 것을 막는다. */
    private static final double REACTIVE_HYSTERESIS_C = 3.0;

    /** 후보 듀티 격자(0~100%, 10%)로 훑는다. 연속 최적화 대신 격자를 쓰는 이유는 재현성이다. */
    private static final double[] DUTY_GRID = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
    /** 예측 적분 간격(초). 시정수(수십 초)보다 충분히 작다. */
    private static final double PREDICT_STEP_SEC = 1.0;

    public static final double DEFAULT_HORIZON_SEC = 60.0;
    public static final double DEFAULT_CONTROL_INTERVAL_SEC = 5.0;
    /** 목표 온도의 기본값은 소프트 제한에서 이만큼 아래다 — 성능 저하가 시작되기 전에 막는다. */
    public static final double DEFAULT_TARGET_MARGIN_C = 3.0;
    /** 듀티를 <b>낮출</b> 때만 요구하는 추가 여유(℃). 올릴 때는 요구하지 않는다(안전 비대칭). */
    public static final double RELEASE_MARGIN_C = 2.0;

    private final Mode mode;
    private final ThermalParams params;
    private final AiLoadProfile load;
    private final WorkloadMode workloadMode;
    private final double targetFps;
    private final FanArraySpec fanArray;
    private final double rJaPassive;
    private final double rJaActive;
    private final double rInternal;
    private final double horizonSec;
    private final double controlIntervalSec;
    private final double targetTempC;
    private final double fixedDuty;

    // ── 상태 ────────────────────────────────────────────────────────────────
    private double duty;
    private double nextDecisionAtSec;
    private int changeCount;
    private double dutyTimeIntegral;   // ∫duty dt — 평균 듀티의 분자
    private double observedSec;
    private double peakDuty;
    private double lastTSec;

    public PtmController(Mode mode, ThermalParams params, AiLoadProfile load,
                         WorkloadMode workloadMode, double targetFps, FanArraySpec fanArray,
                         double rJaPassive, double rJaActive, double rInternal,
                         double horizonSec, double controlIntervalSec, double targetTempC,
                         double fixedDuty) {
        this.mode = mode;
        this.params = params;
        this.load = load;
        this.workloadMode = workloadMode;
        this.targetFps = targetFps;
        this.fanArray = fanArray;
        this.rJaPassive = rJaPassive;
        this.rJaActive = rJaActive;
        this.rInternal = rInternal;
        this.horizonSec = horizonSec;
        this.controlIntervalSec = controlIntervalSec;
        this.targetTempC = targetTempC;
        this.fixedDuty = Math.max(0.0, Math.min(1.0, fixedDuty));
        this.duty = switch (mode) {
            case ALWAYS_MAX -> 1.0;
            case FIXED -> this.fixedDuty;
            // 반응형·예측형은 정지 상태에서 출발한다 — "필요할 때만 돈다"가 두 방식의 전제다.
            case REACTIVE, PREDICTIVE -> 0.0;
        };
    }

    public Mode mode() { return mode; }
    public double targetTempC() { return targetTempC; }
    public double horizonSec() { return horizonSec; }
    public double controlIntervalSec() { return controlIntervalSec; }

    /**
     * 이 시각·이 온도에서 팬 듀티(0~1). 매 적분 스텝마다 불리지만 <b>실제 결정은 제어 주기마다</b>
     * 한다 — 실제 제어기도 매 밀리초 회전수를 바꾸지 않고, 그렇게 두면 팬이 계속 떨린다.
     */
    public double dutyAt(double tSec, double socTempC) {
        if (mode == Mode.ALWAYS_MAX || mode == Mode.FIXED) return duty;

        if (tSec + 1e-9 >= nextDecisionAtSec) {
            double decided = mode == Mode.REACTIVE ? reactiveDuty(socTempC)
                                                   : predictiveDuty(tSec, socTempC);
            if (Math.abs(decided - duty) > 1e-9) {
                changeCount++;
                duty = decided;
            }
            nextDecisionAtSec = tSec + controlIntervalSec;
        }
        return duty;
    }

    /** 시간 가중 통계 누적 — 스텝 폭이 균일하지 않으므로 샘플 개수로 평균내면 안 된다. */
    public void accumulate(double tSec, double stepSec) {
        if (stepSec <= 0) return;
        dutyTimeIntegral += duty * stepSec;
        observedSec += stepSec;
        peakDuty = Math.max(peakDuty, duty);
        lastTSec = tSec;
    }

    public double meanDuty() { return observedSec > 1e-9 ? dutyTimeIntegral / observedSec : duty; }
    public double peakDuty() { return peakDuty; }
    public int changeCount() { return changeCount; }
    public double currentDuty() { return duty; }

    /** 이 듀티에서의 전체 열저항(K/W). 팬이 없으면 수동 냉각 값 그대로. */
    public double rJaFor(double d) {
        if (fanArray == null) return rJaPassive;
        return fanArray.coolingFanAt(d).effectiveRJa(rJaPassive, rJaActive, rInternal);
    }

    /** 이 듀티에서의 팬 소비전력(W). */
    public double fanPowerWFor(double d) {
        return fanArray == null ? 0.0 : fanArray.arrayPowerWAt(d);
    }

    // ── 반응형 ──────────────────────────────────────────────────────────────

    /**
     * 온도만 보고 계단 커브에서 듀티를 고른다. 내려올 때는 히스테리시스만큼 더 식어야 한다 —
     * 임계에 걸친 온도에서 팬이 붙었다 떨어졌다 하면 소음도 전력도 최악이 된다.
     */
    private double reactiveDuty(double tempC) {
        double target = 0.0;
        for (double[] step : REACTIVE_CURVE) {
            if (tempC >= step[0]) target = step[1];
        }
        if (target >= duty) return target;
        // 내려가는 방향 — 한 계단 아래 임계보다 히스테리시스만큼 더 낮아야 실제로 내린다.
        double relaxed = 0.0;
        for (double[] step : REACTIVE_CURVE) {
            if (tempC >= step[0] - REACTIVE_HYSTERESIS_C) relaxed = step[1];
        }
        return Math.min(duty, relaxed);
    }

    // ── 예측형(PTM) ─────────────────────────────────────────────────────────

    /**
     * 다가올 {@code horizonSec} 동안의 부하를 미리 적분해 보고, <b>목표 온도를 지키는 가장 낮은
     * 듀티</b>를 고른다. 격자를 낮은 쪽부터 훑어 처음 만족하는 값에서 멈추므로 항상 최소 듀티다.
     *
     * <p>듀티를 낮추는 방향에는 {@link #RELEASE_MARGIN_C}만큼 여유를 더 요구한다. 올릴 때는
     * 요구하지 않는다 — 늦게 올리면 스로틀링을 맞지만 늦게 내리면 전기만 조금 더 쓸 뿐이라,
     * 두 실수의 대가가 다르기 때문이다.
     */
    private double predictiveDuty(double tSec, double tempC) {
        for (double d : DUTY_GRID) {
            double limit = d < duty ? targetTempC - RELEASE_MARGIN_C : targetTempC;
            if (predictPeakTempC(tSec, tempC, d) <= limit) return d;
        }
        return 1.0;
    }

    /**
     * 이 듀티로 계속 돌렸을 때 지평 안에서 도달할 최고 온도(℃)를 1노드 RC로 내다본다.
     *
     * <p>스로틀링이 없다고 가정하는 것이 <b>보수적인</b> 방향이다 — 실제로는 거버너가 클럭을
     * 낮춰 발열이 줄기 때문에, 예측은 실제보다 뜨겁게 나온다. 제어기가 조금 이르게·조금 세게
     * 도는 쪽으로 틀리므로 안전하다.
     */
    private double predictPeakTempC(double t0, double temp0, double d) {
        double rJa = rJaFor(d);
        double temp = temp0;
        double peak = temp0;
        for (double t = t0; t < t0 + horizonSec; t += PREDICT_STEP_SEC) {
            double level = load == null ? 1.0 : load.levelAt(t);
            double powerW = params.idlePowerW() + params.dynamicPowerW() * utilAt(level);
            temp += (powerW - (temp - params.ambientC()) / rJa) / params.cThJPerK() * PREDICT_STEP_SEC;
            peak = Math.max(peak, temp);
        }
        return peak;
    }

    /** 이 부하 배율에서의 사용률 — 시뮬레이터의 클럭 100% 상황과 같은 식이다. */
    private double utilAt(double level) {
        if (workloadMode == WorkloadMode.TARGET_FPS) {
            return Math.min(1.0, targetFps * level / Math.max(params.maxFps(), 1e-9));
        }
        return Math.min(1.0, level);
    }

    /** 목표 온도 기본값 — 소프트 제한보다 여유만큼 아래(성능 저하 시작 전에 막는다). */
    public static double defaultTargetTempC(ThermalParams p) {
        return p.softLimitC() - DEFAULT_TARGET_MARGIN_C;
    }
}
