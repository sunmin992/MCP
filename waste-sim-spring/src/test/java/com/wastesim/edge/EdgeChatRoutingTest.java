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

        ToolResult tr = new SimulateEdgeThrottlingTool(store, new AiLoadProfileService()).call(args);
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

        ToolResult tr = new SimulateEdgeThrottlingTool(store, new AiLoadProfileService()).call(args);
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
        ToolResult tr = new SimulateHeatsinkLayoutTool(store, new AiLoadProfileService()).call(args);
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

    // ── 보드 비교 ──────────────────────────────────────────────────────────
    //
    // 실제로 재현된 버그: "라즈베리파이 4와 5의 발열 특성이 어떻게 다른지 설명해줘"를
    // 보냈더니 Pi4 결과 하나만 돌아왔다. 원인은 PI5 정규식이 "파이" 바로 뒤의 5만
    // 찾아서 뒤쪽 보드를 놓쳤고, 그 결과 p4^p5가 참이 되어 board=pi4로 확정된 것이다.
    // 비교 요청이 "틀린 답"이 아니라 "다른 실험"으로 조용히 바뀌는 게 가장 위험하다.

    @Test
    @DisplayName("두 보드를 나란히 적으면 한쪽으로 확정하지 않는다(비교 요청)")
    void parallelBoardMentionIsNotResolvedToOne() {
        String[] comparisons = {
                "라즈베리파이 4와 5의 발열 특성이 어떻게 다른지 설명해줘",
                "라즈베리파이4와 5 비교해줘",
                "Pi4 vs Pi5",
                "pi4랑 pi5 차이가 뭐야",
                "4와 5 중에 뭐가 더 뜨거워?",
                "5랑 4 비교",
                "4 대 5 비교해줘"
        };
        for (String text : comparisons) {
            assertTrue(EdgeParamGuard.isBoardComparison(text), "비교로 판정돼야 한다: " + text);
            assertFalse(EdgeParamGuard.fromText(text).hasNonNull("board"),
                    "한쪽 보드로 확정하면 안 된다: " + text);
        }
    }

    @Test
    @DisplayName("보드가 하나만 나오면 비교가 아니라 그 보드로 확정한다(회귀 방지)")
    void singleBoardStillResolves() {
        assertFalse(EdgeParamGuard.isBoardComparison("라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?"));
        assertEquals("pi5", EdgeParamGuard.fromText("라즈베리파이 5 무냉각으로 20분").path("board").asText());
        assertEquals("pi4", EdgeParamGuard.fromText("pi4에 방열판 달면 온도 얼마나 내려가?").path("board").asText());
    }

    @Test
    @DisplayName("보드와 무관한 숫자를 보드로 오인하지 않는다")
    void unrelatedNumbersAreNotBoards() {
        String[] notBoards = {
                "주변 온도 25도에서 20분 측정",
                "목표 4 FPS로 돌려줘",
                "10분과 20분 중 뭐가 나아?",
                "SoC 온도가 85도 넘으면 FPS가 얼마나 떨어져?"
        };
        for (String text : notBoards) {
            assertFalse(EdgeParamGuard.isBoardComparison(text), "비교가 아니어야 한다: " + text);
            assertFalse(EdgeParamGuard.fromText(text).hasNonNull("board"), "보드가 없어야 한다: " + text);
        }
    }

    @Test
    @DisplayName("보드 비교 결과에 두 보드가 모두 나오고, 공통 조건은 한 번만 나온다")
    void boardComparisonShowsBothBoards() throws Exception {
        // ChatController#runEdgeComparison이 하는 일을 그대로 재현 — board만 바꿔 두 번 실행
        String base = "{\"cooling\":\"bare\",\"workloadMode\":\"max_throughput\",\"loadSeconds\":1200,\"includeSeries\":false,\"board\":\"%s\"}";
        List<Map<String, Object>> runs = List.of(
                run(String.format(base, "pi4")), run(String.format(base, "pi5")));

        String text = EdgeChatFormatter.boardComparison(runs);
        assertTrue(text.contains("Raspberry Pi 4B"), "Pi4가 나와야 한다:\n" + text);
        assertTrue(text.contains("Raspberry Pi 5"), "Pi5가 나와야 한다:\n" + text);
        assertTrue(text.contains("정상상태 예상 온도"), "지표가 나란히 나와야 한다");
        assertTrue(text.contains("→"), "어떻게 다른지 결론 한 줄이 있어야 한다");
        // 공통 조건은 헤더에 한 번만 — 보드마다 반복되면 무엇이 통제됐는지 흐려진다
        assertEquals(1, text.split("공통 조건", -1).length - 1);
    }

    @Test
    @DisplayName("비교 실행은 보드 외 조건이 완전히 같아야 성립한다")
    void comparisonControlsEverythingButBoard() throws Exception {
        String base = "{\"cooling\":\"bare\",\"workloadMode\":\"max_throughput\",\"ambientTempC\":30,\"loadSeconds\":900,\"includeSeries\":false,\"board\":\"%s\"}";
        Map<String, Object> pi4 = run(String.format(base, "pi4"));
        Map<String, Object> pi5 = run(String.format(base, "pi5"));

        assertEquals(pi4.get("cooling"), pi5.get("cooling"));
        assertEquals(pi4.get("workloadMode"), pi5.get("workloadMode"));
        assertEquals(pi4.get("loadSeconds"), pi5.get("loadSeconds"));
        assertNotEquals(pi4.get("board"), pi5.get("board"), "보드만 달라야 한다");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String argsJson) throws Exception {
        ToolResult tr = new SimulateEdgeThrottlingTool(store, new AiLoadProfileService()).call(om.readTree(argsJson));
        assertTrue(tr.ready(), () -> String.valueOf(tr.errors()));
        return (Map<String, Object>) tr.result();
    }

    private double tempOf(List<Map<String, Object>> ranking, String name) {
        return ranking.stream().filter(r -> name.equals(r.get("name")))
                .map(r -> (Double) r.get("steadyStateTempC"))
                .findFirst().orElseThrow(() -> new AssertionError("후보 없음: " + name));
    }
}
