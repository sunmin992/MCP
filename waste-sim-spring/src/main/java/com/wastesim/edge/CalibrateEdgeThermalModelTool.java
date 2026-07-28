package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구 {@code calibrate_edge_thermal_model} — 라즈베리파이에서 실제로 측정한
 * 시계열을 넣으면 열 모델 파라미터(R_ja, C_th, τ_h, τ_c)를 역추정하고, 실측
 * TTT/TED/TRT까지 뽑아 저장한다({@link ThermalCalibrator}).
 *
 * <p>이 도구가 R&E의 "실측 → 시뮬레이션" 다리다. 반환된 {@code profileId}를
 * {@code simulate_edge_throttling}·{@code simulate_heatsink_layout}에 넣으면, 그때부터
 * 시뮬레이션은 문헌 추정치가 아니라 <b>학생이 직접 잰 보드</b>를 흉내내게 된다.
 *
 * <p>입력은 두 형식을 받는다 — 구조화된 {@code samples} 배열, 또는 측정 스크립트가
 * 그대로 뱉는 {@code samplesCsv}(헤더 포함 CSV 문자열). 후자는 학생이 CSV를 손으로
 * JSON으로 바꾸는 수고를 없애려는 것이다.
 */
@Component
public class CalibrateEdgeThermalModelTool implements McpToolProvider {

    /** 샘플 개수 상한 — 1초 간격 6시간 분량. */
    static final int MAX_SAMPLES = 22000;
    static final int MIN_SAMPLES = 5;

    private final ThermalCalibrator calibrator = new ThermalCalibrator();
    private final EdgeThermalProfileStore profiles;

    public CalibrateEdgeThermalModelTool(EdgeThermalProfileStore profiles) {
        this.profiles = profiles;
    }


    /** 장량동 쓰레기 도메인과 무관한 라즈베리파이 엣지 발열 도구 — POST /mcp/edge 에 노출된다. */
    @Override public McpDomain domain() { return McpDomain.EDGE; }

    @Override public String toolName() { return "calibrate_edge_thermal_model"; }

    @Override
    public String description() {
        return "라즈베리파이 실측 시계열(시간·SoC온도·전력·클럭·FPS·throttled)에서 열 모델 파라미터"
             + "(R_ja, C_th, 가열/냉각 시정수)를 역추정하고 실측 TTT/TED/TRT를 추출한다. "
             + "결과 profileId를 시뮬레이션 도구에 넣으면 실측 보정된 예측이 된다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {
              "type": "object",
              "properties": {
                "board": {"type": "string", "enum": ["pi4", "pi5"], "description": "측정한 보드"},
                "ambientTempC": {"type": "number", "description": "측정 당시 주변 온도 ℃ (반드시 함께 기록해 둘 것)"},
                "label": {"type": "string", "description": "실험 조건 라벨(예: 'pi5-passive-25C-int8')"},
                "loadEndSeconds": {"type": "number", "description": "고부하를 멈춘 시각(초). 가열/냉각 구간을 나누는 기준. 생략하면 최고온도 시점으로 자동 판정"},
                "samples": {
                  "type": "array", "description": "측정 시계열(시간 오름차순)",
                  "items": {"type": "object", "properties": {
                    "tSec": {"type": "number", "description": "실험 시작 후 경과 시간(초)"},
                    "socTempC": {"type": "number", "description": "SoC 온도 ℃"},
                    "powerW": {"type": "number", "description": "소비전력 W(있으면 R_ja 정확도가 크게 오른다)"},
                    "clockMhz": {"type": "number", "description": "ARM 클럭 MHz"},
                    "fps": {"type": "number", "description": "추론 처리량"},
                    "throttled": {"type": "boolean", "description": "get_throttled 0x4 비트 활성 여부"}
                  }, "required": ["tSec", "socTempC"]}
                },
                "samplesCsv": {"type": "string",
                  "description": "samples 대신 CSV 문자열로 넣어도 된다. 첫 줄은 헤더, 열 이름은 t_sec,soc_temp_c,power_w,clock_mhz,fps,throttled (측정 스크립트 출력 형식과 동일)"},
                "saveProfile": {"type": "boolean", "description": "결과를 profileId로 저장할지", "default": true}
              },
              "required": ["board", "ambientTempC"]
            }
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        EdgeArgs a = new EdgeArgs(args);
        BoardType board = a.enumVal("board", BoardType.PI4, BoardType::parse, EdgeToolSupport.BOARD_ENUM, true);
        double ambient = a.reqDbl("ambientTempC", -20.0, 60.0);
        String label = a.str("label", "unlabeled");
        Double loadEnd = a.has("loadEndSeconds") ? a.dbl("loadEndSeconds", 0, 0, 100000.0) : null;
        boolean save = a.bool("saveProfile", true);

        List<ThermalCalibrator.Sample> samples = new ArrayList<>();
        if (a.has("samplesCsv")) {
            parseCsv(a.str("samplesCsv", ""), samples, a);
        } else if (a.raw("samples").isArray()) {
            int i = 0;
            for (JsonNode n : a.raw("samples")) {
                EdgeArgs s = new EdgeArgs(n);
                double t = s.reqDbl("tSec", -1.0, 1000000.0);
                double temp = s.reqDbl("socTempC", -40.0, 130.0);
                samples.add(new ThermalCalibrator.Sample(t, temp,
                        s.has("powerW") ? s.dbl("powerW", 0, 0, 200.0) : null,
                        s.has("clockMhz") ? s.dbl("clockMhz", 0, 0, 10000.0) : null,
                        s.has("fps") ? s.dbl("fps", 0, 0, 10000.0) : null,
                        s.has("throttled") ? s.raw("throttled").asBoolean() : null));
                if (!s.errors().isEmpty()) {
                    a.reject(ErrorCode.INVALID_ARGUMENTS, "samples[" + i + "]",
                            s.errors().get(0).message());
                }
                i++;
            }
        } else {
            a.reject(ErrorCode.MISSING_FIELD, "samples", "samples 배열 또는 samplesCsv 문자열이 필요하다.");
        }

        if (samples.size() < MIN_SAMPLES) {
            a.reject(ErrorCode.OUT_OF_RANGE, "samples",
                    "시정수를 추정하려면 최소 " + MIN_SAMPLES + "개 샘플이 필요하다(받은 개수: " + samples.size() + ").");
        }
        if (samples.size() > MAX_SAMPLES) {
            a.reject(ErrorCode.OUT_OF_RANGE, "samples",
                    "샘플이 너무 많다(최대 " + MAX_SAMPLES + "개). 샘플링 간격을 늘려 다시 보낼 것.");
        }
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).tSec() <= samples.get(i - 1).tSec()) {
                a.reject(ErrorCode.INVALID_ARGUMENTS, "samples",
                        "시간이 오름차순이 아니다(인덱스 " + i + "). 로그를 시간순으로 정렬해 보낼 것.");
                break;
            }
        }
        if (a.hasErrors()) return ToolResult.rejected(a.errors());

        ThermalCalibrator.Calibration cal;
        try {
            cal = calibrator.calibrate(samples, ambient, board, loadEnd);
        } catch (IllegalArgumentException e) {
            return ToolResult.rejected(new ValidationError(ErrorCode.EXECUTION_ERROR, "samples", e.getMessage()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName());
        out.put("board", board.label());
        out.put("label", label);
        out.put("sampleCount", samples.size());
        out.put("ambientTempC", ambient);
        out.put("estimated", Map.of(
                "rJaKPerW", cal.rJaKPerW(),
                "cThJPerK", cal.cThJPerK(),
                "tauHeatingSec", cal.heating().tauSec(),
                "tauCoolingSec", cal.cooling() == null ? "n/a" : cal.cooling().tauSec(),
                "steadyStateTempC", cal.heating().asymptoteC(),
                "loadPowerW", cal.loadPowerW(),
                "powerSource", cal.powerSource()));
        out.put("fitHeating", cal.heating());
        out.put("fitCooling", cal.cooling());
        out.put("measured", measured(cal));
        out.put("fitQuality", cal.quality());
        out.put("thermalOverride", cal.thermalOverride());
        out.put("warnings", cal.warnings());

        if (save) {
            var profile = profiles.save(label, board, cal.thermalOverride());
            out.put("profileId", profile.profileId());
            out.put("nextStep", "이 profileId를 simulate_edge_throttling / simulate_heatsink_layout의 profileId 인자에 넣으면 "
                    + "실측 보정된 조건으로 다른 시나리오를 외삽할 수 있다.");
        }
        return ToolResult.ok(out);
    }

    private static Map<String, Object> measured(ThermalCalibrator.Calibration cal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tttSec", cal.measuredTttSec());
        m.put("tedSec", cal.measuredTeds());
        m.put("trtStateSec", cal.measuredTrtStateSec());
        m.put("peakTempC", cal.measuredPeakTempC());
        m.put("fpsDropPercent", cal.measuredFpsDropPercent());
        return m;
    }

    /** 측정 스크립트가 내보내는 CSV(헤더 포함)를 그대로 받아 파싱한다. */
    private void parseCsv(String csv, List<ThermalCalibrator.Sample> out, EdgeArgs a) {
        String[] lines = csv.split("\\R");
        if (lines.length < 2) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, "samplesCsv", "헤더 한 줄 + 데이터 한 줄 이상이 필요하다.");
            return;
        }
        String[] header = lines[0].split(",");
        int iT = -1, iTemp = -1, iP = -1, iClk = -1, iFps = -1, iThr = -1;
        for (int i = 0; i < header.length; i++) {
            switch (header[i].trim().toLowerCase()) {
                case "t_sec", "tsec", "time_s", "elapsed_s" -> iT = i;
                case "soc_temp_c", "soctempc", "temp_c", "temperature_c" -> iTemp = i;
                case "power_w", "powerw" -> iP = i;
                case "clock_mhz", "clockmhz", "arm_clock_mhz" -> iClk = i;
                case "fps" -> iFps = i;
                case "throttled", "throttled_now" -> iThr = i;
                default -> { }
            }
        }
        if (iT < 0 || iTemp < 0) {
            a.reject(ErrorCode.INVALID_ARGUMENTS, "samplesCsv",
                    "필수 열(t_sec, soc_temp_c)을 찾지 못했다. 헤더: " + lines[0]);
            return;
        }
        for (int r = 1; r < lines.length; r++) {
            String line = lines[r].trim();
            if (line.isEmpty()) continue;
            String[] c = line.split(",", -1);
            try {
                out.add(new ThermalCalibrator.Sample(
                        Double.parseDouble(c[iT].trim()),
                        Double.parseDouble(c[iTemp].trim()),
                        num(c, iP), num(c, iClk), num(c, iFps),
                        iThr >= 0 && iThr < c.length && !c[iThr].isBlank()
                                ? parseBool(c[iThr].trim()) : null));
            } catch (Exception e) {
                a.reject(ErrorCode.INVALID_ARGUMENTS, "samplesCsv",
                        (r + 1) + "번째 줄을 숫자로 읽지 못했다: " + line);
                return;
            }
        }
    }

    private static Double num(String[] c, int idx) {
        if (idx < 0 || idx >= c.length || c[idx].isBlank()) return null;
        try { return Double.parseDouble(c[idx].trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean parseBool(String s) {
        return switch (s.toLowerCase()) {
            case "1", "true", "t", "yes", "y" -> Boolean.TRUE;
            case "0", "false", "f", "no", "n" -> Boolean.FALSE;
            default -> s.startsWith("0x") ? (Long.decode(s) & 0x4) != 0 : null;
        };
    }
}
