package com.wastesim.subtask;

import com.wastesim.service.OpenAiService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-328~332 — <b>LLM 역할 경계</b>(TDD 3.17.5).
 *
 * <p>MockWebServer로 LLM 응답을 고정 주입한다. 실제 모델을 띄우면 이 검증이 모델의 그날
 * 컨디션에 좌우되는데, 여기서 보는 것은 모델의 품질이 아니라 <b>경계</b>다 — 모델이
 * 무엇을 돌려주든 서버의 판단이 흔들리지 않는가.
 */
class SubtaskNormalizationTest {

    private MockWebServer server;
    private OpenAiService llm;

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final JangnyangSubtaskDefinition def = catalog.latest();

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        llm = new OpenAiService();
        ReflectionTestUtils.setField(llm, "apiUrl", server.url("/v1/chat/completions").toString());
        ReflectionTestUtils.setField(llm, "apiKey", "test-key");
        ReflectionTestUtils.setField(llm, "model", "test-model");
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    /** OpenAI 호환 응답 한 건을 큐에 넣는다. */
    private void enqueueContent(String content) {
        String body = "{\"choices\":[{\"message\":{\"content\":"
                + jsonString(content) + "}}]}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private Object normalize(String subtaskId, String userText) {
        JangnyangSubtask st = def.byId(subtaskId);
        return llm.normalizeToField(st.answerField(), SubtaskSessionService.fieldSpecFor(st), userText);
    }

    @Test
    @DisplayName("UT-328 '아침 여덟시 반쯤' → collectionTime=08:30 — 현재 서브태스크의 필드 하나만 채운다")
    void normalizesSingleField() {
        enqueueContent("{\"collectionTime\":\"08:30\"}");
        assertEquals("08:30", normalize("ST-020", "아침 여덟시 반쯤에 수거하면 좋겠어요"));

        // 정규화한 값이 검증기를 그대로 통과해야 두 단계가 이어진다.
        var r = new JangnyangSubtaskValidator().validate(def, Map.of("ST-020", "08:30"), Map.of());
        assertTrue(r.valid());
        assertEquals(510, r.accepted().get("ST-020").value());
    }

    @Test
    @DisplayName("UT-329 LLM이 다른 필드까지 채운 JSON을 돌려줘도 현재 서브태스크의 필드만 취한다")
    void otherFieldsAreDiscarded() {
        // 모델이 시키지 않은 필드를 함께 채워 보내는 것은 실제로 겪은 행동이다(FR-21).
        enqueueContent("{\"collectionTime\":\"08:30\",\"days\":365,\"truckType\":\"SMALL_1TON\","
                + "\"trafficEnabled\":true,\"seeds\":100}");
        Object value = normalize("ST-020", "아침 여덟시 반");

        // 돌려받은 것은 <b>값 하나</b>다 — 다른 필드가 담길 그릇 자체가 없다.
        assertEquals("08:30", value);
        assertFalse(value instanceof Map, "필드 묶음이 통째로 넘어오면 침범을 막을 수 없다");

        // 세션에 넣어도 다른 항목은 여전히 비어 있어야 한다.
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k");
        sessions.submit("k", "ST-020", value, null);
        Map<String, JangnyangSubtaskAnswer> answers = sessions.store().find("k").answers();
        assertEquals(1, answers.size());
        assertNull(answers.get("ST-006"), "days가 조용히 365로 채워지면 안 된다");
        assertNull(answers.get("ST-024"));
        assertNull(answers.get("ST-029"));
    }

    @Test
    @DisplayName("맵으로 답하는 항목은 객체 그대로 넘어온다 — 빈 값으로 뭉개지지 않는다")
    void mapValuedAnswersSurviveNormalization() {
        // ObjectNode.asText()가 빈 문자열을 돌려주는 탓에 맵 답변이 통째로 사라지고,
        // 사용자가 같은 질문을 무한히 다시 받는 결함이 있었다(라이브 확인).
        enqueueContent("{\"residentsPerBuildingMap\":{\"Node_A\":25,\"Node_B\":25}}");
        Object v = normalize("ST-010", "건물마다 스물다섯 명씩이요");
        assertInstanceOf(Map.class, v, "객체가 빈 문자열로 뭉개지면 안 된다");
        assertEquals(2, ((Map<?, ?>) v).size());

        // 검증기까지 이어져야 두 단계가 실제로 붙어 있는 것이다.
        var r = new JangnyangSubtaskValidator().validate(def, Map.of("ST-010", v), Map.of());
        assertTrue(r.valid(), () -> "정규화한 맵이 검증에서 걸렸다: " + r.errors());

        // 비율 맵도 마찬가지다.
        enqueueContent("{\"occupationRatios\":{\"BlueCollar\":0.5,\"Student\":0.5}}");
        Object ratios = normalize("ST-011", "생산직 절반 학생 절반");
        assertInstanceOf(Map.class, ratios);
        assertTrue(new JangnyangSubtaskValidator()
                .validate(def, Map.of("ST-011", ratios), Map.of()).valid());
    }

    @Test
    @DisplayName("UT-330 LLM 응답에 질문 문장이 들어 있어도 사용자에게 나가는 문장은 카탈로그의 것")
    void questionsCannotBeRewrittenByTheModel() {
        // 모델이 질문을 다시 써서 돌려주는 상황을 그대로 재현한다.
        enqueueContent("{\"collectionTime\":\"08:30\","
                + "\"question\":\"제가 대신 여쭤볼게요! 몇 시에 치울까요~?\","
                + "\"retryQuestion\":\"다시 알려주세요 :)\"}");

        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k");
        Object value = normalize("ST-020", "아침 여덟시 반");
        SubtaskSessionService.Step step = sessions.submit("k", "ST-020", value, null);

        // 다음에 나갈 질문은 카탈로그의 문장이다 — 모델이 보낸 문장이 닿을 자리가 없다.
        JangnyangSubtask next = step.question();
        assertNotNull(next);
        assertEquals(def.byId(next.id()).question(), next.question());
        assertFalse(next.question().contains("치울까요"));
        assertFalse(next.retryQuestion().contains(":)"));

        // 세트 자체도 그대로다.
        assertEquals(catalog.latest().hash(), def.hash());
    }

    @Test
    @DisplayName("UT-331 LLM이 '이제 충분합니다'라고 해도 서버 판정이 미충족이면 실행되지 않는다")
    void modelCannotDeclareCompleteness() {
        enqueueContent("{\"simulationGoal\":\"목적\",\"complete\":true,"
                + "\"message\":\"이제 충분합니다. 바로 실행하셔도 됩니다.\"}");

        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k");
        Object value = normalize("ST-001", "민원 줄이기");
        sessions.submit("k", "ST-001", value, null);

        // 아직 답하지 않은 질문이 많으므로 조립도 실행도 열리지 않는다.
        assertEquals(SubtaskState.COLLECTING, sessions.store().find("k").state());
        assertFalse(sessions.build("k").ok());
        assertNull(sessions.approveRun("k"), "모델의 선언으로 실행이 열리면 fail-closed가 무너진다");
        assertFalse(sessions.progress("k").progress() >= 1.0);
    }

    @Test
    @DisplayName("UT-332 LLM이 null·오류를 돌려줘도 질문 전달과 진행 상태는 정상 동작한다")
    void llmFailureDoesNotStopCollection() {
        // 백엔드 장애(500)와 형식 위반(JSON 아님)을 차례로 겪게 한다.
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        assertNull(normalize("ST-020", "아침 여덟시 반"), "실패는 null로 표현된다(예외를 던지지 않는다)");

        enqueueContent("죄송합니다, 무슨 말인지 모르겠어요.");
        assertNull(normalize("ST-020", "아침 여덟시 반"));

        // 정규화가 실패해도 수집 자체는 계속된다 — 호출부가 원문을 그대로 검증기에
        // 넘기고, 검증기가 판정한다.
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k");
        // ST-020을 대상으로 명시해 답한다 — 도착 순서가 아니라 대상이 자리를 정한다.
        SubtaskSessionService.Step step = sessions.submit("k", "ST-020", "아침 여덟시 반쯤", null);
        assertTrue(step.ok(), "LLM이 죽어도 요청 자체가 거부되면 안 된다");
        assertFalse(step.errors().isEmpty(), "원문이 형식에 안 맞으면 검증기가 잡는다");
        assertEquals(def.byId("ST-020").retryQuestion(), step.errors().get(0).retryQuestion());
        assertNotNull(step.progress(), "진행 상태는 정상적으로 나와야 한다");
        // 재질문이므로 진행률이 오르지 않고 같은 항목에 머문다.
        // 재질문이므로 ST-020은 아직 비어 있고, 세션은 첫 미답 질문에 머문다.
        assertEquals("ST-001", step.progress().currentSubtaskId());
        assertTrue(step.progress().answers().isEmpty());

        // 형식에 맞는 원문은 LLM 없이도 그대로 통과한다.
        SubtaskSessionService.Step direct = sessions.submit("k", "ST-020", "08:30", null);
        assertTrue(direct.errors().isEmpty());
        assertEquals(510, sessions.store().find("k").answers().get("ST-020").value());
    }
}
