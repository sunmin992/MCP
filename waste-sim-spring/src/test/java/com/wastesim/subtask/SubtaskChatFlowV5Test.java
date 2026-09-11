package com.wastesim.subtask;

import com.wastesim.controller.ChatController;
import com.wastesim.mcp.JavaEngineProvider;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.ChatMessage;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * C1 회귀 — v5(유도본)로도 {@link SubtaskChatFlowTest}와 같은 종단 흐름
 * (수집 → 조립 → 미리보기 → 승인)이 성립하는가.
 *
 * <p><b>왜 이 파일이 필요한가</b>: 유일한 종단 흐름 테스트였던 {@code SubtaskChatFlowTest}는
 * {@code v3Catalog()}로 {@code latest()}를 v3에 고정해 두고, Task 11이 추가한 패키지 전용
 * 생성자({@code JangnyangSubtaskCatalog(String...)})는 일부러 v5를 끼워 넣지 않는다 — 그
 * 결과 v5가 확인 단계(CONFIRM)를 통째로 잃어 "승인 → 조립 → 미리보기"로 순서가 뒤집힌
 * 결함(C1)이 어느 스위트에도 걸리지 않았다. 이 테스트는 기본 생성자
 * ({@code new JangnyangSubtaskCatalog()})를 써서 {@code latest()}가 v5를 가리키게 하고,
 * 그 경로로 같은 흐름을 밟는다.
 *
 * <p>v5는 order·ID가 파일이 아니라 트리에서 유도되므로 v3처럼 "ST-016"을 하드코딩할 수
 * 없다 — 대신 <b>답변 필드명</b>으로 값을 찾아 지금 묻는 질문에 답한다.
 */
class SubtaskChatFlowV5Test {

    private SimpMessagingTemplate messaging;
    private OpenAiService llm;
    private SubtaskSessionService sessions;
    private ChatController controller;
    private JangnyangSubtaskDefinition v5;

    @BeforeEach
    void setUp() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        v5 = catalog.latest();
        assertEquals(5, v5.version(), "이 테스트는 latest()가 v5를 가리키는 것을 전제로 한다");

        messaging = mock(SimpMessagingTemplate.class);
        llm = mock(OpenAiService.class);
        TrafficDataService traffic = new TrafficDataService();
        SimulationService sim = new SimulationService(new SimulationEngine(traffic));
        SimulationModelRegistry models = new SimulationModelRegistry(
                List.of(new JavaEngineProvider(sim)));
        SimulationTool tool = new SimulationTool(new SimulationConfigValidator(traffic), models,
                new ScenarioService(sim), new SimpleMeterRegistry());
        sessions = TestSubtaskFixtures.service(catalog);
        controller = new ChatController(messaging, llm, tool, new SimpleMeterRegistry(),
                traffic, sessions, com.wastesim.site.CollectionSiteRegistry.empty());
    }

    /**
     * 답변 필드명 → 값. 단일 실행(single-run) 기준선이고, 실제로 쓰이지 않는 항목은
     * "해당 없음"으로 채운다("관련 없는 항목도 생략하지 않고 묻는다"는 v2 규약이 v5에도
     * 그대로 적용된다).
     */
    private static Map<String, Object> answersByField() {
        Map<String, Object> a = new LinkedHashMap<>();
        String na = JangnyangSubtaskValidator.NOT_APPLICABLE;
        a.put("simulationGoal", "v5 종단 흐름 확인용 목적 문장");
        a.put("scenarioType", "single-run");
        a.put("engine", "java");
        a.put("numBuildings", 10);
        a.put("residentsPerBuilding", 10);
        a.put("occupationPreset", "BALANCED");
        a.put("days", 7);
        a.put("seeds", 3);
        a.put("wasteMeanKg", 0.9);
        a.put("wasteSigma", 0.3);
        a.put("leaveSigma", 30);
        a.put("dischargeTimeMode", "PAPER_BASELINE");
        a.put("dischargeWindow", na);
        a.put("capacity", 60);
        a.put("threshold", 0.8);
        a.put("collectionTime", "12:00");
        a.put("collectionTimes", na);
        a.put("collectionSchedule", "EVERY_DAY");
        a.put("truckType", "LARGE_5TON");
        a.put("truckCount", 1);
        a.put("routeAvailableCapacityKg", 900);
        a.put("initialTruckLoadKg", 0);
        a.put("dispatchIntervalMinutes", na);
        a.put("trafficMode", "NONE");
        a.put("trafficProfileId", na);
        a.put("travelTimeMode", "LEGACY_CONSTANT");
        a.put("routeTravelMinutes", 15);
        a.put("serviceMinutesPerSite", na);
        a.put("intraZoneTravelMinutes", na);
        a.put("zoneAssignmentRule", na);
        a.put("routeSequence", na);
        a.put("defaultApproval", "ALL");
        return a;
    }

    private void send(String text) {
        ChatMessage m = new ChatMessage(ChatMessage.MessageType.USER, text);
        m.setDomain("waste");
        controller.handleMessage(m);
    }

    /** 세션이 지금 묻는 질문에 순서대로 답한다 — ID가 아니라 답변 필드명으로 값을 찾는다. */
    private void answerAllRemaining() {
        Map<String, Object> byField = answersByField();
        for (int i = 0; i < 60; i++) {
            SubtaskProgress p = sessions.progress("default");
            if (p == null || p.currentSubtaskId() == null) return;
            JangnyangSubtask st = v5.byId(p.currentSubtaskId());
            assertNotNull(st, p.currentSubtaskId() + "가 v5 세트에 없다");
            Object v = byField.get(st.answerField());
            assertNotNull(v, "테스트가 " + st.answerField() + "의 답을 준비하지 않았다");
            sessions.submit("default", p.currentSubtaskId(), v, null);
        }
    }

    private List<ChatMessage> capturedMessagesOfType(ChatMessage.MessageType type) {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeast(0)).convertAndSend(eq("/topic/messages"), captor.capture());
        return captor.getAllValues().stream().filter(m -> m.getType() == type).toList();
    }

    @Test
    @DisplayName("v5도 COLLECT 32개를 다 채우면 더 물을 것이 없고, 승인 전에는 조립 결과가 실행되지 않는다(C1)")
    void collectionStopsBeforeConfirmStageAndBlocksExecution() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        answerAllRemaining();

        // C1이 고쳐지기 전에는 CONFIRM 두 개(inputAndScenarioConfirmed·executionApproval)가
        // COLLECT로 잘못 분류돼 있어, 여기서 "미리보기를 확인하셨습니까"가 다음 질문으로
        // 나왔다 — 미리보기가 뜨기도 전에 승인부터 요구한 것이다.
        SubtaskProgress p = sessions.progress("default");
        assertNull(p.currentSubtaskId(), "COLLECT 32개를 다 채웠으면 더 물을 질문이 없어야 한다");

        SubtaskSessionService.BuildStep build = sessions.build("default");
        assertTrue(build.ok(), build::message);
        assertEquals(SubtaskState.BUILT, sessions.store().find("default").state());
        assertNotNull(build.spec());

        // 승인 전에는 엔진이 돌지 않았다(FR-133).
        assertTrue(capturedMessagesOfType(ChatMessage.MessageType.RESULT).isEmpty());
    }

    @Test
    @DisplayName("v5도 승인하면 확인 단계 둘이 함께 기록되고 엔진이 실제로 돈다(C1)")
    void approvalRecordsConfirmationsAndRunsTheEngine() {
        send("장량동 원룸촌 시뮬레이터 만들어 줘");
        answerAllRemaining();
        assertTrue(sessions.build("default").ok());

        controller.runSubtaskScenario(null, null);

        List<ChatMessage> results = capturedMessagesOfType(ChatMessage.MessageType.RESULT);
        assertEquals(1, results.size(), "승인 후 결과가 하나 와야 한다");
        assertEquals(SubtaskState.COMPLETED, sessions.store().find("default").state());

        // 미리보기 화면이 확인 단계 둘을 대신 채웠다 — "34개를 생략 없이 채운다"는
        // 규약(SES 29 + 밖 1 + 절차 4)이 v5에서도 지켜지는가를 본다.
        Map<String, JangnyangSubtaskAnswer> answers = sessions.store().find("default").answers();
        assertEquals(34, answers.size(), "확인 단계까지 채워야 34개가 된다");

        JangnyangSubtask confirmed = v5.byAnswerField("inputAndScenarioConfirmed");
        JangnyangSubtask executed = v5.byAnswerField("executionApproval");
        assertEquals(SubtaskStage.CONFIRM, confirmed.stage(), "C1 — v4처럼 CONFIRM이어야 한다");
        assertEquals(SubtaskStage.CONFIRM, executed.stage(), "C1 — v4처럼 CONFIRM이어야 한다");
        assertEquals("CONFIRMED", answers.get(confirmed.id()).value());
        assertEquals("RUN", answers.get(executed.id()).value());
    }
}
