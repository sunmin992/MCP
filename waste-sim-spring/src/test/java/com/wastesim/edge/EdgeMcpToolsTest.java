package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.McpToolRegistry;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 도구 계층 검증 — 세 도구가 {@link McpToolProvider} 규약을 지키고,
 * <b>범위 밖 인자를 실행 전에 차단</b>하는지(fail-closed) 본다. 이 도구들은 공용
 * {@code SimulationConfigValidator}를 타지 않으므로, 검증 책임이 전적으로 구현체에 있다 —
 * 여기가 뚫리면 GPT가 만든 엉뚱한 숫자가 그대로 물리 계산까지 들어간다.
 */
class EdgeMcpToolsTest {

    private final ObjectMapper om = new ObjectMapper();
    private final EdgeThermalProfileStore store = new EdgeThermalProfileStore();
    private final SimulateEdgeThrottlingTool throttling = new SimulateEdgeThrottlingTool(store);
    private final SimulateHeatsinkLayoutTool layout = new SimulateHeatsinkLayoutTool(store);
    private final CalibrateEdgeThermalModelTool calibrate = new CalibrateEdgeThermalModelTool(store);

    private JsonNode json(String s) throws Exception { return om.readTree(s); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ToolResult r) {
        assertTrue(r.ready(), () -> "실행이 거부됐다: " + r.errors());
        return (Map<String, Object>) r.result();
    }

    @Test
    @DisplayName("세 도구가 레지스트리에 등록되고 스키마가 유효한 JSON이다")
    void toolsRegisterWithValidSchemas() throws Exception {
        var registry = new McpToolRegistry(List.of(throttling, layout, calibrate));
        assertEquals(3, registry.all().size());
        for (McpToolProvider p : registry.all()) {
            assertSame(p, registry.byToolName(p.toolName()));
            assertFalse(p.description().isBlank());
            JsonNode schema = json(p.inputSchemaJson());
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("properties").size() > 0);
        }
        assertNotNull(registry.byToolName("simulate_edge_throttling"));
        assertNotNull(registry.byToolName("simulate_heatsink_layout"));
        assertNotNull(registry.byToolName("calibrate_edge_thermal_model"));
    }

    @Test
    @DisplayName("범위 밖·오타 인자는 실행 전에 전부 모아 거부한다")
    void failsClosedOnBadArguments() throws Exception {
        ToolResult r = throttling.call(json("{\"board\":\"pi9\",\"ambientTempC\":999,\"targetFps\":-3}"));
        assertFalse(r.ready());
        assertEquals(3, r.errors().size(), "오류를 하나씩이 아니라 한 번에 모두 알려줘야 한다");
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.INVALID_ENUM));
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.OUT_OF_RANGE));

        assertFalse(throttling.call(json("{}")).ready(), "board는 필수다");
        assertFalse(layout.call(json("{\"board\":\"pi5\"}")).ready(), "layouts가 없으면 거부");
        assertFalse(calibrate.call(json("{\"board\":\"pi4\"}")).ready(), "ambientTempC가 없으면 거부");
    }

    @Test
    @DisplayName("존재하지 않는 profileId는 조용히 무시하지 않고 거부한다")
    void unknownProfileIsRejected() throws Exception {
        ToolResult r = throttling.call(json("{\"board\":\"pi5\",\"profileId\":\"cal-999\"}"));
        assertFalse(r.ready());
        assertEquals("profileId", r.errors().get(0).field());
    }

    @Test
    @DisplayName("정상 호출이면 실험 설계서의 측정 지표가 모두 담긴 결과를 낸다")
    void returnsAllExperimentMetrics() throws Exception {
        var out = result(throttling.call(json("""
                {"board":"pi5","cooling":"bare","ambientTempC":35,"workloadMode":"max_throughput",
                 "loadSeconds":900,"recoveryPolicy":"r1_stop","recoverySeconds":600,"sampleIntervalSeconds":30}""")));

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) out.get("metrics");
        for (String key : List.of("softLimitEntrySec", "tttSec", "medianTedSec", "trtStateSec",
                "trtServiceSec", "trtFullSec", "peakTempC", "fpsDropPercent", "tauHeatingSec")) {
            assertTrue(m.containsKey(key), "측정 지표 누락: " + key);
        }
        assertNotNull(m.get("tttSec"), "Pi5 무냉각 35℃면 스로틀링이 관측돼야 한다");
        assertFalse(((List<?>) out.get("series")).isEmpty());
    }

    @Test
    @DisplayName("방열판 후보는 정상상태 온도 순으로 정렬되고 무냉각 기준선이 자동으로 끼어든다")
    void ranksLayoutsAgainstBareBaseline() throws Exception {
        var out = result(layout.call(json("""
                {"board":"pi5","ambientTempC":28,"workloadMode":"max_throughput","layouts":[
                  {"name":"중앙정렬","heatsink":{"baseLengthMm":40,"baseWidthMm":40,"finCount":10,
                     "finHeightMm":12,"finThicknessMm":1.2}},
                  {"name":"15mm 어긋남","heatsink":{"baseLengthMm":40,"baseWidthMm":40,"finCount":10,
                     "finHeightMm":12,"finThicknessMm":1.2},"placement":{"offsetXMm":15}}]}""")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertEquals(3, ranking.size(), "후보 2개 + 무냉각 기준선");
        assertEquals("중앙정렬", out.get("bestLayout"));
        double prev = -999;
        for (Map<String, Object> row : ranking) {
            double temp = (Double) row.get("steadyStateTempC");
            assertTrue(temp >= prev, "정상상태 온도 오름차순이어야 한다");
            prev = temp;
        }
        assertEquals("(기준선) 방열판 없음", ranking.get(2).get("name"));
        assertNotNull(ranking.get(0).get("improvementHint"));
    }

    @Test
    @DisplayName("후보 개수 상한을 넘으면 거부한다")
    void rejectsTooManyLayouts() throws Exception {
        StringBuilder sb = new StringBuilder("{\"board\":\"pi4\",\"layouts\":[");
        for (int i = 0; i < SimulateHeatsinkLayoutTool.MAX_LAYOUTS + 1; i++) {
            sb.append(i > 0 ? "," : "").append("{\"heatsink\":{\"baseLengthMm\":30,\"baseWidthMm\":30}}");
        }
        assertFalse(layout.call(json(sb.append("]}").toString())).ready());
    }

    @Test
    @DisplayName("CSV 실측 → 캘리브레이션 → profileId → 다른 조건으로 외삽까지 한 바퀴 돈다")
    void calibrationRoundTrip() throws Exception {
        double r = 4.2, c = 14.0, amb = 26, p = 11.5;
        double tInf = amb + p * r, t0 = amb + 3 * r, tau = r * c;
        StringBuilder csv = new StringBuilder("t_sec,soc_temp_c,power_w,clock_mhz,fps,throttled\\n");
        for (int t = 0; t <= 600; t += 5) {
            double temp = tInf - (tInf - t0) * Math.exp(-t / tau);
            csv.append(String.format("%d,%.1f,%.2f,2400,25.0,0%n", t, temp, p));
        }
        var args = om.createObjectNode().put("board", "pi5").put("ambientTempC", amb)
                .put("label", "pi5-passive-26C").put("loadEndSeconds", 600)
                .put("samplesCsv", csv.toString());

        var cal = result(calibrate.call(args));
        @SuppressWarnings("unchecked")
        Map<String, Object> est = (Map<String, Object>) cal.get("estimated");
        assertEquals(r, (Double) est.get("rJaKPerW"), r * 0.05);
        assertEquals(c, (Double) est.get("cThJPerK"), c * 0.05);
        String profileId = (String) cal.get("profileId");
        assertNotNull(profileId);
        assertNotNull(store.get(profileId));

        // 같은 보드를 35℃ 방에서 돌리면? — 실측 R·C는 유지, 주변 온도만 이동
        var sim = result(throttling.call(json(
                "{\"board\":\"pi5\",\"profileId\":\"" + profileId + "\",\"ambientTempC\":35,"
                + "\"workloadMode\":\"max_throughput\",\"loadSeconds\":600,\"includeSeries\":false}")));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) sim.get("metrics");
        assertEquals(tInf + 9.0, (Double) m.get("steadyStateTempC"), 0.5,
                "주변 온도를 9℃ 올리면 정상상태도 9℃ 올라야 한다");
    }

    @Test
    @DisplayName("시간이 뒤죽박죽인 로그는 거부한다")
    void rejectsUnsortedSamples() throws Exception {
        var r = calibrate.call(json("""
                {"board":"pi4","ambientTempC":25,"samples":[
                  {"tSec":0,"socTempC":40},{"tSec":10,"socTempC":45},{"tSec":5,"socTempC":50},
                  {"tSec":20,"socTempC":55},{"tSec":30,"socTempC":58}]}"""));
        assertFalse(r.ready());
        assertTrue(r.errors().get(0).message().contains("오름차순"));
    }
}
