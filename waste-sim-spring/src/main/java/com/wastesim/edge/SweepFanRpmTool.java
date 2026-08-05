package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구 {@code sweep_fan_rpm} — 팬 PWM/RPM을 바꿔가며 같은 실험을 반복 실행하고,
 * 성능·온도 제약을 만족하는 지점 중 <b>에너지가 가장 적게 드는 운전점</b>을 고른다
 * (FAN_RPM_SWEEP_DESIGN.md).
 *
 * <h3>이 도구가 답하는 질문</h3>
 * <blockquote>요구 FPS와 온도 한계를 지키면서 총에너지(또는 추론 1건당 에너지)가 최소가
 * 되는 팬 속도는 얼마인가?</blockquote>
 * 팬 전력은 회전수의 3승, 냉각은 풍속의 0.8승에 비례한다({@link FanSpec}). 이 비대칭
 * 때문에 최적점은 대개 <b>정격보다 한참 아래</b>에 있고, 그 지점을 숫자로 찍어 주는 것이
 * 이 연구의 지속가능성 결론이다.
 *
 * <h3>{@code simulate_edge_throttling}과 나누는 이유</h3>
 * 단일 실행은 결과가 지표 한 벌이지만 스윕은 곡선·제약 판정·최적점이다. 한 도구에 섞으면
 * 응답 형식이 호출마다 달라져 채팅 포매터와 UI가 계속 분기해야 한다(§2). 열 계산은
 * {@link ThermalSimulator}를 그대로 재사용하므로 공식이 두 벌로 갈라지지 않는다.
 */
@Component
public class SweepFanRpmTool implements McpToolProvider {

    /** 팬 사양을 지정하지 않았을 때 쓰는 기본 배열 — 검증 전 임시 사양임이 결과에 표시된다. */
    static final String DEFAULT_PRESET = "PI5_DUAL_40MM_PRELIMINARY";
    static final double DEFAULT_PRESET_RATED_RPM = 7750.0;
    /** {@link EdgeToolSupport#fan} 및 단일 실행 도구 스키마와 같은 레거시 기본 정격. */
    static final double LEGACY_RATED_RPM = 5000.0;
    static final double LEGACY_RATED_POWER_W = 0.5;
    /** 단일 실행 도구와 같은 기본 부하 시간 — 같은 조건을 두 도구로 물었을 때 값이 맞아야 한다. */
    static final double DEFAULT_LOAD_SEC = 900.0;

    private final ThermalSimulator simulator = new ThermalSimulator();
    private final EdgeThermalProfileStore profiles;
    private final AiLoadProfileService aiLoads;

    public SweepFanRpmTool(EdgeThermalProfileStore profiles, AiLoadProfileService aiLoads) {
        this.profiles = profiles;
        this.aiLoads = aiLoads;
    }

    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "sweep_fan_rpm"; }

    @Override
    public String description() {
        return "냉각팬 PWM/RPM을 단계적으로 바꿔가며 같은 조건으로 발열 시뮬레이션을 반복하고, "
             + "온도·처리량 제약을 만족하는 지점 중 시스템 총에너지(또는 프레임당 에너지)가 "
             + "최소인 운전점을 찾는다. 모든 지점의 온도·에너지·탈락 사유를 함께 돌려주므로 "
             + "RPM-에너지-온도 곡선을 그대로 그릴 수 있다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "board": {"type": "string", "enum": ["pi4", "pi5"], "description": "대상 보드"},
                "cooling": {"type": "string", "enum": ["passive", "active"],
                  "description": "냉각판 조건 — 팬은 방열판 위에서만 의미가 있으므로 bare는 쓸 수 없다(FR-96)", "default": "passive"},
                "ambientTempC": {"type": "number", "description": "주변 온도 ℃", "default": 25},
                "workloadMode": {"type": "string", "enum": ["target_fps", "max_throughput"],
                  "description": "target_fps=목표 FPS만 채움(기본 목적함수 총에너지), max_throughput=최대 추론(기본 목적함수 프레임당 에너지)", "default": "target_fps"},
                "targetFps": {"type": "number", "description": "목표 추론 FPS(target_fps 모드)", "default": 10},
                "maxFps": {"type": "number", "description": "스로틀링 없을 때의 최대 FPS"},
                "loadSeconds": {"type": "number", "description": "모든 지점에 동일하게 적용되는 부하 시간(초)", "default": 900},
                "aiLoadProfileId": {"type": "string", "enum": ["steady", "burst", "mixed"],
                  "description": "AI 부하 패턴. 모든 지점에 같은 패턴이 적용된다"},
                "profileId": {"type": "string", "description": "calibrate_edge_thermal_model로 저장한 실측 파라미터 id"},
                "heatsinkMassG": {"type": "number", "description": "방열판 질량(g). 넣으면 2노드 모델로 계산한다"},
                "heatsinkMaterial": {"type": "string", "enum": ["aluminum", "copper"], "default": "aluminum"},
                "fanArray": {"type": "object",
                  "description": "스윕할 팬 배열. 생략하면 Pi5 40mm 2연팬 임시 프리셋으로 돈다. commandedPwmPercent는 스윕이 지점마다 덮어쓰므로 넣어도 무시된다. measuredArrayRpm·measuredCurrentA는 한 운전점의 실측값이라 스윕에서는 거부된다",
                  "properties": {
                    "presetId": {"type": "string", "description": "PI5_DUAL_40MM_PRELIMINARY 등"},
                    "ratedRpm": {"type": "number", "default": 7750},
                    "ratedPowerW": {"type": "number", "default": 0.625},
                    "ratedCurrentA": {"type": "number", "default": 0.125},
                    "ratedValueScope": {"type": "string", "enum": ["PER_FAN", "DUAL_FAN_ARRAY_ASSUMED", "ARRAY_TOTAL_MEASURED"]},
                    "verified": {"type": "boolean", "default": false},
                    "fans": {"type": "array", "items": {"type": "object"}}
                  }},
                "sweep": {"type": "object", "description": "PWM 스윕 범위. rpmPoints와 동시에 쓸 수 없다",
                  "properties": {
                    "minPwmPercent": {"type": "number", "default": 0},
                    "maxPwmPercent": {"type": "number", "default": 100},
                    "steps": {"type": "integer", "description": "시작·종료를 포함한 지점 수", "default": 11, "minimum": 2, "maximum": 101}
                  }},
                "rpmPoints": {"type": "array", "items": {"type": "number"},
                  "description": "실행할 회전수를 직접 지정한다(예: [0,1500,2500,5000]). 정격 대비 비율로 PWM에 대응시킨다. sweep과 동시에 넣으면 거부된다"},
                "constraints": {"type": "object", "description": "적합 판정 기준 — TTT뿐 아니라 온도·처리량까지 본다",
                  "properties": {
                    "maxPeakTempC": {"type": "number", "default": 85},
                    "maxThroughputLossPercent": {"type": "number", "default": 1},
                    "minTargetFpsRatio": {"type": "number", "default": 0.99}
                  }},
                "objective": {"type": "string",
                  "enum": ["min_total_energy", "min_energy_per_frame", "min_fan_energy", "min_pwm"],
                  "description": "목적함수. 기본값은 target_fps=min_total_energy, max_throughput=min_energy_per_frame"},
                "allowTotalEnergyInMaxThroughput": {"type": "boolean", "default": false,
                  "description": "최대 처리량 모드에서 min_total_energy를 쓰려면 명시적으로 켜야 한다 — 지점마다 처리한 작업량이 달라 총에너지 비교가 공정하지 않기 때문"},
                "includeSeriesForOptimal": {"type": "boolean", "default": false,
                  "description": "최적 운전점의 온도 시계열을 함께 반환할지"}
              },
              "required": ["board"]
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        EdgeArgs a = new EdgeArgs(args);
        BoardType board = a.enumVal("board", BoardType.PI4, BoardType::parse, EdgeToolSupport.BOARD_ENUM, true);
        CoolingPreset cooling = a.enumVal("cooling", CoolingPreset.PASSIVE, CoolingPreset::parse,
                EdgeToolSupport.COOLING_ENUM, false);
        double ambient = a.dbl("ambientTempC", 25.0, -20.0, 60.0);
        WorkloadMode mode = a.enumVal("workloadMode", WorkloadMode.TARGET_FPS, WorkloadMode::parse,
                EdgeToolSupport.MODE_ENUM, false);

        FanSweepResult.Objective objective = a.enumVal("objective",
                FanSweepResult.Objective.defaultFor(mode), FanSweepResult.Objective::parse,
                "min_total_energy, min_energy_per_frame, min_fan_energy, min_pwm", false);
        // §7.3 — 최대 처리량 모드에서는 지점마다 처리한 작업량이 다르다. 총에너지로 고르면
        // "덜 일한 지점이 이긴다"는 함정에 그대로 빠지므로 명시적 허용을 요구한다.
        if (mode == WorkloadMode.MAX_THROUGHPUT
                && objective == FanSweepResult.Objective.MIN_TOTAL_ENERGY
                && !a.bool("allowTotalEnergyInMaxThroughput", false)) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, "objective",
                    "최대 처리량 모드에서는 지점마다 처리한 프레임 수가 달라 총에너지 비교가 공정하지 않다 "
                    + "— min_energy_per_frame을 쓰거나, 그래도 총에너지로 고르려면 "
                    + "allowTotalEnergyInMaxThroughput=true를 명시할 것.");
        }

        FanSweepResult.Constraints constraints = constraints(a);
        ObjectNode fanArray = fanArrayTemplate(a, args);
        List<Double> pwmPoints = pwmPoints(a, fanArray);

        // 헤더 검증이 실패하면 지점 파싱으로 넘어가지 않는다 — 같은 오류가 지점 수만큼
        // 중복돼서 "무엇을 고쳐야 하는지"가 오히려 안 보이게 된다.
        if (a.hasErrors()) return ToolResult.rejected(a.errors());

        ObjectNode base = fixedConditions(args, fanArray, cooling);

        List<FanSweepPoint> points = new ArrayList<>(pwmPoints.size());
        FanArraySpec reportedFan = null;
        ThermalRun optimalRun = null;
        double targetFpsUsed = 0.0, loadSecUsed = 0.0;
        List<ThermalRun> runs = new ArrayList<>(pwmPoints.size());

        for (double pwm : pwmPoints) {
            ObjectNode pointArgs = base.deepCopy();
            ((ObjectNode) pointArgs.get("fanArray")).put("commandedPwmPercent", pwm);

            EdgeArgs pa = new EdgeArgs(pointArgs);
            ThermalParams p = EdgeToolSupport.thermalParams(pa, board, cooling, ambient, profiles);
            AiLoadProfile aiLoad = EdgeToolSupport.aiLoadProfile(pa, aiLoads);
            ThermalSimulator.Spec spec = EdgeToolSupport.spec(pa, board, p, DEFAULT_LOAD_SEC, mode, aiLoad);
            // 지점끼리 조건이 같으므로 첫 실패가 곧 전체 실패다 — 그대로 돌려준다.
            if (pa.hasErrors()) return ToolResult.rejected(pa.errors());

            ThermalRun run = simulator.run(board, spec);
            runs.add(run);
            reportedFan = spec.fanArray();
            targetFpsUsed = spec.targetFps();
            loadSecUsed = spec.loadSec();
            points.add(point(run, spec, mode, constraints));
        }

        FanSweepPoint optimal = FanSweepResult.select(points, objective);
        if (optimal != null) {
            optimalRun = runs.get(points.indexOf(optimal));
        }
        FanSweepResult result = new FanSweepResult(objective,
                optimal == null ? FanSweepResult.STATUS_NONE : FanSweepResult.STATUS_FOUND,
                optimal, optimalReason(optimal, objective), points, constraints,
                FanSweepResult.TIE_TOLERANCE);

        return ToolResult.ok(output(result, board, cooling, ambient, mode, targetFpsUsed, loadSecUsed,
                reportedFan, optimalRun, a.bool("includeSeriesForOptimal", false),
                !hasFanArrayInput(args)));
    }

    // ── 입력 해석 ────────────────────────────────────────────────────────────

    private FanSweepResult.Constraints constraints(EdgeArgs a) {
        EdgeArgs c = a.child("constraints");
        return new FanSweepResult.Constraints(
                c.dbl("maxPeakTempC", FanSweepResult.Constraints.DEFAULT_MAX_PEAK_TEMP_C, 40.0, 110.0),
                c.dbl("maxThroughputLossPercent",
                        FanSweepResult.Constraints.DEFAULT_MAX_THROUGHPUT_LOSS_PERCENT, 0.0, 100.0),
                c.dbl("minTargetFpsRatio",
                        FanSweepResult.Constraints.DEFAULT_MIN_TARGET_FPS_RATIO, 0.0, 1.0));
    }

    private static boolean hasFanArrayInput(JsonNode args) {
        return args != null && (args.hasNonNull("fanArray") || args.hasNonNull("fanRpm")
                || args.hasNonNull("fanRatedRpm") || args.hasNonNull("fanRatedPowerW"));
    }

    /**
     * 스윕할 팬 배열의 <b>틀</b>을 만든다. PWM만 지점마다 바뀌고 나머지는 전 지점 공통이다.
     *
     * <p>실측값({@code measuredArrayRpm}·{@code measuredCurrentA})을 거부하는 이유가 중요하다.
     * 둘 다 <b>한 운전점에서 잰 값</b>인데, {@link FanArraySpec}은 이 값이 있으면 PWM 추정보다
     * 우선한다 — 그대로 스윕하면 모든 지점이 같은 회전수·같은 전력으로 계산되어 곡선이
     * 평평해지고, 그 위에서 고른 "최적점"은 동률 규칙이 뽑은 첫 지점일 뿐이다. 조용히
     * 무시하면 실측을 넣었다고 믿는 사용자가 그 결과를 신뢰하게 되므로 fail-closed로 막고,
     * 대신 무엇이 필요한지(PWM-RPM 보정곡선)를 알려준다.
     */
    private ObjectNode fanArrayTemplate(EdgeArgs a, JsonNode args) {
        ObjectNode fa;
        if (args != null && args.hasNonNull("fanArray") && args.get("fanArray").isObject()) {
            fa = (ObjectNode) args.get("fanArray").deepCopy();
            for (String measured : List.of("measuredArrayRpm", "measuredCurrentA")) {
                if (fa.hasNonNull(measured)) {
                    a.reject(ErrorCode.INVALID_ARGUMENTS, "fanArray." + measured,
                            "한 운전점에서 잰 실측값이라 회전수를 바꾸는 스윕에는 쓸 수 없다 — "
                            + "이 값이 있으면 모든 지점이 같은 회전수·전력으로 계산되어 곡선이 평평해진다. "
                            + "PWM별 실측을 반영하려면 PWM-RPM 보정곡선이 필요하다(미구현).");
                }
            }
        } else if (args != null && (args.hasNonNull("fanRpm") || args.hasNonNull("fanRatedRpm")
                || args.hasNonNull("fanRatedPowerW"))) {
            // 레거시 단일 팬 입력 — 팬 1개짜리 배열로 옮긴다. fanRpm(한 지점의 회전수)은
            // 스윕이 지점마다 정하므로 버린다.
            fa = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            ArrayNode fans = fa.putArray("fans");
            fans.addObject();
            fa.put("ratedRpm", a.dbl("fanRatedRpm", LEGACY_RATED_RPM, 100.0, 30000.0));
            fa.put("ratedPowerW", a.dbl("fanRatedPowerW", LEGACY_RATED_POWER_W, 0.0, 50.0));
            fa.put("ratedCurrentA", 0.0);      // 전력만 알고 전류는 모르는 입력이라 검증을 건너뛴다
            fa.put("ratedValueScope", "PER_FAN");
        } else {
            fa = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            fa.put("presetId", DEFAULT_PRESET);
        }
        return fa;
    }

    /** 이 배열의 정격 회전수 — {@code rpmPoints}를 PWM으로 옮길 때의 기준이다. */
    private static double ratedRpmOf(ObjectNode fanArray) {
        if (DEFAULT_PRESET.equalsIgnoreCase(fanArray.path("presetId").asText(""))) {
            return DEFAULT_PRESET_RATED_RPM;
        }
        return fanArray.path("ratedRpm").asDouble(DEFAULT_PRESET_RATED_RPM);
    }

    /**
     * 실행할 PWM 지점을 만든다(§3, §7.1). 시작·종료를 <b>포함</b>한다 — 0%(팬 정지)는
     * 대조군이고 100%(정격)는 상한이라, 둘 중 하나가 빠지면 곡선의 끝을 읽을 수 없다.
     */
    private List<Double> pwmPoints(EdgeArgs a, ObjectNode fanArray) {
        boolean hasRpmPoints = a.raw("rpmPoints").isArray();
        if (hasRpmPoints && a.has("sweep")) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, "rpmPoints",
                    "sweep(PWM 범위)과 rpmPoints(회전수 직접 지정)를 동시에 넣을 수 없다 — "
                    + "어느 쪽이 실행 지점인지 모호하므로 하나만 쓸 것(fail-closed).");
            return List.of();
        }

        if (hasRpmPoints) {
            double rated = ratedRpmOf(fanArray);
            List<Double> pwms = new ArrayList<>();
            for (JsonNode n : a.raw("rpmPoints")) {
                if (!n.isNumber()) {
                    a.reject(ErrorCode.INVALID_ARGUMENTS, "rpmPoints", "회전수는 숫자여야 한다(받은 값: " + n.asText() + ")");
                    return List.of();
                }
                double rpm = n.asDouble();
                if (rpm < 0 || rpm > rated) {
                    a.reject(ErrorCode.OUT_OF_RANGE, "rpmPoints", String.format(
                            "회전수 %.0f은 0~정격(%.0f) 범위를 벗어났다 — 정격을 넘는 회전수는 낼 수 없다.", rpm, rated));
                    return List.of();
                }
                pwms.add(ThermalSimulator.round(rpm / rated * 100.0, 4));
            }
            return validated(a, pwms, "rpmPoints");
        }

        EdgeArgs s = a.child("sweep");
        double min = s.dbl("minPwmPercent", 0.0, 0.0, 100.0);
        double max = s.dbl("maxPwmPercent", 100.0, 0.0, 100.0);
        int steps = s.intVal("steps", 11, 2, 101);
        if (min > max) {
            s.reject(ErrorCode.INVALID_ARGUMENTS, "minPwmPercent", String.format(
                    "시작 PWM(%.0f%%)이 종료 PWM(%.0f%%)보다 크다.", min, max));
            return List.of();
        }
        List<Double> pwms = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            pwms.add(ThermalSimulator.round(min + (max - min) * i / (steps - 1.0), 4));
        }
        return validated(a, pwms, "sweep");
    }

    /** 같은 PWM을 두 번 도는 것은 실행 시간만 쓰고 곡선에 아무것도 더하지 않는다(§7.3). */
    private List<Double> validated(EdgeArgs a, List<Double> pwms, String field) {
        if (pwms.size() < 2) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, field, "스윕에는 최소 2개 지점이 필요하다.");
            return List.of();
        }
        if (pwms.stream().distinct().count() != pwms.size()) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, field,
                    "생성된 실행 지점에 중복이 있다 — 시작·종료가 같거나 단계가 너무 촘촘하다.");
            return List.of();
        }
        return pwms;
    }

    /**
     * 모든 지점이 공유하는 고정 조건을 박아 넣는다(§4).
     *
     * <p>회복 정책을 끄는 것이 핵심이다. 스로틀링을 감지한 순간 회복을 적용하면 RPM에 따라
     * 부하 구간의 길이가 달라져 <b>지점마다 다른 시간 동안 다른 일</b>을 하게 된다 — 그
     * 상태에서 총에너지를 비교하면 "적게 일한 지점이 적게 썼다"는 당연한 결과가 최적점으로
     * 뽑힌다.
     */
    private ObjectNode fixedConditions(JsonNode args, ObjectNode fanArray, CoolingPreset cooling) {
        ObjectNode base = args != null && args.isObject()
                ? (ObjectNode) args.deepCopy()
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        // 냉각 조건은 생략됐어도 반드시 적어 넣는다 — 팬을 지정한 채 cooling이 비어 있으면
        // FR-96(방열판 없이 팬만)에 걸려 기본값으로 돌린 스윕이 통째로 거부된다.
        base.put("cooling", cooling.name().toLowerCase());
        base.remove(List.of("fanRpm", "fanRatedRpm", "fanRatedPowerW", "rpmPoints", "sweep",
                "constraints", "objective", "allowTotalEnergyInMaxThroughput", "includeSeriesForOptimal"));
        base.set("fanArray", fanArray);
        base.put("recoveryPolicy", "none");
        base.put("recoverySeconds", 0);
        base.put("applyRecoveryOnThrottle", false);
        if (!base.hasNonNull("loadSeconds")) base.put("loadSeconds", DEFAULT_LOAD_SEC);
        return base;
    }

    // ── 지점 평가 ────────────────────────────────────────────────────────────

    /** 실행 결과 하나를 제약과 대조해 적합 여부까지 판정한다(§5). */
    private FanSweepPoint point(ThermalRun run, ThermalSimulator.Spec spec, WorkloadMode mode,
                                FanSweepResult.Constraints c) {
        List<String> reasons = new ArrayList<>();
        if (run.tttSec() != null || run.throttledFraction() > 0) {
            reasons.add(FanSweepPoint.Rejection.HARD_THROTTLED.name());
        }
        if (run.peakTempC() > c.maxPeakTempC()) reasons.add(FanSweepPoint.Rejection.TOO_HOT.name());
        if (run.throughputLossPercent() > c.maxThroughputLossPercent()) {
            reasons.add(FanSweepPoint.Rejection.THROUGHPUT_LOSS.name());
        }
        if (mode == WorkloadMode.TARGET_FPS) {
            // 부하 패턴이 있으면 요구량 자체가 시간에 따라 오르내린다 — 평균 배율을 곱하지
            // 않으면 패턴을 켠 순간 모든 지점이 "목표 FPS 미달"로 탈락한다.
            double meanLevel = spec.aiLoad() == null ? 1.0 : spec.aiLoad().meanLevel();
            double demanded = Math.min(spec.targetFps(), run.params().maxFps()) * meanLevel;
            if (run.meanFpsLoad() < demanded * c.minTargetFpsRatio()) {
                reasons.add(FanSweepPoint.Rejection.TARGET_FPS_MISSED.name());
            }
        }

        FanArraySpec fa = spec.fanArray();
        Double energyPerFrame = run.processedFrames() > 1e-9
                ? ThermalSimulator.round(run.totalEnergyJ() / run.processedFrames(), 6) : null;
        return new FanSweepPoint(
                ThermalSimulator.round(fa.commandedPwmPercent(), 2),
                ThermalSimulator.round(fa.effectiveRpm(), 0),
                fa.rpmSource().name(),
                ThermalSimulator.round(run.params().rJaKPerW(), 3),
                ThermalSimulator.round(fa.arrayPowerW(), 4),
                run.fanEnergyJ(), run.energyJ(), run.totalEnergyJ(),
                run.peakTempC(), run.softLimitEntrySec(), run.tttSec(), run.medianTedSec(),
                run.throttledFraction(), run.meanFpsLoad(), run.throughputLossPercent(),
                run.processedFrames(), energyPerFrame,
                reasons.isEmpty(), reasons);
    }

    private String optimalReason(FanSweepPoint optimal, FanSweepResult.Objective objective) {
        if (optimal == null) return null;
        return String.format("온도·처리량 제약을 만족하는 지점 중 %s(%s)",
                objective.labelKo(), objective.wire());
    }

    // ── 응답 ────────────────────────────────────────────────────────────────

    private Map<String, Object> output(FanSweepResult r, BoardType board, CoolingPreset cooling,
                                       double ambient, WorkloadMode mode, double targetFps,
                                       double loadSec, FanArraySpec fan, ThermalRun optimalRun,
                                       boolean includeSeries, boolean defaultedFanSpec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("board", board.label());
        out.put("soc", board.soc());
        out.put("cooling", cooling.name().toLowerCase());
        out.put("ambientTempC", ambient);
        out.put("workloadMode", mode.name().toLowerCase());
        out.put("targetFps", targetFps);
        out.put("loadSeconds", loadSec);
        out.put("objective", r.objective().wire());
        out.put("status", r.status());
        out.put("optimal", r.optimal() == null ? null : optimalMap(r));
        // record를 그대로 담지 않고 Map으로 편다 — 채팅 포매터는 JSON 직렬화를 거치지 않고
        // 이 결과를 그대로 읽으므로(ChatController), record로 두면 MCP와 채팅에서 서로 다른
        // 타입을 다뤄야 한다.
        out.put("points", r.points().stream().map(SweepFanRpmTool::pointMap).toList());
        Map<String, Object> cons = new LinkedHashMap<>();
        cons.put("maxPeakTempC", r.constraints().maxPeakTempC());
        cons.put("maxThroughputLossPercent", r.constraints().maxThroughputLossPercent());
        cons.put("minTargetFpsRatio", r.constraints().minTargetFpsRatio());
        out.put("constraints", cons);
        out.put("tieToleranceRelative", r.tieToleranceRelative());
        out.put("fanSpec", fanSpecMap(fan));
        out.put("notes", notes(r, fan, defaultedFanSpec));
        if (r.optimal() == null) {
            out.put("recommendation", "팬 사양·PWM 범위를 넓히거나 목표 FPS·주변 온도 조건을 조정할 것 "
                    + "— 지금 조건에서는 어떤 회전수로도 제약을 만족하지 못한다.");
        }
        if (includeSeries && optimalRun != null) out.put("optimalSeries", optimalRun.series());
        return out;
    }

    /** 지점 하나를 응답용 Map으로. 필드 이름이 곧 곡선을 그리는 클라이언트의 계약이다. */
    static Map<String, Object> pointMap(FanSweepPoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commandedPwmPercent", p.commandedPwmPercent());
        m.put("effectiveRpm", p.effectiveRpm());
        m.put("rpmSource", p.rpmSource());
        m.put("rJaKPerW", p.rJaKPerW());
        m.put("fanPowerW", p.fanPowerW());
        m.put("peakTempC", p.peakTempC());
        m.put("softLimitEntrySec", p.softLimitEntrySec());
        m.put("tttSec", p.tttSec());
        m.put("medianTedSec", p.medianTedSec());
        m.put("throttledFraction", p.throttledFraction());
        m.put("meanFpsDuringLoad", p.meanFpsLoad());
        m.put("throughputLossPercent", p.throughputLossPercent());
        m.put("processedFrames", p.processedFrames());
        m.put("energyPerFrameJ", p.energyPerFrameJ());
        m.put("socEnergyJ", p.socEnergyJ());
        m.put("fanEnergyJ", p.fanEnergyJ());
        m.put("totalEnergyJ", p.totalEnergyJ());
        m.put("feasible", p.feasible());
        m.put("rejectionReasons", p.rejectionReasons());
        return m;
    }

    private Map<String, Object> optimalMap(FanSweepResult r) {
        FanSweepPoint o = r.optimal();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commandedPwmPercent", o.commandedPwmPercent());
        m.put("effectiveRpm", o.effectiveRpm());
        m.put("rpmSource", o.rpmSource());
        m.put("rJaKPerW", o.rJaKPerW());
        m.put("peakTempC", o.peakTempC());
        m.put("tttSec", o.tttSec());
        m.put("throughputLossPercent", o.throughputLossPercent());
        m.put("meanFpsDuringLoad", o.meanFpsLoad());
        m.put("processedFrames", o.processedFrames());
        m.put("energyPerFrameJ", o.energyPerFrameJ());
        m.put("fanPowerW", o.fanPowerW());
        m.put("socEnergyJ", o.socEnergyJ());
        m.put("fanEnergyJ", o.fanEnergyJ());
        m.put("totalEnergyJ", o.totalEnergyJ());
        m.put("reason", r.optimalReason());
        return m;
    }

    /** §11 — 이 결과가 실측인지 추정인지는 숫자와 <b>항상 함께</b> 다녀야 한다. */
    private Map<String, Object> fanSpecMap(FanArraySpec fan) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (fan == null) return m;
        m.put("presetId", fan.presetId());
        m.put("fanCount", fan.fanCount());
        m.put("ratedRpm", fan.ratedRpm());
        m.put("ratedPowerW", fan.ratedPowerW());
        m.put("ratedValueScope", fan.ratedValueScope().name());
        m.put("measurementScope", fan.measurementScope().name());
        m.put("source", fan.sourceStatus().name());
        m.put("verified", fan.verified());
        return m;
    }

    private List<String> notes(FanSweepResult r, FanArraySpec fan, boolean defaultedFanSpec) {
        List<String> notes = new ArrayList<>();
        if (defaultedFanSpec) {
            notes.add("팬 사양을 지정하지 않아 " + DEFAULT_PRESET + "(40mm 2연팬, 검증 전 임시 사양)으로 스윕했다.");
        }
        notes.add("모든 지점을 같은 부하·시간·초기조건으로 돌렸고 회복 정책은 껐다 — "
                + "지점마다 부하 구간 길이가 달라지면 총에너지를 직접 비교할 수 없기 때문이다.");
        notes.add("팬 전력은 회전수의 3승, 냉각은 풍속의 0.8승에 비례한다 — "
                + "그래서 최적점은 대개 정격보다 한참 아래에 있다.");
        if (r.optimal() != null && r.optimal().commandedPwmPercent() >= 99.9) {
            notes.add("최적점이 스윕 상단(정격)에 걸렸다 — 더 강한 냉각이 필요한 조건일 수 있으므로 "
                    + "제약(온도·처리량)이 현실적인지 함께 확인할 것.");
        }
        if (fan != null) {
            if (!fan.verified()) {
                notes.add("팬 사양이 아직 검증 전이라 이 최적점은 확정값이 아니라 잠정 결과다 — "
                        + "TACH 실측 RPM과 실측 전류로 사양을 확정한 뒤 다시 돌릴 것(§11).");
            }
            notes.addAll(fan.warnings());
        }
        notes.add("팬 위치·겹침 면적의 효율은 이 스윕으로 판정할 수 없다 — "
                + "배치는 실측 보정 전까지 냉각계수를 만들지 않는 메타데이터다(§12).");
        return notes;
    }
}
