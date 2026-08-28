package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구 {@code simulate_ptm_control} — <b>예측 냉각(PTM)이 실제로 이득인지</b>를
 * 같은 조건에서 제어 방식만 바꿔 돌려 비교한다.
 *
 * <h3>왜 단일 실행이 아니라 비교인가</h3>
 * "예측 제어로 돌렸더니 스로틀링 없이 평균 듀티 42%였다"는 문장 하나로는 아무것도 판단할 수
 * 없다. 반응형이 40%였다면 예측은 손해고, 항상 최대(100%)와 비교하면 이득이다. <b>기준선이
 * 없는 제어 결과는 해석이 불가능</b>하므로 이 도구는 항상 여러 방식을 함께 돌린다
 * (보드·재질·팬 유무 비교를 두 번 실행으로 처리하는 것과 같은 이유).
 *
 * <h3>회복 정책을 끄는 이유</h3>
 * 회복 정책 R3(능동 냉각)와 팬 제어기는 <b>같은 액추에이터</b>를 두고 다툰다. 둘을 동시에
 * 켜면 온도가 내려간 것이 제어기 덕인지 정책 덕인지 분리할 수 없다. 그래서 이 도구는
 * 회복 구간 없이(policy=none, recoverySeconds=0) 부하 구간만 비교한다.
 *
 * <h3>승자 판정</h3>
 * <b>스로틀링이 없는 방식들 중에서만</b> 총에너지 최소를 고른다. 스로틀링을 맞으면서 에너지를
 * 아낀 방식이 이기면, 그건 "냉각을 포기했다"는 뜻이지 효율이 좋다는 뜻이 아니다
 * ({@code sweep_fan_rpm}의 NO_FEASIBLE_POINT 원칙과 같다).
 */
@Component
public class SimulatePtmControlTool implements McpToolProvider {

    /** 팬 사양을 지정하지 않았을 때 가정하는 정격 회전수·전력(단일 팬). */
    private static final double DEFAULT_RATED_RPM = 5000.0;
    private static final double DEFAULT_RATED_POWER_W = 0.5;

    private final ThermalSimulator simulator = new ThermalSimulator();
    private final EdgeThermalProfileStore profiles;
    private final AiLoadProfileService aiLoads;

    public SimulatePtmControlTool(EdgeThermalProfileStore profiles, AiLoadProfileService aiLoads) {
        this.profiles = profiles;
        this.aiLoads = aiLoads;
    }

    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "simulate_ptm_control"; }

    @Override
    public String description() {
        return "예측 냉각(PTM) 제어기의 이득을 검증한다 — 같은 보드·부하 조건에서 팬 제어 방식만 "
             + "바꿔(항상 최대 / 반응형 온도 커브 / 예측형 PTM) 돌리고, 스로틀링 없이 총에너지가 "
             + "가장 적은 방식을 고른다. 부하가 시간에 따라 오르내릴 때(aiLoadProfileId=burst·mixed) "
             + "예측이 반응형보다 앞서는지가 이 도구가 답하는 질문이다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "board": {"type": "string", "enum": ["pi4", "pi5"], "description": "대상 보드"},
                "cooling": {"type": "string", "enum": ["passive", "active"],
                  "description": "냉각 조건. 팬은 방열판 위에서만 의미가 있으므로 bare는 받지 않는다(FR-96)", "default": "passive"},
                "ambientTempC": {"type": "number", "default": 25},
                "workloadMode": {"type": "string", "enum": ["target_fps", "max_throughput"], "default": "target_fps"},
                "targetFps": {"type": "number", "default": 10},
                "maxFps": {"type": "number"},
                "loadSeconds": {"type": "number", "description": "비교 실행 시간(초). 부하 패턴 주기가 여러 번 들어갈 만큼 길어야 한다", "default": 1800},
                "aiLoadProfileId": {"type": "string", "enum": ["steady", "burst", "mixed"],
                  "description": "시변 부하 패턴. PTM의 이득은 부하가 출렁일 때 나오므로 burst·mixed를 권한다. steady로 돌리면 예측과 반응형이 거의 같아진다"},
                "modes": {"type": "array", "items": {"type": "string", "enum": ["always_max", "reactive", "predictive", "fixed"]},
                  "description": "비교할 제어 방식. 기본값은 always_max·reactive·predictive 세 가지"},
                "fixedPwmPercent": {"type": "number", "description": "fixed 모드로 비교할 때의 고정 듀티(%). sweep_fan_rpm이 찾은 최적 운전점을 넣어 예측형과 견줄 때 쓴다", "default": 50},
                "predictionHorizonSeconds": {"type": "number", "description": "예측 지평(초) — 이만큼 앞의 부하를 내다본다. 시정수보다 짧으면 예측이 반응형과 비슷해진다", "default": 60},
                "controlIntervalSeconds": {"type": "number", "description": "제어 주기(초) — 이 간격으로만 회전수를 다시 정한다", "default": 5},
                "targetTempC": {"type": "number", "description": "제어 목표 온도 ℃. 기본값은 소프트 제한(80℃) − 3℃ = 77℃로, 성능 저하가 시작되기 전에 막는다"},
                "heatsinkMassG": {"type": "number", "description": "방열판 질량(g). 넣으면 2노드 모델로 계산한다"},
                "heatsinkMaterial": {"type": "string", "enum": ["aluminum", "copper"], "default": "aluminum"},
                "rInternalKPerW": {"type": "number"},
                "profileId": {"type": "string", "description": "실측 캘리브레이션 프로파일 id"},
                "fanArray": {"type": "object", "description": "팬 배열 사양(sweep_fan_rpm과 같은 형식). 생략하면 정격 5000rpm·0.5W 단일 팬을 가정하고 그 사실을 결과에 남긴다"},
                "fanRatedRpm": {"type": "number", "default": 5000},
                "fanRatedPowerW": {"type": "number", "default": 0.5},
                "sampleIntervalSeconds": {"type": "number", "default": 30},
                "includeSeries": {"type": "boolean", "description": "제어 방식별 시계열(팬 듀티 포함) 포함 여부", "default": false}
              },
              "required": ["board"]
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        EdgeArgs a = new EdgeArgs(args);
        // MCP 클라이언트가 일반 발열 도구의 recoveryPolicy를 그대로 섞어 보내는 경우를
        // 조용히 무시하면 R3와 PTM을 병용했다고 오해한다. 둘은 같은 팬 액추에이터를
        // 사용하므로 none 이외의 회복정책은 계산 전에 명시적으로 거부한다(PT-21).
        if (args != null && args.hasNonNull("recoveryPolicy")
                && !"none".equalsIgnoreCase(args.path("recoveryPolicy").asText())) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, "recoveryPolicy",
                    "PTM 비교에서는 회복정책을 함께 사용할 수 없다 — R3와 제어기가 같은 팬을 제어한다. "
                    + "recoveryPolicy=none으로 실행할 것.");
        }
        BoardType board = a.enumVal("board", BoardType.PI5, BoardType::parse, EdgeToolSupport.BOARD_ENUM, true);
        CoolingPreset cooling = a.enumVal("cooling", CoolingPreset.PASSIVE, CoolingPreset::parse,
                EdgeToolSupport.COOLING_ENUM, false);
        // 팬이 붙을 자리가 없으면 제어할 대상이 없다 — 무냉각은 fail-closed로 거부한다(FR-96).
        if (cooling == CoolingPreset.BARE) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, "cooling",
                    "FR-96: 무냉각(bare)에는 팬을 달 수 없으므로 제어할 대상이 없다 — "
                    + "cooling=passive/active로 두거나 heatsinkMassG를 지정할 것.");
        }
        double ambient = a.dbl("ambientTempC", 25.0, -20.0, 60.0);

        ThermalParams p = EdgeToolSupport.thermalParams(a, board, cooling, ambient, profiles);
        AiLoadProfile aiLoad = EdgeToolSupport.aiLoadProfile(a, aiLoads);

        List<PtmController.Mode> modes = modes(a);
        double horizon = a.dbl("predictionHorizonSeconds", PtmController.DEFAULT_HORIZON_SEC, 5.0, 3600.0);
        double interval = a.dbl("controlIntervalSeconds", PtmController.DEFAULT_CONTROL_INTERVAL_SEC, 0.5, 600.0);
        double targetTemp = a.dbl("targetTempC", PtmController.defaultTargetTempC(p), 30.0, p.hardLimitC());
        double fixedDuty = a.dbl("fixedPwmPercent", 50.0, 0.0, 100.0) / 100.0;
        boolean includeSeries = a.bool("includeSeries", false);

        // 팬 사양 — 지정하지 않았으면 기본 단일 팬을 가정하고 그 사실을 결과에 남긴다.
        // 조용히 가정하면 "이 결과가 어느 팬 기준이었나"를 나중에 복원할 수 없다(FR-104와 같은 원칙).
        FanArraySpec fanArray = EdgeToolSupport.fanArray(a);
        boolean fanAssumed = fanArray == null;
        if (fanAssumed) {
            double ratedRpm = a.dbl("fanRatedRpm", DEFAULT_RATED_RPM, 100.0, 30000.0);
            double ratedPowerW = a.dbl("fanRatedPowerW", DEFAULT_RATED_POWER_W, 0.0, 50.0);
            fanArray = FanArraySpec.legacy(new FanSpec(ratedRpm, ratedRpm, ratedPowerW));
        }

        // 기본 실행 명세 — 회복 구간은 쓰지 않는다(제어기와 R3가 같은 액추에이터를 다투므로).
        ThermalSimulator.Spec base = EdgeToolSupport.spec(a, board, p, 1800.0, WorkloadMode.TARGET_FPS, aiLoad);
        if (a.hasErrors()) return ToolResult.rejected(a.errors());

        // 팬 정지/정격 두 끝점 — 제어기가 듀티를 열저항으로 바꿀 때 지나야 하는 두 점이다.
        // 실측 프로파일을 쓴 경우에도 성립하도록 프리셋의 <b>비율</b>을 이번 실행의 기준
        // 열저항에 적용한다(절대값을 프리셋으로 덮어쓰면 실측이 조용히 버려진다).
        double rJaPassive = base.params().rJaKPerW();
        double activeRatio = board.rJaKPerW(CoolingPreset.ACTIVE) / board.rJaKPerW(CoolingPreset.PASSIVE);
        double rJaActive = rJaPassive * activeRatio;

        List<Map<String, Object>> runs = new ArrayList<>();
        for (PtmController.Mode mode : modes) {
            PtmController control = new PtmController(mode, base.params(), aiLoad, base.mode(),
                    base.targetFps(), fanArray, rJaPassive, rJaActive, board.rJcKPerW(),
                    horizon, interval, targetTemp, fixedDuty);
            ThermalSimulator.Spec spec = new ThermalSimulator.Spec(
                    base.params(), base.mode(), base.targetFps(), base.loadSec(),
                    RecoveryPolicy.NONE, 0.0, base.params().rJaKPerW(),
                    base.dtSec(), base.sampleSec(), false,
                    aiLoad, base.heatsink(), fanArray, control);
            ThermalRun run = simulator.run(board, spec);
            runs.add(runOutput(mode, run, includeSeries));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("board", board.label());
        out.put("soc", board.soc());
        out.put("cooling", cooling.name().toLowerCase());
        out.put("ambientTempC", ambient);
        out.put("workloadMode", base.mode().name().toLowerCase());
        out.put("targetFps", base.targetFps());
        out.put("loadSeconds", base.loadSec());
        if (aiLoad != null) {
            Map<String, Object> lp = new LinkedHashMap<>();
            lp.put("id", aiLoad.getId());
            lp.put("label", aiLoad.getLabel());
            lp.put("cycleSeconds", aiLoad.cycleSeconds());
            lp.put("meanLevel", aiLoad.meanLevel());
            lp.put("peakLevel", aiLoad.peakLevel());
            out.put("aiLoadProfile", lp);
        }
        Map<String, Object> ctl = new LinkedHashMap<>();
        ctl.put("predictionHorizonSeconds", horizon);
        ctl.put("controlIntervalSeconds", interval);
        ctl.put("targetTempC", targetTemp);
        ctl.put("recoveryPolicy", "none (제어기와 R3가 같은 액추에이터를 다투지 않도록 비교 실행에서는 끈다)");
        out.put("control", ctl);
        out.put("runs", runs);
        out.putAll(verdict(runs));

        List<String> notes = new ArrayList<>();
        if (fanAssumed) {
            notes.add(String.format(
                    "팬 사양을 지정하지 않아 정격 %.0f RPM · %.2fW 단일 팬을 가정했다 — 절대 에너지는 이 가정에 비례하므로, "
                    + "방식 간 '차이'는 유효하지만 절대값을 보고할 때는 실측 사양(fanArray)으로 다시 돌릴 것.",
                    fanArray.ratedRpm(), fanArray.ratedPowerW()));
        }
        if (aiLoad == null || aiLoad.isConstant()) {
            notes.add("부하가 일정한 조건이라 예측이 반응형보다 앞설 여지가 거의 없다 — "
                    + "PTM은 '다가올 변화'를 쓰는 제어이므로 aiLoadProfileId=burst·mixed로 비교해야 이득이 드러난다.");
        }
        out.put("notes", notes);
        return ToolResult.ok(out);
    }

    /** 비교할 제어 방식. 지정하지 않으면 기준선 둘(항상 최대·반응형) + PTM. */
    private List<PtmController.Mode> modes(EdgeArgs a) {
        JsonNode arr = a.raw("modes");
        if (!arr.isArray() || arr.isEmpty()) {
            return List.of(PtmController.Mode.ALWAYS_MAX, PtmController.Mode.REACTIVE,
                    PtmController.Mode.PREDICTIVE);
        }
        List<PtmController.Mode> modes = new ArrayList<>();
        for (JsonNode n : arr) {
            PtmController.Mode m = PtmController.Mode.parse(n.asText());
            if (m == null) {
                a.reject(ErrorCode.INVALID_ENUM, "modes",
                        "허용되지 않은 값 '" + n.asText() + "'. 허용 값: always_max, reactive, predictive, fixed");
            } else if (!modes.contains(m)) {
                modes.add(m);
            }
        }
        return modes;
    }

    private Map<String, Object> runOutput(PtmController.Mode mode, ThermalRun run, boolean includeSeries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.name().toLowerCase());
        m.put("modeLabel", mode.labelKo());
        m.put("meanFanDutyPercent", run.controlReport().meanDutyPercent());
        m.put("peakFanDutyPercent", run.controlReport().peakDutyPercent());
        m.put("fanSpeedChanges", run.controlReport().changeCount());
        m.put("throttled", run.tttSec() != null);
        m.put("tttSec", run.tttSec());
        m.put("peakTempC", run.peakTempC());
        m.put("tempAmplitudeC", run.tempAmplitudeC());
        m.put("throughputLossPercent", run.throughputLossPercent());
        m.put("socEnergyJ", run.energyJ());
        m.put("fanEnergyJ", run.fanEnergyJ());
        m.put("totalEnergyJ", run.totalEnergyJ());
        if (includeSeries) m.put("series", run.series());
        return m;
    }

    /**
     * 승자와 절감률. 스로틀링이 없는 방식들 중에서만 고른다 — 냉각을 포기해서 아낀 에너지는
     * 절감이 아니다.
     */
    private Map<String, Object> verdict(List<Map<String, Object>> runs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> feasible = runs.stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("throttled")))
                .toList();
        if (feasible.isEmpty()) {
            out.put("status", "NO_FEASIBLE_MODE");
            out.put("best", null);
            out.put("recommendation", "모든 제어 방식에서 스로틀링이 발생했다 — 팬 정격이 부족하거나 "
                    + "주변 온도·부하가 이 냉각으로 감당할 수 없는 조건이다. 방열판을 키우거나 팬 사양을 올려야 한다.");
            return out;
        }
        // 에너지 동률 처리 — 팬 스윕(FanSweepResult.select)과 같은 규칙을 쓴다. 예전에는
        // min(에너지) 하나뿐이라 동률이면 리스트에서 먼저 나온 방식(항상 최대)이 그냥
        // 이겼다. 제어 방식은 서너 개뿐이라 수 J 차이로 순위가 갈리는 일이 잦은데,
        // 그때 "왜 이게 이겼는가"에 답할 수 없는 승자가 나온다.
        double bestEnergy = feasible.stream().mapToDouble(SimulatePtmControlTool::energy)
                .min().orElseThrow();
        // 동률 판정은 상대 오차로 한다 — 총에너지는 수만 J라 절대 오차로는 기준을 못 잡는다.
        double tolerance = Math.abs(bestEnergy) * FanSweepResult.TIE_TOLERANCE;
        List<Map<String, Object>> tied = feasible.stream()
                .filter(r -> energy(r) <= bestEnergy + tolerance)
                .toList();
        // 에너지가 같다면 (1) 더 시원하고 (2) 팬을 덜 돌리고 (3) 회전수를 덜 바꾸는 쪽이 낫다.
        // 온도는 여유(수명·안정성), 듀티와 변경 횟수는 기계적 마모·소음이다.
        Map<String, Object> best = tied.stream()
                .min(Comparator.<Map<String, Object>>comparingDouble(r -> num(r, "peakTempC"))
                        .thenComparingDouble(r -> num(r, "meanFanDutyPercent"))
                        .thenComparingDouble(r -> num(r, "fanSpeedChanges")))
                .orElseThrow();
        out.put("status", "OK");
        out.put("best", best.get("mode"));
        out.put("bestLabel", best.get("modeLabel"));
        // 동률이었다는 사실 자체가 결과다 — "PTM이 이겼다"와 "PTM이 반응형과 사실상
        // 같은데 더 시원해서 이겼다"는 다른 결론이다(D-25 없는 우열을 만들지 않는다).
        if (tied.size() > 1) {
            out.put("energyTiedModes", tied.stream().map(r -> r.get("mode")).toList());
            out.put("tieBreak", "에너지가 " + tied.size() + "개 방식에서 사실상 같아("
                    + "상대 오차 " + FanSweepResult.TIE_TOLERANCE + " 이내) 최고 온도 → 평균 팬 듀티 → "
                    + "회전수 변경 횟수 순으로 골랐다. 이 조건에서는 제어 방식 간 에너지 차이가 "
                    + "의미 있는 결론이 되지 못한다.");
        }

        // 절감률은 "항상 최대" 대비로 적는다 — 그것이 제어를 하지 않았을 때의 비용이다.
        Map<String, Object> alwaysMax = runs.stream()
                .filter(r -> "always_max".equals(r.get("mode"))).findFirst().orElse(null);
        if (alwaysMax != null && energy(alwaysMax) > 0) {
            double saved = (energy(alwaysMax) - energy(best)) / energy(alwaysMax) * 100.0;
            out.put("energySavedVsAlwaysMaxPercent", Math.round(saved * 10.0) / 10.0);
        }
        return out;
    }

    private static double energy(Map<String, Object> m) {
        return num(m, "totalEnergyJ");
    }

    /** 결과 맵의 수치 필드. 없거나 숫자가 아니면 "가장 나쁨"으로 둬서 승자가 되지 않게 한다. */
    private static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.MAX_VALUE;
    }
}
