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
 * MCP 도구 {@code simulate_heatsink_layout} — "방열판을 어떻게 배치해야 효율이 좋은가"에
 * 숫자로 답한다. 후보 배치를 여러 개 넣으면 각각의 열저항 R_ja를 형상·오프셋·기류에서
 * 계산하고({@link HeatsinkThermalModel}), 그 R_ja로 발열 시뮬레이션까지 돌려
 * ({@link ThermalSimulator}) 정상상태 온도·TTT 기준으로 <b>순위를 매긴다</b>.
 *
 * <p>무냉각(bare) 기준선을 항상 자동으로 끼워 넣는다 — "방열판을 붙여서 몇 ℃ 내려갔나"를
 * 같은 계산식으로 비교해야 의미가 있기 때문이다.
 *
 * <p>개선 힌트는 LLM이 아니라 <b>열저항 분해에서 가장 큰 항</b>을 보고 고른 고정 규칙으로
 * 만든다(이 프로젝트의 C2 원칙 — 실행·판단에 영향을 주는 결정은 결정론적으로).
 */
@Component
public class SimulateHeatsinkLayoutTool implements McpToolProvider {

    /** 후보 개수 상한 — 응답 크기와 학생의 비교 가능 범위를 함께 고려한 값. */
    static final int MAX_LAYOUTS = 12;

    private final HeatsinkThermalModel model = new HeatsinkThermalModel();
    private final ThermalSimulator simulator = new ThermalSimulator();
    private final EdgeThermalProfileStore profiles;
    private final AiLoadProfileService aiLoads;

    public SimulateHeatsinkLayoutTool(EdgeThermalProfileStore profiles, AiLoadProfileService aiLoads) {
        this.profiles = profiles;
        this.aiLoads = aiLoads;
    }


    /** 장량동 쓰레기 도메인과 무관한 라즈베리파이 엣지 발열 도구 — POST /mcp/edge 에 노출된다. */
    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "simulate_heatsink_layout"; }

    @Override
    public String description() {
        return "방열판 형상(크기·핀 수/높이·재질)과 배치(SoC 중심 대비 오프셋·핀 방향·TIM·팬 거리)를 "
             + "주면 전체 열저항 R_ja를 계산하고 그 값으로 발열 시뮬레이션까지 돌려, 후보 배치들을 "
             + "정상상태 온도·스로틀링 진입시간(TTT) 기준으로 순위 매긴다. 무냉각 기준선이 자동 포함된다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "board": {"type": "string", "enum": ["pi4", "pi5"], "description": "대상 보드"},
                "ambientTempC": {"type": "number", "description": "주변 온도 ℃", "default": 25},
                "workloadMode": {"type": "string", "enum": ["target_fps", "max_throughput"], "default": "max_throughput"},
                "aiLoadProfileId": {"type": "string", "enum": ["steady", "burst", "mixed"],
                  "description": "AI 부하 패턴 — 생략하면 상수 부하. 상수 부하에서 순위는 열저항 R_ja로만 정해지지만, burst/mixed처럼 부하가 출렁이면 열용량이 피크 온도를 좌우해 후보 순위가 뒤집힐 수 있다"},
                "targetFps": {"type": "number", "description": "목표 추론 FPS(target_fps 모드)", "default": 10},
                "maxFps": {"type": "number", "description": "스로틀링 없을 때의 최대 FPS(실측값 권장)"},
                "loadSeconds": {"type": "number", "description": "고부하 유지 시간(초)", "default": 1800},
                "profileId": {"type": "string", "description": "calibrate_edge_thermal_model로 저장한 실측 프로파일 id — 열용량 C_th 등을 실측값으로 맞춘 뒤 배치만 바꿔 비교할 때 쓴다"},
                "includeSeries": {"type": "boolean", "description": "후보별 온도 시계열 포함 여부", "default": false},
                "includeBareBaseline": {"type": "boolean", "description": "무냉각 기준선 자동 포함", "default": true},
                "layouts": {
                  "type": "array", "description": "비교할 방열판 배치 후보(1~12개)",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string", "description": "후보 이름(예: '중앙정렬-핀세로')"},
                      "heatsink": {
                        "type": "object",
                        "properties": {
                          "baseLengthMm": {"type": "number", "description": "베이스 길이(mm) — 핀이 뻗은 방향(=X축)"},
                          "baseWidthMm": {"type": "number", "description": "베이스 폭(mm) — 핀이 나열된 방향(=Y축)"},
                          "baseThicknessMm": {"type": "number", "default": 2},
                          "finCount": {"type": "integer", "description": "핀 개수(0=민판)", "default": 0},
                          "finHeightMm": {"type": "number", "default": 10},
                          "finThicknessMm": {"type": "number", "default": 1},
                          "material": {"type": "string", "enum": ["aluminum", "copper"], "default": "aluminum"}
                        },
                        "required": ["baseLengthMm", "baseWidthMm"]
                      },
                      "placement": {
                        "type": "object",
                        "properties": {
                          "offsetXMm": {"type": "number", "description": "SoC 패키지 중심 대비 X 오프셋(mm). 0=정중앙 정렬", "default": 0},
                          "offsetYMm": {"type": "number", "description": "Y 오프셋(mm)", "default": 0},
                          "finAlignment": {"type": "string", "enum": ["aligned", "cross"],
                            "description": "핀 채널이 기류(팬 바람/자연대류 상승)와 나란한가", "default": "aligned"}
                        }
                      },
                      "airflow": {
                        "type": "object",
                        "properties": {
                          "type": {"type": "string", "enum": ["natural", "forced"], "default": "natural"},
                          "airSpeedMps": {"type": "number", "description": "방열판 표면 풍속(m/s). 없으면 fanRpm에서 추정"},
                          "fanRpm": {"type": "number", "description": "팬 회전수"},
                          "fanDistanceMm": {"type": "number", "description": "팬-방열판 거리(mm)", "default": 5}
                        }
                      },
                      "tim": {
                        "type": "object",
                        "properties": {
                          "type": {"type": "string", "enum": ["pad", "paste", "tape"], "default": "pad"},
                          "thicknessMm": {"type": "number", "description": "두께(mm). 얇을수록 유리"},
                          "conductivityWmK": {"type": "number", "description": "실측 열전도율이 있으면 지정"}
                        }
                      },
                      "hotspots": {
                        "type": "array", "description": "SoC 외 부수 발열점(열화상으로 좌표를 읽어 넣는다)",
                        "items": {"type": "object", "properties": {
                          "name": {"type": "string"}, "xMm": {"type": "number"},
                          "yMm": {"type": "number"}, "powerW": {"type": "number"}}}
                      }
                    },
                    "required": ["heatsink"]
                  }
                }
              },
              "required": ["board", "layouts"]
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        EdgeArgs a = new EdgeArgs(args);
        BoardType board = a.enumVal("board", BoardType.PI4, BoardType::parse, EdgeToolSupport.BOARD_ENUM, true);
        double ambient = a.dbl("ambientTempC", 25.0, -20.0, 60.0);
        boolean includeSeries = a.bool("includeSeries", false);
        boolean includeBare = a.bool("includeBareBaseline", true);

        JsonNode layoutsNode = args == null ? null : args.get("layouts");
        List<HeatsinkLayout> layouts = new ArrayList<>();
        if (layoutsNode == null || !layoutsNode.isArray() || layoutsNode.isEmpty()) {
            a.reject(ErrorCode.MISSING_FIELD, "layouts", "비교할 방열판 배치 후보를 1개 이상 넣어야 한다.");
        } else if (layoutsNode.size() > MAX_LAYOUTS) {
            a.reject(ErrorCode.OUT_OF_RANGE, "layouts",
                    "후보는 최대 " + MAX_LAYOUTS + "개까지다(받은 개수: " + layoutsNode.size() + ").");
        } else {
            int i = 0;
            for (JsonNode n : layoutsNode) {
                EdgeArgs sub = new EdgeArgs(n);
                layouts.add(EdgeToolSupport.layout(sub, "layout" + (++i)));
                a.errors().addAll(sub.errors());
            }
        }

        // 열용량·전력은 프리셋/실측 프로파일에서 가져오고, R_ja만 배치별로 갈아끼운다.
        ThermalParams base = EdgeToolSupport.thermalParams(a, board, CoolingPreset.PASSIVE, ambient, profiles);

        // 부하 패턴은 이 도구에서 특히 중요하다 — 상수 부하에서는 후보 순위가 R_ja로만
        // 정해지지만, 부하가 출렁이면 열용량이 피크 온도를 좌우해 순위가 뒤집힐 수 있다.
        // 조용히 무시하면 "패턴 조건에서 비교했다"고 믿은 채 상수 부하 순위를 읽게 된다.
        AiLoadProfile aiLoad = EdgeToolSupport.aiLoadProfile(a, aiLoads);
        ThermalSimulator.Spec baseSpec = EdgeToolSupport.spec(a, board, base, 1800.0,
                WorkloadMode.MAX_THROUGHPUT, aiLoad);

        if (a.hasErrors()) return ToolResult.rejected(a.errors());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (HeatsinkLayout layout : layouts) {
            HeatsinkThermalModel.Result r = model.evaluate(board, layout, ambient,
                    base.fullLoadSteadyTempC());
            rows.add(row(board, layout.name(), r, base.withRJa(r.rJaKPerW()), baseSpec, includeSeries));
        }
        if (includeBare) {
            ThermalParams bareP = base.withRJa(board.rJaKPerW(CoolingPreset.BARE));
            rows.add(row(board, "(기준선) 방열판 없음", null, bareP, baseSpec, includeSeries));
        }

        rows.sort(Comparator.comparingDouble(m -> (Double) m.get("steadyStateTempC")));
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("rank", i + 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("board", board.label());
        out.put("ambientTempC", ambient);
        out.put("workloadMode", baseSpec.mode().name().toLowerCase());
        out.put("loadSeconds", baseSpec.loadSec());
        if (baseSpec.aiLoad() != null) {
            AiLoadProfile lp = baseSpec.aiLoad();
            out.put("aiLoadProfileId", lp.getId());
            out.put("aiLoadProfileLabel", lp.getLabel());
            // 순위를 읽기 전에 "이 패턴이 애초에 순위를 바꿀 수 있는 시간 규모인가"를 알려준다.
            out.put("aiLoadNote", lp.timescaleNote(base.tauSeconds()));
        }
        out.put("ranking", rows);
        out.put("bestLayout", rows.get(0).get("name"));
        out.put("interpretation", interpretation(rows));
        out.put("modelCaveat", "대류 열전달계수·오정렬 상수는 경험식이다. 절대 온도보다 후보 간 상대 비교(Δ℃)를 신뢰할 것. "
                + "실측 한 조건을 calibrate_edge_thermal_model로 보정한 뒤 profileId를 넣으면 절대값 정확도가 올라간다.");
        return ToolResult.ok(out);
    }

    private Map<String, Object> row(BoardType board, String name, HeatsinkThermalModel.Result r,
                                    ThermalParams p, ThermalSimulator.Spec baseSpec, boolean includeSeries) {
        ThermalSimulator.Spec spec = new ThermalSimulator.Spec(p, baseSpec.mode(), baseSpec.targetFps(),
                baseSpec.loadSec(), baseSpec.policy(), baseSpec.recoverySec(), p.rJaKPerW(),
                baseSpec.dtSec(), baseSpec.sampleSec(), baseSpec.triggerOnThrottle(), baseSpec.aiLoad());
        ThermalRun run = simulator.run(board, spec);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("rJaKPerW", p.rJaKPerW());
        m.put("steadyStateTempC", run.steadyStateTempC());
        m.put("peakTempC", run.peakTempC());
        m.put("throttlingExpected", run.throttlingExpected());
        m.put("tttSec", run.tttSec());
        m.put("throttledFraction", run.throttledFraction());
        m.put("meanFpsDuringLoad", run.meanFpsLoad());
        m.put("marginToHardLimitC", ThermalSimulator.round(p.hardLimitC() - run.steadyStateTempC(), 2));
        if (r != null) {
            m.put("coverage", r.coverage());
            m.put("contactAreaMm2", r.contactAreaMm2());
            m.put("finSurfaceAreaCm2", r.finSurfaceAreaCm2());
            m.put("finEfficiency", r.finEfficiency());
            m.put("hEffWm2K", r.hEffWm2K());
            m.put("airSpeedMps", r.airSpeedMps());
            m.put("resistanceBreakdown", r.breakdown());
            m.put("dominantResistance", dominant(r));
            m.put("improvementHint", hint(r));
            m.put("hotspots", r.hotspots());
            m.put("warnings", r.warnings());
        }
        if (includeSeries) m.put("series", run.series());
        return m;
    }

    /** 방열판 경로에서 가장 큰 열저항 항 — 여기를 고쳐야 실제로 온도가 내려간다. */
    static String dominant(HeatsinkThermalModel.Result r) {
        var b = r.breakdown();
        double max = Math.max(Math.max(b.rConv(), b.rTim()), Math.max(b.rMisalign(), b.rSpread()));
        if (max == b.rConv()) return "rConv(대류)";
        if (max == b.rTim()) return "rTim(접촉 열전달물질)";
        if (max == b.rMisalign()) return "rMisalign(오정렬)";
        return "rSpread(확산)";
    }

    /** 지배 항별 고정 개선 규칙(결정론적 — LLM 판단 아님). */
    static String hint(HeatsinkThermalModel.Result r) {
        return switch (dominant(r)) {
            case "rConv(대류)" -> "방열 면적·기류가 병목이다 — 핀 높이/개수를 늘리거나(핀 간격 1.5mm 이상 유지) 팬을 방열판 가까이(10mm 이내) 붙이는 것이 가장 효과가 크다.";
            case "rTim(접촉 열전달물질)" -> "접촉면이 병목이다 — 두꺼운 서멀패드/테이프 대신 얇은(0.1mm 이하) 서멀 그리스를 쓰고 밀착 압력을 높일 것.";
            case "rMisalign(오정렬)" -> "방열판이 SoC를 제대로 덮지 못한 것이 병목이다 — 오프셋을 0에 맞추는 것만으로 다른 어떤 개선보다 크게 내려간다.";
            default -> "작은 다이에서 넓은 베이스로 열이 퍼지는 확산 저항이 병목이다 — 베이스를 더 두껍게 하거나 구리 베이스를 쓰면 개선된다.";
        };
    }

    /** 1등과 꼴등의 차이를 학생이 바로 읽을 수 있는 한 문장으로 만든다. */
    static String interpretation(List<Map<String, Object>> rows) {
        var best = rows.get(0);
        var worst = rows.get(rows.size() - 1);
        double delta = (Double) worst.get("steadyStateTempC") - (Double) best.get("steadyStateTempC");
        String s = String.format("1위 '%s'는 정상상태 %.1f℃, 최하위 '%s'는 %.1f℃로 차이는 %.1f℃다.",
                best.get("name"), (Double) best.get("steadyStateTempC"),
                worst.get("name"), (Double) worst.get("steadyStateTempC"), delta);
        if (Boolean.TRUE.equals(best.get("throttlingExpected"))) {
            s += " 다만 1위 후보도 이론상 스로틀링을 피하지 못한다 — 부하를 낮추거나(목표 FPS 하향) 능동 냉각이 필요하다.";
        } else {
            s += String.format(" 1위 후보는 하드 제한까지 %.1f℃ 여유가 있어 스로틀링 없이 연속 운용할 수 있다.",
                    (Double) best.get("marginToHardLimitC"));
        }
        return s;
    }
}
