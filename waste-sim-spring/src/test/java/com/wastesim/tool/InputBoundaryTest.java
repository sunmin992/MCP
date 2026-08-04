package com.wastesim.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.edge.CalibrateEdgeThermalModelTool;
import com.wastesim.edge.EdgeArgs;
import com.wastesim.edge.EdgeThermalProfileStore;
import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 입력 경계 강화 검증 (DEBUGGING_ISSUES.md W-04·E-03·E-05).
 *
 * <p>셋 다 <b>잘못된 값이 조용히 다른 값으로 바뀌는</b> 부류다. 12:99가 13:39가 되고,
 * 핀 10.9개가 10개가 되고, NaN이 범위 검증을 통과한다. 오류로 돌려주지 않으면 클라이언트는
 * 자기가 보낸 값과 다른 조건으로 실험이 돌아간 것을 알 수 없다.
 */
class InputBoundaryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private EdgeArgs args(String json) {
        try {
            return new EdgeArgs(mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── W-04: HH:MM 파서 ────────────────────────────────────────────────

    @Test
    @DisplayName("정상적인 시각 표기는 그대로 해석한다")
    void validTimesParse() {
        assertEquals(510, SimulationConfig.hhmmToMinutes("8:30"));
        assertEquals(510, SimulationConfig.hhmmToMinutes("08:30"));
        assertEquals(1439, SimulationConfig.hhmmToMinutes("23:59"));
        assertEquals(0, SimulationConfig.hhmmToMinutes("00:00"));
    }

    /**
     * 12:99는 총 분이 819(=13:39)라 하루 범위 안에 들어가므로 이후 범위 검증도 통과한다.
     * 즉 사용자가 요청한 시각과 다른 시각으로 실험이 돌아가고 아무도 모른다.
     */
    @Test
    @DisplayName("분이 60 이상이면 거부한다 — 12:99가 13:39로 둔갑하면 안 된다")
    void minuteOverflowRejected() {
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.hhmmToMinutes("12:99"));
    }

    @Test
    @DisplayName("시가 24 이상이거나 형식이 깨지면 거부한다")
    void malformedTimesRejected() {
        for (String bad : new String[]{"24:00", "25:30", "12:", ":30", "abc", "", "12:30:45", "-1:00"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SimulationConfig.hhmmToMinutes(bad), "거부해야 한다: " + bad);
        }
    }

    // ── E-03: 정수 필드에 소수 입력 ──────────────────────────────────────

    @Test
    @DisplayName("정수 필드에 소수를 주면 거부한다 — 10.9가 10으로 잘리면 안 된다")
    void fractionalIntegerRejected() {
        EdgeArgs a = args("{\"finCount\": 10.9}");
        a.intVal("finCount", 0, 0, 200);
        assertTrue(a.hasErrors(), "10.9를 10으로 조용히 절삭하면 스키마의 integer 계약이 깨진다");
    }

    @Test
    @DisplayName("정수는 그대로 통과한다")
    void integerAccepted() {
        EdgeArgs a = args("{\"finCount\": 10}");
        assertEquals(10, a.intVal("finCount", 0, 0, 200));
        assertFalse(a.hasErrors());
    }

    // ── E-05: NaN / Infinity ────────────────────────────────────────────

    /**
     * JSON 경로는 {@code isNumber()}가 이미 막는다 — "NaN"은 JSON 숫자가 아니라 문자열이라
     * 거기서 걸린다. 실제로 새는 곳은 <b>보정 CSV</b>이고, {@code Double.parseDouble}이
     * "NaN"·"Infinity"를 그대로 받는다.
     */
    @Test
    @DisplayName("JSON 인자의 NaN 문자열은 이미 숫자 검사에서 막힌다")
    void nanStringRejectedByNumberCheck() {
        EdgeArgs a = args("{\"ambientTempC\": \"NaN\"}");
        a.dbl("ambientTempC", 25.0, -20.0, 60.0);
        assertTrue(a.hasErrors());
    }

    @Test
    @DisplayName("정상적인 실수는 통과한다 — 과학 표기 포함")
    void finiteDoublesAccepted() {
        EdgeArgs a = args("{\"ambientTempC\": 26.5, \"other\": 1.2e2}");
        assertEquals(26.5, a.dbl("ambientTempC", 25.0, -20.0, 60.0), 1e-9);
        assertEquals(120.0, a.dbl("other", 0, 0, 1000), 1e-9);
        assertFalse(a.hasErrors());
    }

    // ── E-05: 보정 CSV의 NaN / Infinity ─────────────────────────────────

    private final CalibrateEdgeThermalModelTool calibrator =
            new CalibrateEdgeThermalModelTool(new EdgeThermalProfileStore());

    private ToolResult calibrate(String csv) {
        try {
            String json = mapper.writeValueAsString(java.util.Map.of(
                    "board", "pi5", "ambientTempC", 25.0, "samplesCsv", csv));
            return calibrator.call(mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지수 곡선 모양의 정상 데이터 — 여기에 한 칸만 오염시켜 비교한다. */
    private String csv(String badTemp) {
        StringBuilder sb = new StringBuilder("t_sec,soc_temp_c\n");
        for (int i = 0; i <= 20; i++) {
            double temp = 80 - 40 * Math.exp(-i * 10 / 100.0);
            sb.append(i * 10).append(',')
              .append(i == 5 && badTemp != null ? badTemp : String.format("%.2f", temp))
              .append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("정상 CSV는 보정에 성공한다 — 기준선")
    void validCsvCalibrates() {
        assertTrue(calibrate(csv(null)).ready());
    }

    @Test
    @DisplayName("CSV에 NaN이 있으면 거부한다 — 범위 검증을 우회해 결과 전체를 오염시킨다")
    void nanInCsvRejected() {
        ToolResult r = calibrate(csv("NaN"));
        assertFalse(r.ready(), "NaN이 통과하면 R²·RMSE까지 전부 NaN이 된다");
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("유한")),
                "무엇이 문제인지 알려야 한다: " + r.errors());
    }

    @Test
    @DisplayName("CSV의 무한대도 거부한다")
    void infinityInCsvRejected() {
        assertFalse(calibrate(csv("Infinity")).ready());
        assertFalse(calibrate(csv("-Infinity")).ready());
    }
}
