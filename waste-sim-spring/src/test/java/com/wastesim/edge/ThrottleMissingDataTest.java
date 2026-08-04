package com.wastesim.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 열 보정에서 {@code throttled} 결측값 처리 (EDGE_PRIORITY_FIX_PLAN.md §2).
 *
 * <p>핵심은 한 줄이다 — <b>측정하지 않은 것을 측정한 것으로 바꾸지 않는다.</b>
 * 예전에는 {@code Boolean.TRUE.equals(throttled)}가 null을 false로 만들어, 결측이
 * "스로틀링 해제"로 둔갑했다. 그 결과 에피소드가 둘로 쪼개져 TED가 짧아지고, 회복 구간의
 * 결측이 해제로 오인돼 TRT가 실제보다 빨리 잡혔다.
 */
class ThrottleMissingDataTest {

    private final ThermalCalibrator calibrator = new ThermalCalibrator();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 지수 상승 곡선 위에 스로틀 비트를 얹는다. 적합 자체는 항상 성공해야 하므로
     * 온도는 정상적인 곡선을 유지하고, 검증 대상은 오직 비트 해석이다.
     */
    private List<ThermalCalibrator.Sample> series(Boolean... bits) {
        List<ThermalCalibrator.Sample> out = new ArrayList<>();
        for (int i = 0; i < bits.length; i++) {
            double t = i * 10.0;
            double temp = 85 - 45 * Math.exp(-t / 100.0);
            out.add(new ThermalCalibrator.Sample(t, temp, 6.6, null, 10.0, bits[i]));
        }
        return out;
    }

    private ThermalCalibrator.Calibration calibrate(List<ThermalCalibrator.Sample> s, Double loadEnd) {
        return calibrator.calibrate(s, 25.0, BoardType.PI4, loadEnd);
    }

    private static final Boolean T = Boolean.TRUE, F = Boolean.FALSE, X = null;

    // ── TED: 결측이 섞인 에피소드는 완결로 세지 않는다 ─────────────────────

    /**
     * `true, true, null, true, false` — 예전 해석이면 null이 false가 되어 에피소드가
     * 20~20초와 30~40초 둘로 쪼개진다. 실제로는 20~40초 한 덩어리일 수도 있고 아닐 수도
     * 있으므로, 어느 쪽도 완결 TED로 단정하면 안 된다.
     */
    @Test
    @DisplayName("에피소드 중간의 결측은 완결 TED를 만들지 않는다")
    void unknownInsideEpisodeIsNotComplete() {
        var cal = calibrate(series(F, F, T, T, X, T, F, F, F, F, F, F), 110.0);
        assertTrue(cal.measuredTeds().isEmpty(),
                "결측이 섞인 에피소드가 완결 TED로 잡히면 안 된다. 실제: " + cal.measuredTeds());
        assertEquals(1, cal.throttleQuality().incompleteEpisodes());
        assertTrue(cal.warnings().stream().anyMatch(w -> w.contains("제외")),
                "제외 사실을 알려야 한다: " + cal.warnings());
    }

    @Test
    @DisplayName("결측이 없는 에피소드는 정상적으로 TED가 된다 — 기준선")
    void cleanEpisodeYieldsTed() {
        var cal = calibrate(series(F, F, T, T, T, F, F, F, F, F, F, F), 110.0);
        assertEquals(List.of(30.0), cal.measuredTeds(), "20~50초 = 30초");
        assertEquals(0, cal.throttleQuality().incompleteEpisodes());
    }

    // ── TTT: 결측 구간을 건너뛰어 확정하지 않는다 ──────────────────────────

    @Test
    @DisplayName("최초 관측 직전이 결측이면 TTT를 확정하지 않는다")
    void ttrNotConfirmedAcrossGap() {
        var cal = calibrate(series(F, F, X, X, T, T, F, F, F, F, F, F), 110.0);
        assertNull(cal.measuredTttSec(),
                "결측 구간 안에서 이미 걸렸을 수 있으므로 40초를 '최초'라고 단정할 수 없다");
        assertTrue(cal.warnings().stream().anyMatch(w -> w.contains("확정하지 못했다")),
                "왜 못 냈는지 알려야 한다: " + cal.warnings());
    }

    @Test
    @DisplayName("결측 없이 관측된 최초 스로틀링은 TTT가 된다")
    void tttConfirmedWhenObservationIsContinuous() {
        var cal = calibrate(series(F, F, F, F, T, T, F, F, F, F, F, F), 110.0);
        assertEquals(40.0, cal.measuredTttSec());
    }

    // ── TRT: 회복 구간의 결측이 해제로 오인되면 안 된다 ────────────────────

    /**
     * 부하 종료(50초) 이후 결측이 이어지다 뒤늦게 CLEAR가 나온다. 결측을 해제로 읽으면
     * TRT가 실제보다 짧게 잡힌다.
     */
    @Test
    @DisplayName("회복 구간의 결측은 TRT를 만들지 않는다")
    void unknownDuringRecoveryDoesNotClearThrottle() {
        var cal = calibrate(series(F, F, T, T, T, T, X, X, F, F, F, F), 50.0);
        assertNull(cal.measuredTrtStateSec(),
                "해제 시점을 알 수 없는데 TRT를 확정하면 회복이 실제보다 빨라 보인다");
    }

    @Test
    @DisplayName("명시적 해제로 끝나면 TRT가 산출된다")
    void explicitClearYieldsTrt() {
        var cal = calibrate(series(F, F, T, T, T, T, T, F, F, F, F, F), 50.0);
        assertEquals(20.0, cal.measuredTrtStateSec(), "부하 종료 50초 → 해제 70초");
    }

    // ── 결측 품질 지표 ──────────────────────────────────────────────────

    @Test
    @DisplayName("결측 개수·비율·최장 공백을 정확히 보고한다")
    void qualityCountsAreAccurate() {
        var cal = calibrate(series(F, X, X, X, F, F, X, F, F, F, F, F), 110.0);
        var q = cal.throttleQuality();
        assertEquals(4, q.missingSamples());
        assertEquals(4.0 / 12.0, q.missingRatio(), 0.001);
        // 10~40초가 연속 결측 — 앞뒤 관측 지점(0초, 40초) 사이가 판단 불가 구간이다.
        assertEquals(40.0, q.longestGapSec(), 1e-9);
        assertEquals(0.0, q.longestGapStartSec());
        assertEquals(40.0, q.longestGapEndSec());
    }

    @Test
    @DisplayName("결측률이 5%를 넘으면 신뢰도 경고를 붙인다")
    void highMissingRatioWarns() {
        var cal = calibrate(series(F, X, F, F, F, F, F, F, F, F, F, F), 110.0);
        assertTrue(cal.throttleQuality().missingRatio() >= 0.05);
        assertTrue(cal.warnings().stream().anyMatch(w -> w.contains("신뢰도가 낮다")),
                "경고가 있어야 한다: " + cal.warnings());
    }

    @Test
    @DisplayName("결측이 하나도 없으면 품질 경고를 붙이지 않는다")
    void noMissingNoWarning() {
        var cal = calibrate(series(F, F, T, T, T, F, F, F, F, F, F, F), 110.0);
        assertEquals(0, cal.throttleQuality().missingSamples());
        assertTrue(cal.warnings().stream().noneMatch(w -> w.contains("누락됐다")));
    }

    // ── 입력 검증(§2.5) ─────────────────────────────────────────────────

    private ToolResult call(String json) {
        try {
            return new CalibrateEdgeThermalModelTool(new EdgeThermalProfileStore())
                    .call(mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 정상 곡선 CSV — throttled 열의 한 칸만 바꿔가며 시험한다. */
    private String csv(String throttledCell) {
        StringBuilder sb = new StringBuilder("t_sec,soc_temp_c,throttled\n");
        for (int i = 0; i <= 20; i++) {
            double temp = 85 - 45 * Math.exp(-i * 10 / 100.0);
            String bit = (i == 7 && throttledCell != null) ? throttledCell : "false";
            sb.append(i * 10).append(',').append(String.format("%.2f", temp)).append(',').append(bit).append('\n');
        }
        return sb.toString();
    }

    private ToolResult calibrateCsv(String csv) {
        try {
            return call(mapper.writeValueAsString(java.util.Map.of(
                    "board", "pi4", "ambientTempC", 25.0, "saveProfile", false, "samplesCsv", csv)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("정상 CSV는 보정에 성공한다 — 기준선")
    void validCsvWorks() {
        assertTrue(calibrateCsv(csv(null)).ready());
    }

    @Test
    @DisplayName("알 수 없는 throttled 표기는 결측으로 바꾸지 않고 행 번호와 함께 거부한다")
    void unknownThrottledStringRejected() {
        ToolResult r = calibrateCsv(csv("ON"));
        assertFalse(r.ready(), "해석 못 한 값을 결측으로 처리하면 실측 지표가 조용히 사라진다");
        String msg = r.errors().get(0).message();
        assertTrue(msg.contains("9"), "몇 번째 줄인지 알려야 한다: " + msg);
        assertTrue(msg.contains("throttled"), msg);
    }

    @Test
    @DisplayName("빈 칸은 미측정으로 허용한다")
    void blankThrottledAllowed() {
        assertTrue(calibrateCsv(csv("")).ready());
    }

    @Test
    @DisplayName("0x 비트 표현은 그대로 해석한다")
    void bitwiseThrottledParsed() {
        assertTrue(calibrateCsv(csv("0x4")).ready());
    }

    @Test
    @DisplayName("JSON의 throttled는 진짜 boolean만 받는다")
    void jsonThrottledMustBeBoolean() {
        StringBuilder samples = new StringBuilder();
        for (int i = 0; i <= 10; i++) {
            double temp = 85 - 45 * Math.exp(-i * 10 / 100.0);
            samples.append(String.format(
                    "{\"tSec\":%d,\"socTempC\":%.2f,\"throttled\":%s}",
                    i * 10, temp, i == 5 ? "\"yes\"" : "false"));
            if (i < 10) samples.append(',');
        }
        ToolResult r = call("{\"board\":\"pi4\",\"ambientTempC\":25.0,\"saveProfile\":false,"
                + "\"samples\":[" + samples + "]}");
        assertFalse(r.ready(), "\"yes\"가 조용히 false가 되면 전 구간이 '스로틀링 없음'이 된다");
        assertTrue(r.errors().stream().anyMatch(e -> e.field().contains("throttled")),
                "어느 필드인지 알려야 한다: " + r.errors());
    }
}
