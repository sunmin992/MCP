package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolCatalog;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-137 선택 3종 — <b>등록하지 않기로 한 도구가 여전히 동작하는지</b> 고정한다.
 *
 * <p>이 셋은 스프링 빈이 아니라 tools/list에 나오지 않는다(노출 규모는 허브 14종으로
 * 고정, {@code McpToolExposureTest}). 그렇다고 검증을 빼면 쓰지 않는 코드가 조용히 썩고,
 * 나중에 다시 열 때 "붙이기만 하면 된다"가 사실이 아니게 된다. 그래서 여기서는 직접
 * 생성해 계약을 그대로 확인한다 — 등록 여부와 동작의 정상성은 다른 문제다.
 */
class UnregisteredSubtaskToolsTest {

    private final ObjectMapper om = new ObjectMapper();
    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);

    private final SubmitJangnyangSubtaskAnswerTool submit =
            new SubmitJangnyangSubtaskAnswerTool(sessions);
    private final GetJangnyangSubtaskProgressTool progress =
            new GetJangnyangSubtaskProgressTool(sessions);
    private final ResetJangnyangSubtaskSessionTool reset =
            new ResetJangnyangSubtaskSessionTool(sessions);

    private JsonNode json(String s) throws Exception { return om.readTree(s); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ToolResult r) {
        assertTrue(r.ready(), () -> "실행이 거부됐다: " + r.errors());
        return (Map<String, Object>) r.result();
    }

    @Test
    @DisplayName("세 도구가 McpToolProvider 규약(이름·설명·스키마·도메인)을 지킨다")
    void contractIsIntact() throws Exception {
        for (var p : List.of(submit, progress, reset)) {
            assertFalse(p.toolName().isBlank());
            assertFalse(p.description().isBlank());
            JsonNode schema = json(p.inputSchemaJson());
            assertEquals("object", schema.path("type").asText());
            // 셋 다 sessionKey가 required다 — 기본 키로 대신 실행하면 세션 격리가
            // 무너지므로(NFR-18), 이 계약이 빠지면 안 된다.
            assertTrue(McpToolCatalog.missingRequired(schema, json("{}")).contains("sessionKey"));
        }
    }

    @Test
    @DisplayName("start → submit → progress 흐름이 서버 세션을 그대로 몰고 간다")
    void collectionFlowWorksThroughTheTools() throws Exception {
        Map<String, Object> started = result(submit.call(
                json("{\"sessionKey\":\"ext-1\",\"start\":true}")));
        @SuppressWarnings("unchecked")
        Map<String, Object> q1 = (Map<String, Object>) started.get("question");
        assertEquals("ST-001", q1.get("id"));
        assertEquals(Boolean.FALSE, started.get("readyToBuild"));

        // 질문 문장은 카탈로그의 것이어야 한다 — 도구가 문장을 다시 쓰지 않는다(D-44).
        assertEquals(catalog.latest().byId("ST-001").question(), q1.get("question"));

        result(submit.call(json(
                "{\"sessionKey\":\"ext-1\",\"subtaskId\":\"ST-001\",\"value\":\"목적\"}")));

        Map<String, Object> p = result(progress.call(json("{\"sessionKey\":\"ext-1\"}")));
        assertEquals("ST-002", p.get("currentSubtaskId"));
        assertEquals(catalog.latest().subtaskSetId(), p.get("subtaskSetId"));
        assertEquals(SubtaskState.COLLECTING.name(), p.get("state"));
        assertEquals(1, ((Map<?, ?>) p.get("answers")).size());
    }

    @Test
    @DisplayName("세션 키 없는 호출과 없는 세션 조회를 거부한다")
    void failsClosedWithoutASession() throws Exception {
        ToolResult noKey = submit.call(json("{\"value\":\"목적\"}"));
        assertFalse(noKey.ready());
        assertEquals(ErrorCode.MISSING_FIELD, noKey.errors().get(0).code());

        // 없는 세션에 빈 진행 상태를 지어내지 않는다.
        assertFalse(progress.call(json("{\"sessionKey\":\"없는키\"}")).ready());

        assertFalse(reset.call(json("{}")).ready());
    }

    @Test
    @DisplayName("reset은 상태만 바꾸지 않고 누적 답변까지 지운다(UT-315)")
    void resetClearsAccumulatedAnswers() throws Exception {
        result(submit.call(json("{\"sessionKey\":\"ext-2\",\"start\":true}")));
        result(submit.call(json(
                "{\"sessionKey\":\"ext-2\",\"subtaskId\":\"ST-001\",\"value\":\"지난 목적\"}")));

        Map<String, Object> out = result(reset.call(json("{\"sessionKey\":\"ext-2\"}")));
        assertEquals(SubtaskState.CANCELLED.name(), out.get("state"));
        assertFalse(progress.call(json("{\"sessionKey\":\"ext-2\"}")).ready());

        Map<String, Object> restarted = result(submit.call(
                json("{\"sessionKey\":\"ext-2\",\"start\":true}")));
        @SuppressWarnings("unchecked")
        Map<String, Object> prog = (Map<String, Object>) restarted.get("progress");
        assertTrue(((Map<?, ?>) prog.get("answers")).isEmpty(), "이전 답변이 남으면 안 된다");
    }

    @Test
    @DisplayName("세션이 시작한 버전과 다른 버전의 답변을 거부한다(FR-138)")
    void versionMismatchIsRejected() throws Exception {
        result(submit.call(json("{\"sessionKey\":\"ext-3\",\"start\":true}")));
        ToolResult wrong = submit.call(json(
                "{\"sessionKey\":\"ext-3\",\"subtaskId\":\"ST-001\",\"value\":\"목적\",\"version\":1}"));
        assertFalse(wrong.ready());
        assertTrue(wrong.errors().get(0).message().contains("버전"));
    }
}
