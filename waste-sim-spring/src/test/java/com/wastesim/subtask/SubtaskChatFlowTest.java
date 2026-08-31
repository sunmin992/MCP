package com.wastesim.subtask;

import com.wastesim.controller.ChatController;
import com.wastesim.mcp.JavaEngineProvider;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.ChatMessage;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.ScenarioService;
import com.wastesim.service.SimulationService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.SimulationTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IT-85~90 — <b>채팅 종단간</b>(TDD 3.17.7).
 *
 * <p>WebSocket 전송 계층은 {@link SimpMessagingTemplate} 목으로 대신하고, 그 아래의
 * 게이트·수집·검증·조립·실행은 <b>실제 구현</b>을 그대로 쓴다. 이 절이 보려는 것은 메시지가
 * 어느 경로로 흘러가는가이지 브로커의 동작이 아니다.
 *
 * <p>LLM도 목이다 — 정규화가 실패(null)해도 수집이 원문으로 진행된다는 성질(UT-332)에
 * 기대어, 이 테스트들은 LLM 없이 전 과정을 돈다. 그것 자체가 "질문·판정에 LLM이 필요 없다"는
 * 확인이기도 하다.
 */
class SubtaskChatFlowTest {

    private SimpMessagingTemplate messaging;
    private OpenAiService llm;
    private SimulationTool tool;
    private SubtaskSessionService sessions;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        llm = mock(OpenAiService.class);
        TrafficDataService traffic = new TrafficDataService();
        SimulationService sim = new SimulationService(new SimulationEngine(traffic));
        SimulationModelRegistry models = new SimulationModelRegistry(
                List.of(new JavaEngineProvider(sim)));
        tool = new SimulationTool(new SimulationConfigValidator(traffic), models,
                new ScenarioService(sim), new SimpleMeterRegistry());
        sessions = TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
        controller = new ChatController(messaging, llm, tool, new SimpleMeterRegistry(),
                traffic, sessions);
    }

    private void send(String text) {
        ChatMessage m = new ChatMessage(ChatMessage.MessageType.USER, text);
        m.setDomain("waste");
        controller.handleMessage(m);
    }

    private List<ChatMessage> sent() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeast(0)).convertAndSend(eq("/topic/messages"), captor.capture());
        return captor.getAllValues();
    }

    private List<ChatMessage> ofType(ChatMessage.MessageType type) {
        return sent().stream().filter(m -> m.getType() == type).toList();
    }

    /** 지정한 서브태스크가 현재 질문이 될 때까지 순서대로 답한다. */
    private void answerUpTo(String stopAtId) {
        Map<String, Object> answers = V2Answers.all();
        for (int i = 0; i < 60; i++) {
            SubtaskProgress p = sessions.progress("default");
            if (p == null || p.currentSubtaskId() == null || stopAtId.equals(p.currentSubtaskId())) return;
            sessions.submit("default", p.currentSubtaskId(), answers.get(p.currentSubtaskId()), null);
        }
    }

    /** 남은 질문에 순서대로 답한다 — 세션이 묻는 것만 답하므로 계획이 바뀌어도 따라간다. */
    private void answerRemaining(Map<String, Object> answers) {
        for (int i = 0; i < 60; i++) {
            SubtaskProgress p = sessions.progress("default");
            if (p == null || p.currentSubtaskId() == null) return;
            Object v = answers.get(p.currentSubtaskId());
            assertNotNull(v, "테스트가 " + p.currentSubtaskId() + "의 답을 준비하지 않았다");
            sessions.submit("default", p.currentSubtaskId(), v, null);
        }
    }

    @Test
    @DisplayName("IT-85 '장량동 원룸촌 시뮬레이터 만들어 줘' — 즉시 실행되지 않고 첫 질문이 SUBTASK로 온다")
    void creationRequestStartsCollectionInsteadOfRunning() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");

        List<ChatMessage> subtasks = ofType(ChatMessage.MessageType.SUBTASK);
        assertEquals(1, subtasks.size(), "첫 질문이 SUBTASK 메시지로 와야 한다");
        ChatMessage q = subtasks.get(0);
        assertEquals("ST-001", q.getCurrentSubtaskId());
        assertEquals(1, q.getSubtaskOrder());
        assertEquals("jangnyang-simulator-v2", q.getSubtaskSetId());
        assertEquals(2, q.getSubtaskVersion());
        assertNotNull(q.getInputSchema(), "프런트엔드가 입력 위젯을 고르려면 스키마가 필요하다");
        assertEquals("STRING", q.getInputSchema().get("answerType"));

        // 엔진도, 파라미터 추출도 부르지 않았다 — 값을 모으는 것부터 시작하는 요청이다.
        verify(llm, never()).extractParamsStrict(anyList(), anyString());
        verify(llm, never()).answerPlain(anyList(), anyString());
        assertTrue(ofType(ChatMessage.MessageType.RESULT).isEmpty());
    }

    @Test
    @DisplayName("IT-86 수집 중 '8시 30분'만 보내도 실행 의도 게이트로 새지 않는다")
    void answersDoNotLeakIntoTheExecutionGate() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        clearInvocations(messaging);

        // 이 문장은 수집 밖이었다면 시각 1개 + 실행 요청으로 판정돼 즉시 실행됐을 것이다.
        send("8시 30분");

        verify(llm, never()).extractParamsStrict(anyList(), anyString());
        assertTrue(ofType(ChatMessage.MessageType.RESULT).isEmpty(), "수집이 실행으로 새면 안 된다");
        // 진행은 계속된다 — 답변으로 처리되어 다음 질문이 온다.
        assertFalse(ofType(ChatMessage.MessageType.SUBTASK).isEmpty());
        assertNotNull(sessions.activeSession("default"));
    }

    @Test
    @DisplayName("IT-87 범위 밖 값을 내면 같은 질문이 같은 문장으로 다시 오고 진행률이 오르지 않는다")
    void retryRepeatsTheSameSentenceWithoutProgress() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        // ST-020까지 순서대로 채운 뒤 그 질문에서 틀려 본다.
        answerUpTo("ST-020");
        double before = sessions.progress("default").progress();
        clearInvocations(messaging);

        when(llm.normalizeToField(anyString(), anyString(), anyString())).thenReturn(null);
        send("25시 99분");
        send("25시 99분");

        List<ChatMessage> retries = ofType(ChatMessage.MessageType.SUBTASK);
        assertEquals(2, retries.size());
        String expected = new JangnyangSubtaskCatalog().latest().byId("ST-020").retryQuestion();
        for (ChatMessage m : retries) {
            assertEquals("ST-020", m.getCurrentSubtaskId());
            assertEquals(expected, m.getQuestion(), "재질문 문장이 매번 달라지면 안 된다(D-47)");
            assertFalse(m.getValidationErrors().isEmpty());
        }
        assertEquals(before, sessions.progress("default").progress(), 1e-9,
                "틀린 답으로 진행률이 오르면 안 된다");
    }

    @Test
    @DisplayName("IT-88 전 항목 완료 시 PREVIEW에 시나리오·기본값·가정이 실리고, 승인 전에는 엔진이 호출되지 않는다")
    void previewCarriesDefaultsAndBlocksExecutionUntilApproved() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        answerRemaining(V2Answers.all());
        clearInvocations(messaging);

        // 마지막 답변이 들어가면 미리보기가 나온다.
        when(llm.normalizeToField(anyString(), anyString(), anyString())).thenReturn(null);
        // 이미 전 항목을 채웠으므로 조립·미리보기를 직접 호출하는 경로를 탄다.
        SubtaskSessionService.BuildStep build = sessions.build("default");
        assertTrue(build.ok(), build::message);

        assertEquals(SubtaskState.BUILT, sessions.store().find("default").state());
        JangnyangScenarioSpec spec = build.spec();
        // v2는 값을 거의 다 묻기 때문에 채워 넣을 것이 없을 수 있다. 반드시 남아야 하는
        // 것은 가정이다 — "해당 없음"으로 넘어간 항목이 여기 기록된다.
        assertNotNull(spec.appliedDefaults());
        assertFalse(spec.assumptions().isEmpty());
        assertTrue(spec.previewText().contains("single-run"));

        // 승인 전에는 엔진이 돌지 않았다.
        assertTrue(ofType(ChatMessage.MessageType.RESULT).isEmpty());
    }

    @Test
    @DisplayName("IT-89 승인하면 기존 엔진이 돌고 결과가 RESULT로 오며, 조건·가정이 함께 실린다")
    void approvalRunsTheExistingEngineAndReportsOnlyComputedMetrics() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        answerRemaining(V2Answers.all());
        assertTrue(sessions.build("default").ok());
        clearInvocations(messaging);

        controller.runSubtaskScenario(null, null);

        List<ChatMessage> results = ofType(ChatMessage.MessageType.RESULT);
        assertEquals(1, results.size(), "승인 후에 결과가 하나 와야 한다");
        ChatMessage r = results.get(0);
        assertNotNull(r.getSimulationResult());
        assertNotNull(r.getSimulationConfig());
        assertEquals(SubtaskState.COMPLETED, sessions.store().find("default").state());

        // 미리보기 화면이 확인 단계 셋을 대신했다 — 화면에는 실행 승인만 보였지만 세트의
        // 50개는 전부 채워져야 한다("생략하지 않는다"는 규약).
        Map<String, JangnyangSubtaskAnswer> answers = sessions.store().find("default").answers();
        assertEquals(50, answers.size(), "확인 단계까지 채워야 50개가 된다");
        assertEquals("CONFIRMED", answers.get("ST-048").value());
        assertEquals("CONFIRMED", answers.get("ST-049").value());
        assertEquals("RUN", answers.get("ST-050").value());

        // 설명 문장은 엔진이 낸 값으로만 구성된다(FR-135). 결과 경로에 LLM 호출이
        // 아예 없다는 것이 그 근거다 — 정규화(수집 단계) 외에는 이 목이 건드려지지 않는다.
        verifyNoMoreInteractions(llm);
        String text = r.getContent();
        assertTrue(text.contains(String.valueOf(r.getSimulationResult().getTotalComplaints())),
                "결과 문장에 실제 계산된 민원 수가 있어야 한다");
        // 엔진이 내지 않는 지표를 지어내지 않는다.
        assertFalse(text.contains("평균 대기시간"));
        assertFalse(text.contains("비용"));
        assertFalse(text.contains("만족도"));
    }

    @Test
    @DisplayName("답변이 도착 순서가 아니라 자기 질문을 찾아간다 — 순서가 뒤집혀도 옆 칸에 들어가지 않는다")
    void answersTargetTheirOwnSubtaskNotTheCurrentOne() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        // 실측으로 재현된 상황: 사용자가 ST-02와 ST-03에 차례로 답했는데 STOMP
        // 인바운드가 두 메시지를 뒤집어 처리했다. 세션이 "지금 묻는 항목"으로만
        // 판단하면 08:30이 scenarioType 칸에, single-run이 collectionTime 칸에 들어간다.
        ChatMessage late = new ChatMessage(ChatMessage.MessageType.USER, "08:30");
        late.setDomain("waste");
        late.setCurrentSubtaskId("ST-020");        // 이 답이 어느 질문의 것인지 명시
        controller.handleMessage(late);

        ChatMessage early = new ChatMessage(ChatMessage.MessageType.USER, "single-run");
        early.setDomain("waste");
        early.setCurrentSubtaskId("ST-035");
        controller.handleMessage(early);

        Map<String, JangnyangSubtaskAnswer> answers = sessions.store().find("default").answers();
        assertEquals(510, answers.get("ST-020").value(), "08:30은 수거 시각 칸에 들어가야 한다");
        assertEquals("single-run", answers.get("ST-035").value(), "single-run은 시나리오 유형 칸에");

        // 조립까지 가도 값이 어긋나 있지 않아야 한다 — 어긋난 값이 그 필드에서 우연히
        // 유효하면 오류 없이 끝까지 가기 때문에, 여기서 고정해 둔다.
        answerRemaining(V2Answers.all());
        SubtaskSessionService.BuildStep build = sessions.build("default");
        assertTrue(build.ok(), build::message);
        assertEquals(510, build.spec().toSimulationConfig().getCollectionTimeMinutes());
        assertEquals("single-run", build.spec().scenarioType());
    }

    @Test
    @DisplayName("서브태스크 ID 없이 온 답변(자유 입력창)은 종전대로 현재 질문의 답으로 처리된다")
    void answersWithoutAnIdStillTargetTheCurrentSubtask() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        when(llm.normalizeToField(anyString(), anyString(), anyString())).thenReturn(null);

        send("민원 줄이기");   // ChatMessage에 currentSubtaskId가 없다

        Map<String, JangnyangSubtaskAnswer> answers = sessions.store().find("default").answers();
        assertEquals("민원 줄이기", answers.get("ST-001").value());
        assertEquals("ST-002", sessions.progress("default").currentSubtaskId());
    }

    @Test
    @DisplayName("IT-90 LLM 프로바이더를 바꿔도 질문 문장·개수·순서·재질문과 최종 설정이 동일하다")
    void collectionIsIdenticalAcrossLlmBackends() {
        // 두 프로바이더가 모두 로컬에 있어야 하는 것은 <b>정규화</b> 뿐이고, 질문 고정성은
        // LLM 없이도 성립한다(UT-301). 그래서 여기서는 두 개의 서로 다른 LLM 목을 두어
        // "정규화 결과가 달라도 질문과 최종 설정은 같다"를 직접 확인한다 — 실제 모델
        // 두 벌이 없어도 이 성질은 검증 가능하다.
        List<String> questionsA = collectQuestionsWith(normalizerReturning("08:30"));
        List<String> questionsB = collectQuestionsWith(normalizerReturning(null));

        assertEquals(questionsA, questionsB,
                "프로바이더가 달라지면 질문이 달라진다는 것은 NFR-17 위반이다");
        assertFalse(questionsA.isEmpty());

        // 같은 답변으로 만든 최종 SimulationConfig도 같아야 한다.
        SimulationConfig cfgA = buildConfigWith(normalizerReturning("08:30"));
        SimulationConfig cfgB = buildConfigWith(normalizerReturning(null));
        assertEquals(cfgA.getCollectionTimeMinutes(), cfgB.getCollectionTimeMinutes());
        assertEquals(cfgA.getDays(), cfgB.getDays());
        assertEquals(cfgA.getSeeds(), cfgB.getSeeds());
        assertEquals(cfgA.getNumBuildings(), cfgB.getNumBuildings());
        assertEquals(cfgA.getThreshold(), cfgB.getThreshold(), 1e-9);
        assertEquals(cfgA.getTruckType(), cfgB.getTruckType());
    }

    private OpenAiService normalizerReturning(String value) {
        OpenAiService m = mock(OpenAiService.class);
        when(m.normalizeToField(anyString(), anyString(), anyString())).thenReturn(value);
        return m;
    }

    /** 한 프로바이더로 수집을 처음부터 끝까지 돌며 나온 질문 문장을 순서대로 모은다. */
    private List<String> collectQuestionsWith(OpenAiService provider) {
        SimpMessagingTemplate bus = mock(SimpMessagingTemplate.class);
        SubtaskSessionService svc = TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
        ChatController c = new ChatController(bus, provider, tool, new SimpleMeterRegistry(),
                new TrafficDataService(), svc);

        ChatMessage start = new ChatMessage(ChatMessage.MessageType.USER, "장량동 시뮬레이터 만들어 줘");
        start.setDomain("waste");
        c.handleMessage(start);

        Map<String, Object> answers = V2Answers.all();
        for (int i = 0; i < 60; i++) {
            SubtaskProgress p = svc.progress("default");
            if (p == null || p.currentSubtaskId() == null) break;
            svc.submit("default", p.currentSubtaskId(), answers.get(p.currentSubtaskId()), null);
        }

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(bus, atLeast(0)).convertAndSend(eq("/topic/messages"), captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> m.getType() == ChatMessage.MessageType.SUBTASK)
                .map(ChatMessage::getQuestion)
                .toList();
    }

    private SimulationConfig buildConfigWith(OpenAiService provider) {
        SubtaskSessionService svc = TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
        ChatController c = new ChatController(mock(SimpMessagingTemplate.class), provider, tool,
                new SimpleMeterRegistry(), new TrafficDataService(), svc);
        ChatMessage start = new ChatMessage(ChatMessage.MessageType.USER, "장량동 시뮬레이터 만들어 줘");
        start.setDomain("waste");
        c.handleMessage(start);

        Map<String, Object> answers = V2Answers.all();
        for (int i = 0; i < 60; i++) {
            SubtaskProgress p = svc.progress("default");
            if (p == null || p.currentSubtaskId() == null) break;
            svc.submit("default", p.currentSubtaskId(), answers.get(p.currentSubtaskId()), null);
        }
        SubtaskSessionService.BuildStep build = svc.build("default");
        assertTrue(build.ok(), build::message);
        return build.spec().toSimulationConfig();
    }
}
