package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 채팅 → 엣지 도구 경로의 부품 검증(파라미터 안전망 · 프리셋 · 결과 포맷팅).
 * {@code ChatController}는 STOMP·Spring이 필요해 여기서는 그 안에서 벌어지는 일을
 * 같은 순서로 재현해 확인한다.
 */
class EdgeChatRoutingTest {

    private final ObjectMapper om = new ObjectMapper();
    private final EdgeThermalProfileStore store = new EdgeThermalProfileStore();

    @Test
    @DisplayName("실험 조건을 좌우하는 값은 LLM 출력이 아니라 이번 메시지에서 확정한다")
    void guardOverridesLlmOnDecisiveFields() throws Exception {
        // LLM이 이전 턴에 낚여 pi4·방열판을 들고 왔다고 가정
        JsonNode llm = om.readTree("{\"board\":\"pi4\",\"cooling\":\"passive\",\"loadSeconds\":1200}");
        ObjectNode merged = EdgeParamGuard.merge(llm, EdgeParamGuard.fromText("라즈베리파이 5 무냉각으로 돌려줘"));

        assertEquals("pi5", merged.path("board").asText(), "이번 메시지의 보드가 이겨야 한다");
        assertEquals("bare", merged.path("cooling").asText());
        assertEquals(1200, merged.path("loadSeconds").asInt(), "LLM이 뽑은 값 중 겹치지 않는 건 살아남는다");
    }

    @Test
    @DisplayName("LLM이 죽어도(null) 정규식으로 뽑은 값만으로 실행된다")
    void worksWithoutLlm() {
        ObjectNode args = EdgeParamGuard.merge(null, EdgeParamGuard.fromText(
                "pi5 무냉각, 실내 온도 30도, 15fps로 최대 처리량 실험"));
        assertEquals("pi5", args.path("board").asText());
        assertEquals("bare", args.path("cooling").asText());
        assertEquals(30.0, args.path("ambientTempC").asDouble(), 1e-9);
        assertEquals(15.0, args.path("targetFps").asDouble(), 1e-9);
        assertEquals("max_throughput", args.path("workloadMode").asText());

        ToolResult tr = new SimulateEdgeThrottlingTool(store).call(args);
        assertTrue(tr.ready(), () -> "정규식 값만으로도 실행돼야 한다: " + tr.errors());
    }

    @Test
    @DisplayName("냉각 조건은 구체적인 쪽이 이긴다 — '방열판에 팬까지'는 팬 냉각")
    void mostSpecificCoolingWins() {
        assertEquals("active", EdgeParamGuard.fromText("방열판에 팬까지 달았을 때").path("cooling").asText());
        assertEquals("bare", EdgeParamGuard.fromText("방열판 없이 무냉각으로").path("cooling").asText());
        assertEquals("passive", EdgeParamGuard.fromText("방열판만 붙이면").path("cooling").asText());
    }

    @Test
    @DisplayName("회복 정책도 문장에서 결정론적으로 뽑힌다")
    void recoveryPolicyFromText() {
        assertEquals("r3_active_cooling",
                EdgeParamGuard.fromText("스로틀링 걸리면 팬 100%로 켜서 회복시켜줘").path("recoveryPolicy").asText());
        assertEquals("r1_stop",
                EdgeParamGuard.fromText("추론을 완전히 중지하면 얼마나 걸려?").path("recoveryPolicy").asText());
        assertEquals("r2_low_load",
                EdgeParamGuard.fromText("부하를 25% 저부하로 낮추면?").path("recoveryPolicy").asText());
    }

    @Test
    @DisplayName("회복 조치로서의 '팬'은 부하 구간 냉각 조건을 바꾸지 않는다")
    void fanAsRecoveryDoesNotCoolTheLoadPhase() {
        ObjectNode args = EdgeParamGuard.fromText("pi5 스로틀링 걸린 다음에 팬 100%로 켜면 얼마나 빨리 회복돼?");
        assertEquals("r3_active_cooling", args.path("recoveryPolicy").asText());
        assertFalse(args.has("cooling"),
                "처음부터 팬이 돌면 스로틀링이 안 걸려서 회복 시간을 잴 수 없다");
    }

    @Test
    @DisplayName("회복 실험인데 조건이 비면 스로틀링이 실제로 걸리는 표준 조건을 채운다")
    void recoveryExperimentGetsUsableDefaults() {
        ObjectNode args = EdgeParamGuard.merge(null,
                EdgeParamGuard.fromText("pi5 스로틀링 걸리면 팬 100%로 켜서 회복시켜줘"));
        assertTrue(EdgeParamGuard.applyRecoveryExperimentDefaults(args));
        assertEquals("bare", args.path("cooling").asText());
        assertEquals("max_throughput", args.path("workloadMode").asText());

        ToolResult tr = new SimulateEdgeThrottlingTool(store).call(args);
        assertTrue(tr.ready(), () -> String.valueOf(tr.errors()));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tr.result();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) out.get("metrics");
        assertNotNull(m.get("tttSec"), "회복 시간을 재려면 실제로 스로틀링이 걸려야 한다");
        assertNotNull(m.get("trtStateSec"), "그래야 TRT가 산출된다");
    }

    @Test
    @DisplayName("회복 정책이 없으면 기본 조건을 건드리지 않는다")
    void plainRequestKeepsToolDefaults() {
        ObjectNode args = EdgeParamGuard.merge(null, EdgeParamGuard.fromText("pi5 발열 얼마나 심해?"));
        assertFalse(EdgeParamGuard.applyRecoveryExperimentDefaults(args));
        assertFalse(args.has("cooling"));
        assertFalse(args.has("workloadMode"));
    }

    @Test
    @DisplayName("보드가 없으면 값을 지어내지 않는다 — 되물어야 하는 상태로 남는다")
    void doesNotInventBoard() {
        assertFalse(EdgeParamGuard.fromText("발열 시뮬레이션 돌려줘").has("board"));
        // 두 보드를 함께 언급한 비교 요청도 한쪽으로 단정하지 않는다
        assertFalse(EdgeParamGuard.fromText("pi4랑 pi5 중 뭐가 더 뜨거워?").has("board"));
    }

    @Test
    @DisplayName("방열판 프리셋 6종은 유효하고 그대로 도구에 넣어 실행된다")
    void presetsRunEndToEnd() throws Exception {
        JsonNode layouts = om.readTree(HeatsinkPresets.LAYOUTS_JSON);
        assertTrue(layouts.isArray());
        assertEquals(6, layouts.size());
        assertTrue(layouts.size() <= SimulateHeatsinkLayoutTool.MAX_LAYOUTS);

        ObjectNode args = EdgeParamGuard.merge(null, EdgeParamGuard.fromText("pi5 방열판 배치 비교해줘"));
        args.set("layouts", layouts);
        ToolResult tr = new SimulateHeatsinkLayoutTool(store).call(args);
        assertTrue(tr.ready(), () -> "프리셋으로 바로 실행돼야 한다: " + tr.errors());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tr.result();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        assertEquals(7, ranking.size(), "후보 6종 + 무냉각 기준선");

        // 한 번에 한 요인만 바꾼 후보들이므로 기준(A)보다 나쁜 배치가 실제로 아래에 와야 한다
        double a = tempOf(ranking, "A 중앙 정렬 (기준)");
        assertTrue(tempOf(ranking, "B 15mm 어긋나게 부착") > a, "어긋나게 붙이면 더 뜨거워야 한다");
        assertTrue(tempOf(ranking, "C 핀이 기류를 가로막음") > a, "핀이 기류를 막으면 더 뜨거워야 한다");
        assertTrue(tempOf(ranking, "E 팬 추가 (8mm 거리)") < a, "팬을 붙이면 시원해야 한다");
    }

    @Test
    @DisplayName("결과 포맷팅은 값이 없는 지표를 0으로 뭉개지 않는다")
    void formatterKeepsNullsMeaningful() throws Exception {
        // 스로틀링이 걸리는 조건 — TTT가 나온다
        var hot = run("{\"board\":\"pi5\",\"cooling\":\"bare\",\"ambientTempC\":35,"
                + "\"workloadMode\":\"max_throughput\",\"loadSeconds\":900,"
                + "\"recoveryPolicy\":\"r1_stop\",\"recoverySeconds\":600}");
        String hotText = EdgeChatFormatter.throttling(hot);
        assertTrue(hotText.contains("스로틀링 진입(TTT): "));
        assertFalse(hotText.contains("TTT): 발생 안 함"));
        assertTrue(hotText.contains("R1 완전 중지"));

        // 스로틀링이 안 걸리는 조건 — "발생 안 함"이라고 적혀야 한다(0초가 아니라)
        var cool = run("{\"board\":\"pi4\",\"cooling\":\"active\",\"ambientTempC\":22,"
                + "\"workloadMode\":\"max_throughput\",\"loadSeconds\":600}");
        String coolText = EdgeChatFormatter.throttling(cool);
        assertTrue(coolText.contains("TTT): 발생 안 함"));
        assertTrue(coolText.contains("※"), "왜 스로틀링이 없는지 설명이 함께 나와야 한다");
    }

    @Test
    @DisplayName("캘리브레이션은 채팅에서 실행하지 않고 보내는 방법을 안내한다")
    void calibrationGuideInsteadOfFakeRun() {
        String empty = EdgeChatFormatter.calibrationGuide(store.all());
        assertTrue(empty.contains("csv_to_mcp_payload.py") || empty.contains("Import-McpCalibration"));
        assertTrue(empty.contains("저장된 캘리브레이션 프로파일이 없습니다"));

        store.save("pi5-passive-26C", BoardType.PI5,
                new ThermalCalibrator.ThermalOverride(4.2, 14.0, 26.0, 3.0, 8.5, 38.6, null));
        String withProfile = EdgeChatFormatter.calibrationGuide(store.all());
        assertTrue(withProfile.contains("cal-001"));
        assertTrue(withProfile.contains("pi5-passive-26C"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String argsJson) throws Exception {
        ToolResult tr = new SimulateEdgeThrottlingTool(store).call(om.readTree(argsJson));
        assertTrue(tr.ready(), () -> String.valueOf(tr.errors()));
        return (Map<String, Object>) tr.result();
    }

    private double tempOf(List<Map<String, Object>> ranking, String name) {
        return ranking.stream().filter(r -> name.equals(r.get("name")))
                .map(r -> (Double) r.get("steadyStateTempC"))
                .findFirst().orElseThrow(() -> new AssertionError("후보 없음: " + name));
    }
}
