package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code simulate_ptm_control} MCP 도구 회귀 — 입력 거부와 <b>승자 판정 규칙</b>을 고정한다.
 *
 * <p>승자 규칙이 핵심이다. "에너지가 제일 적은 방식"을 그냥 고르면 <b>냉각을 포기해 스로틀링을
 * 맞은 방식</b>이 이길 수 있는데, 그건 절감이 아니라 성능을 판 것이다.
 */
class PtmControlToolTest {

    private final ObjectMapper om = new ObjectMapper();
    private final SimulatePtmControlTool tool =
            new SimulatePtmControlTool(new EdgeThermalProfileStore(), new AiLoadProfileService());

    private JsonNode args(String json) {
        try { return om.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String json) {
        ToolResult tr = tool.call(args(json));
        assertTrue(tr.ready(), () -> "실행됐어야 한다: " + tr.errors());
        return (Map<String, Object>) tr.result();
    }

    /** 무냉각에는 팬을 달 자리가 없다 — 제어할 대상이 없으므로 실행 전에 거부한다(FR-96). */
    @Test
    void bareCoolingIsRejected() {
        ToolResult tr = tool.call(args("{\"board\":\"pi5\",\"cooling\":\"bare\"}"));
        assertFalse(tr.ready());
        assertTrue(tr.errors().stream().anyMatch(e -> e.message().contains("FR-96")));
    }

    @Test
    void unknownModeIsRejected() {
        ToolResult tr = tool.call(args("{\"board\":\"pi5\",\"modes\":[\"telepathy\"]}"));
        assertFalse(tr.ready());
        assertTrue(tr.errors().stream().anyMatch(e -> e.field().contains("modes")));
    }

    @Test
    void recoveryPolicyCannotCompeteForTheSameFanActuator() {
        ToolResult tr = tool.call(args(
                "{\"board\":\"pi5\",\"recoveryPolicy\":\"r3_active_cooling\"}"));
        assertFalse(tr.ready());
        assertTrue(tr.errors().stream().anyMatch(e ->
                e.field().equals("recoveryPolicy") && e.message().contains("같은 팬")));
    }

    /** 기본 실행은 기준선 둘 + PTM 세 방식을 함께 돌린다 — 기준선 없는 제어 결과는 해석이 안 된다. */
    @Test
    @SuppressWarnings("unchecked")
    void defaultComparesThreeModes() {
        Map<String, Object> out = run("""
                {"board":"pi5","ambientTempC":40,"loadSeconds":1200,"aiLoadProfileId":"burst"}
                """);
        List<Map<String, Object>> runs = (List<Map<String, Object>>) out.get("runs");
        assertEquals(3, runs.size());
        assertEquals(List.of("always_max", "reactive", "predictive"),
                runs.stream().map(r -> (String) r.get("mode")).toList());
    }

    /**
     * 이 도구가 답해야 하는 질문 — 버스트 부하에서 예측형이 이기고, 절감률이 함께 나온다.
     * 절감률의 기준선은 "항상 최대"다(제어를 하지 않았을 때의 비용).
     */
    @Test
    void predictiveWinsOnBurstyLoad() {
        Map<String, Object> out = run("""
                {"board":"pi5","ambientTempC":40,"loadSeconds":1800,"aiLoadProfileId":"burst",
                 "workloadMode":"max_throughput"}
                """);
        assertEquals("OK", out.get("status"));
        assertEquals("predictive", out.get("best"));

        double saved = ((Number) out.get("energySavedVsAlwaysMaxPercent")).doubleValue();
        assertTrue(saved > 0, "항상 최대보다 아껴야 승자로 의미가 있다(실제 " + saved + "%)");
    }

    /** 팬 사양을 지정하지 않았으면 가정했다는 사실이 결과에 남아야 한다(FR-104와 같은 원칙). */
    @Test
    @SuppressWarnings("unchecked")
    void assumedFanSpecIsDisclosed() {
        Map<String, Object> out = run("""
                {"board":"pi5","ambientTempC":40,"loadSeconds":600,"aiLoadProfileId":"burst"}
                """);
        List<String> notes = (List<String>) out.get("notes");
        assertTrue(notes.stream().anyMatch(n -> n.contains("가정했다")),
                "가정한 팬 사양을 밝히지 않으면 절대 에너지를 잘못 읽는다");
    }

    /** 상수 부하로 돌리면 "예측할 것이 없다"는 경고가 붙는다 — 잘못된 결론을 막는 안내다. */
    @Test
    @SuppressWarnings("unchecked")
    void steadyLoadWarnsThatPredictionHasNothingToUse() {
        Map<String, Object> out = run("""
                {"board":"pi5","ambientTempC":40,"loadSeconds":600,"aiLoadProfileId":"steady"}
                """);
        List<String> notes = (List<String>) out.get("notes");
        assertTrue(notes.stream().anyMatch(n -> n.contains("burst")));
    }

    /**
     * 감당 못 하는 조건에서는 승자를 만들지 않는다 — {@code sweep_fan_rpm}의
     * NO_FEASIBLE_POINT와 같은 원칙이다. 최대 팬으로도 스로틀링이 나면 "그중 나은 것"은 답이 아니다.
     */
    @Test
    void noWinnerWhenEveryModeThrottles() {
        Map<String, Object> out = run("""
                {"board":"pi5","ambientTempC":55,"loadSeconds":1800,"workloadMode":"max_throughput",
                 "fanRatedPowerW":0.5,"fanRatedRpm":5000,"aiLoadProfileId":"steady"}
                """);
        if ("NO_FEASIBLE_MODE".equals(out.get("status"))) {
            assertNull(out.get("best"));
            assertNotNull(out.get("recommendation"));
        } else {
            // 이 조건에서도 냉각이 가능하다면 최소한 승자는 스로틀링이 없어야 한다.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) out.get("runs");
            Map<String, Object> best = runs.stream()
                    .filter(r -> r.get("mode").equals(out.get("best"))).findFirst().orElseThrow();
            assertNotEquals(Boolean.TRUE, best.get("throttled"));
        }
    }
}
