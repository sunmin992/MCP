package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 도구 {@code simulate_edge_throttling} — 보드·냉각·부하·회복 정책을 주면
 * 온도 시계열과 TTT·TED·TRT를 계산해 돌려준다(R&E 실험 설계 §4.5 측정 지표 전부).
 *
 * <p>장량동 쓰레기 도메인과 입력이 전혀 겹치지 않으므로 {@code SimulationModelProvider}가
 * 아니라 {@link McpToolProvider} 확장점을 쓴다(MCP_모델_연결_방법.md §3). 공용
 * {@code SimulationConfigValidator}를 타지 않는 대신 {@link EdgeArgs}로 직접
 * fail-closed 검증한다.
 *
 * <p><b>학생용 사용 흐름</b>: (1) 프리셋으로 대략 돌려 실험 시간(부하 몇 분, 회복 몇 분)을
 * 정한다 → (2) 그 시간대로 실제 라즈베리파이를 측정한다 → (3)
 * {@code calibrate_edge_thermal_model}로 실측 파라미터를 얻는다 → (4) 그 {@code profileId}를
 * 이 도구에 넣어, 실제로는 못 돌려본 조건(다른 주변온도·다른 목표 FPS 등)을 외삽한다.
 */
@Component
public class SimulateEdgeThrottlingTool implements McpToolProvider {

    private final ThermalSimulator simulator = new ThermalSimulator();
    private final EdgeThermalProfileStore profiles;
    private final AiLoadProfileService aiLoads;

    public SimulateEdgeThrottlingTool(EdgeThermalProfileStore profiles, AiLoadProfileService aiLoads) {
        this.profiles = profiles;
        this.aiLoads = aiLoads;
    }


    /** 장량동 쓰레기 도메인과 무관한 라즈베리파이 엣지 발열 도구 — POST /mcp/edge 에 노출된다. */
    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "simulate_edge_throttling"; }

    @Override
    public String description() {
        return "라즈베리파이(Pi4/Pi5) 고부하 AI 추론 시 발열·스로틀링 동역학을 열 RC 모델로 "
             + "시뮬레이션해 TTT(스로틀링 진입시간)·TED(에피소드 지속시간)·TRT(회복시간)와 "
             + "온도/클럭/FPS 시계열을 반환한다. 냉각 조건(bare/passive/active)과 회복 정책"
             + "(R1 완전중지 / R2 저부하 / R3 능동냉각)을 바꿔가며 비교할 수 있다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "board": {"type": "string", "enum": ["pi4", "pi5"], "description": "대상 보드"},
                "cooling": {"type": "string", "enum": ["bare", "passive", "active"],
                  "description": "냉각 상태 — bare(기본), passive(방열판), active(팬)", "default": "passive"},
                "ambientTempC": {"type": "number", "description": "주변 온도 ℃", "default": 25},
                "workloadMode": {"type": "string", "enum": ["target_fps", "max_throughput"],
                  "description": "target_fps=목표 FPS만 채움(주 비교 대상), max_throughput=클럭이 허용하는 만큼 최대 추론", "default": "target_fps"},
                "targetFps": {"type": "number", "description": "목표 추론 FPS(target_fps 모드)", "default": 10},
                "maxFps": {"type": "number", "description": "스로틀링 없을 때의 최대 FPS. 모델(FP32/INT8)에 맞춰 실측값으로 덮어쓸 것"},
                "loadSeconds": {"type": "number", "description": "고부하 추론 유지 시간(초)", "default": 900},
                "recoveryPolicy": {"type": "string", "enum": ["r1_stop", "r2_low_load", "r3_active_cooling", "none"],
                  "description": "회복 정책 — R1 완전중지, R2 목표FPS 25%, R3 팬100%+서비스유지, none=조치 없음(자연 회복만 관찰)", "default": "none"},
                "recoverySeconds": {"type": "number", "description": "회복 관찰 시간(초)", "default": 600},
                "recoveryRJaKPerW": {"type": "number", "description": "회복 구간 열저항 K/W. 기본값은 R3일 때 보드의 active 프리셋, 그 외엔 부하 구간과 동일"},
                "applyRecoveryOnThrottle": {"type": "boolean", "description": "true면 스로틀링이 감지된 순간 회복 정책을 적용한다(정책끼리 TRT를 공정하게 비교하려면 같은 상태에서 출발해야 하므로 기본값). false면 정확히 loadSeconds 시점에 적용", "default": true},
                "sampleIntervalSeconds": {"type": "number", "description": "결과 시계열 출력 간격(초)", "default": 5},
                "startTempC": {"type": "number", "description": "실험 시작 온도 ℃. 기본값은 해당 냉각 조건의 유휴 정상상태 온도"},
                "profileId": {"type": "string", "description": "calibrate_edge_thermal_model로 저장한 실측 파라미터 id(예: cal-001). 넣으면 프리셋 대신 실측값으로 계산"},
                "fanRpm": {"type": "number", "description": "냉각팬 회전수. 넣으면 회전수에 맞춰 열저항이 낮아지고 소비전력이 함께 집계된다. 0이면 팬을 끈 상태(대조군) — 팬 유무·세기를 같은 조건에서 비교할 때 쓴다"},
                "fanRatedRpm": {"type": "number", "description": "팬 정격(최대) 회전수", "default": 5000},
                "fanRatedPowerW": {"type": "number", "description": "정격 회전수에서의 팬 소비전력 W. 전력은 회전수의 3승에 비례하므로 절반만 돌리면 1/8만 쓴다", "default": 0.5},
                "fanArray": {"type": "object",
                  "description": "냉각팬 배열(Pi5 4핀 헤더 + 40mm 팬 2개 등). 위 단일 fanRpm과 동시에 넣으면 거부된다(fail-closed). presetId=\\"PI5_DUAL_40MM_PRELIMINARY\\"로 검증 전 임시 사양을 바로 쓰거나, fans[]·ratedRpm·ratedPowerW·commandedPwmPercent를 직접 지정한다. TACH 실측(measuredArrayRpm)·실측전류(measuredCurrentA)가 있으면 PWM 추정보다 우선한다. 팬 위치(offsetXmm·flowDirection)는 실측 전 조건 보존용 메타데이터로만 쓰고 냉각계수를 만들지 않는다",
                  "properties": {
                    "presetId": {"type": "string", "description": "PI5_DUAL_40MM_PRELIMINARY 등"},
                    "commandedPwmPercent": {"type": "number", "default": 100},
                    "measuredArrayRpm": {"type": "number", "description": "TACH 실측 RPM(있으면 PWM 추정보다 우선)"},
                    "measuredCurrentA": {"type": "number", "description": "실측 전류(있으면 전력=5V×I)"},
                    "ratedRpm": {"type": "number", "default": 7750},
                    "ratedPowerW": {"type": "number", "default": 0.625},
                    "ratedCurrentA": {"type": "number", "default": 0.125},
                    "ratedValueScope": {"type": "string", "enum": ["PER_FAN", "DUAL_FAN_ARRAY_ASSUMED", "ARRAY_TOTAL_MEASURED"], "description": "정격값이 팬별인지 배열 전체인지 — 이중 계산 방지"},
                    "measurementScope": {"type": "string", "enum": ["PER_FAN", "DUAL_FAN_TOTAL", "UNKNOWN_PER_FAN_OR_TOTAL"]},
                    "verified": {"type": "boolean", "default": false},
                    "fans": {"type": "array", "description": "팬 목록(fanCount는 이 길이에서 센다)", "items": {"type": "object"}}
                  }},
                "heatsinkMassG": {"type": "number", "description": "방열판 질량(g). 넣으면 방열판을 별도 열 덩어리로 보는 2노드 모델로 계산한다 — 정상상태는 1노드와 같고 과도응답만 달라진다. 부하가 출렁일 때 질량이 피크를 흡수하는 효과를 보려면 필요하다"},
                "heatsinkMaterial": {"type": "string", "enum": ["aluminum", "copper"], "description": "방열판 재질(질량→열용량 변환용). 비열 알루미늄 900 / 구리 385 J/(kg·K)이라 같은 질량이면 알루미늄이 열용량이 크다", "default": "aluminum"},
                "heatsinkCThJPerK": {"type": "number", "description": "방열판 열용량 J/K 직접 지정. heatsinkMassG 대신 쓴다"},
                "rInternalKPerW": {"type": "number", "description": "SoC→방열판 열저항 K/W(2노드). 전체 rJa보다 작아야 하며 나머지가 방열판→공기가 된다. 기본값은 보드의 rJc(다이→패키지)"},
                "aiLoadProfileId": {"type": "string", "enum": ["steady", "burst", "mixed"],
                  "description": "AI 데이터센터식 시변 부하 패턴 — steady(일정, 대조군), burst(2분 몰림/3분 한가 반복), mixed(한가대→피크대 흐름 + 각 구간 버스트). 생략하면 부하가 일정하다. workloadMode와는 독립된 축이며, 목표 FPS·최대 처리량 어느 쪽에도 배율로 곱해진다"},
                "thermalOverride": {"type": "object", "description": "열 파라미터 직접 지정",
                  "properties": {
                    "rJaKPerW": {"type": "number", "description": "전체 열저항 K/W"},
                    "cThJPerK": {"type": "number", "description": "등가 열용량 J/K"},
                    "ambientC": {"type": "number"},
                    "idlePowerW": {"type": "number"},
                    "dynamicPowerW": {"type": "number"},
                    "startTempC": {"type": "number"}
                  }},
                "includeSeries": {"type": "boolean", "description": "온도 시계열 포함 여부", "default": true}
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

        ThermalParams p = EdgeToolSupport.thermalParams(a, board, cooling, ambient, profiles);

        AiLoadProfile aiLoad = EdgeToolSupport.aiLoadProfile(a, aiLoads);
        ThermalSimulator.Spec spec = EdgeToolSupport.spec(a, board, p, 900.0, WorkloadMode.TARGET_FPS, aiLoad);
        boolean includeSeries = a.bool("includeSeries", true);

        if (a.hasErrors()) return ToolResult.rejected(a.errors());

        ThermalRun run = simulator.run(board, spec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("board", board.label());
        out.put("soc", board.soc());
        out.put("cooling", cooling.name().toLowerCase());
        // 주변 온도는 실험 조건 중에서도 가장 자주 빠뜨리는 값이라(R_ja 계산의 기준)
        // 배치 비교 도구와 마찬가지로 결과에 그대로 되돌려 남긴다.
        out.put("ambientTempC", ambient);
        out.put("workloadMode", spec.mode().name().toLowerCase());
        out.put("targetFps", spec.targetFps());
        if (spec.aiLoad() != null) {
            // 어떤 패턴으로 돌렸는지 결과에 남긴다(재현성 — params를 함께 돌려주는 것과 같은 취지).
            Map<String, Object> lp = new LinkedHashMap<>();
            lp.put("id", spec.aiLoad().getId());
            lp.put("label", spec.aiLoad().getLabel());
            lp.put("cycleSeconds", spec.aiLoad().cycleSeconds());
            lp.put("meanLevel", spec.aiLoad().meanLevel());
            lp.put("peakLevel", spec.aiLoad().peakLevel());
            lp.put("timescaleFit", spec.aiLoad().timescaleFit(run.params().tauSeconds()).name().toLowerCase());
            out.put("aiLoadProfile", lp);
        }
        // 구성 요소는 화면이 보드·방열판·팬 구성도를 그리는 데 쓴다 — 없으면 담지 않아
        // "지정하지 않음"과 "0으로 지정함"이 구분된다(팬 0 RPM은 달려 있지만 꺼진 상태).
        ThermalRun.FanReport fr = run.fanReport();
        if (fr != null) {
            out.put("fanCount", fr.fanCount());
            out.put("commandedPwmPercent", fr.commandedPwmPercent());
            out.put("measuredRpm", fr.measuredRpm());
            out.put("effectiveRpm", fr.effectiveRpm());
            out.put("rpmSource", fr.rpmSource());
            out.put("fanCurrentA", fr.fanCurrentA());
            out.put("fanPowerW", fr.fanPowerW());
            out.put("fanEnergyJ", fr.fanEnergyJ());
            out.put("fanStartupEnergyJ", fr.fanStartupEnergyJ());
            out.put("startupPeakCurrentA", fr.startupPeakCurrentA());
            out.put("fanSpecSource", fr.fanSpecSource());
            out.put("fanSpecVerified", fr.fanSpecVerified());
            out.put("fanMeasurementScope", fr.measurementScope());
        }
        if (spec.heatsink() != null) {
            out.put("heatsinkCThJPerK", spec.heatsink().cThJPerK());
            double massG = a.dbl("heatsinkMassG", 0.0, 0.0, 5000.0);
            if (massG > 0) out.put("heatsinkMassG", massG);
            if (a.has("heatsinkMaterial")) out.put("heatsinkMaterial", a.str("heatsinkMaterial", "aluminum"));
        }
        out.put("recoveryPolicy", spec.policy().name().toLowerCase());
        out.put("loadSeconds", spec.loadSec());
        out.put("recoverySeconds", spec.recoverySec());
        out.put("metrics", metrics(run));
        out.put("params", run.params());
        out.put("episodes", run.episodes());
        out.put("notes", run.notes());
        if (includeSeries) out.put("series", run.series());
        else out.put("seriesOmitted", "includeSeries=false — 시계열을 생략했다.");
        return ToolResult.ok(out);
    }

    /** 실험 설계 §4.5 지표를 한 덩어리로 모아 학생이 결과지에 바로 옮겨 적을 수 있게 한다. */
    static Map<String, Object> metrics(ThermalRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("softLimitEntrySec", run.softLimitEntrySec());
        m.put("tttSec", run.tttSec());
        m.put("tttFirstDetectSec", run.tttFirstDetectSec());
        m.put("throttlingExpected", run.throttlingExpected());
        m.put("episodeCount", run.episodes().size());
        m.put("medianTedSec", run.medianTedSec());
        m.put("trtStateSec", run.trtStateSec());
        m.put("trtServiceSec", run.trtServiceSec());
        m.put("trtFullSec", run.trtFullSec());
        m.put("peakTempC", run.peakTempC());
        m.put("loadEndTempC", run.loadEndTempC());
        // 온도 진폭(안정성) — 부품 수명 평가 축. 상수 부하면 ≈0, 시변 부하에서 의미가 있다.
        m.put("tempAmplitudeC", run.tempAmplitudeC());
        m.put("loadSettledMeanTempC", run.loadSettledMeanTempC());
        m.put("steadyStateTempC", run.steadyStateTempC());
        m.put("throttledFraction", run.throttledFraction());
        m.put("meanFpsDuringLoad", run.meanFpsLoad());
        m.put("fpsDropPercent", run.fpsDropPercent());
        m.put("throughputLossPercent", run.throughputLossPercent());
        m.put("tauHeatingSec", run.tauHeatingSec());
        m.put("energyJ", run.energyJ());
        // 팬을 켰을 때만 노출한다 — 팬이 없으면 0이라 표만 길어지고, 가성비 판정의
        // 분모가 totalEnergyJ라는 점이 오히려 흐려진다.
        if (run.fanEnergyJ() > 0) {
            m.put("fanEnergyJ", run.fanEnergyJ());
            m.put("totalEnergyJ", run.totalEnergyJ());
        }
        return m;
    }
}
