package com.wastesim.controller;

import com.wastesim.model.ChatMessage;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DESIGN_DECISIONS.md D-04 · D-05 — 현재 단일 'default' 세션에서 확인 대기
 * 설정이 겹칠 때의 동작(최신 요청으로 덮어쓰고, 이전 요청은 폐기됐다고
 * 안내)을 고정한다. 세션 분리가 구현되면 이 테스트는 "세션 A의
 * pendingConfig가 세션 B에 안 보인다"는 격리 테스트로 교체한다.
 */
class ChatControllerTest {

    @Test
    void newConfirmRequestOverwritesPendingAndNotifiesDiscard() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        TrafficDataService trafficData = new TrafficDataService();

        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData);

        // SimulationConfig.getCollectionTimeLabel()은 collectionTimeMinutes를
        // 매번 "%02d:%02d"로 재포맷하므로("8:30"→setter가 파싱 후 getter가
        // "08:30"으로 정규화) 한 자리 시로는 cfgToConfirm 분기를 재현할 수
        // 없다 — 시(hour)가 0~23 범위를 벗어나야(가능성은 낮지만 파싱 자체는
        // 성공하는 값) isValidCollectionTime이 false가 되어 확인 대기로
        // 빠진다.
        SimulationConfig cfgA = new SimulationConfig();
        cfgA.setCollectionTimeLabel("25:30");   // 시 범위 초과 → 확인 대기 분기(cfgToConfirm)
        cfgA.setDays(30);
        cfgA.setSeeds(30);

        SimulationConfig cfgB = new SimulationConfig();
        cfgB.setCollectionTimeLabel("26:15");
        cfgB.setDays(30);
        cfgB.setSeeds(30);

        when(openAiService.extractParamsStrict(anyList(), anyString()))
                .thenReturn(cfgA)
                .thenReturn(cfgB);

        controller.handleMessage(userMsg("12시에 수거하는 걸로 실행해줘"));  // pendingConfigs[default] = cfgA
        controller.handleMessage(userMsg("1시에 수거하는 걸로 실행해줘"));   // cfgB로 덮어씀 + 폐기 안내

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/messages"), captor.capture());
        boolean discardNoticeSent = captor.getAllValues().stream()
                .anyMatch(m -> m.getType() == ChatMessage.MessageType.SYSTEM
                        && m.getContent() != null && m.getContent().contains("폐기"));
        assertTrue(discardNoticeSent, "이전 대기 설정 폐기 안내가 전송되어야 한다(D-04)");

        // 확인 시 최신(cfgB)이 실행된다 — D-05: 단일 세션이라 이전 요청(cfgA)은 남지 않는다.
        // ChatController는 이제 항상 3-인자(SimulationTool#runSimulation(cfg, modelId,
        // skipWarnings))로 호출한다 — 여기서는 두 메시지 모두 엔진을 지정하지
        // 않았으므로 modelId는 null(기본 모델).
        when(tool.runSimulation(any(), isNull(), eq(true))).thenReturn(ToolResult.ok(new SimulationResult()));
        controller.confirmRun();

        ArgumentCaptor<SimulationConfig> cfgCaptor = ArgumentCaptor.forClass(SimulationConfig.class);
        verify(tool).runSimulation(cfgCaptor.capture(), isNull(), eq(true));
        assertEquals("26:15", cfgCaptor.getValue().getCollectionTimeLabel());
    }

    /** EngineSelectionDetector가 잡아내는 "파이썬 엔진으로" 같은 요청이 run_waste_simulation_devs
     *  (modelId="python-devs")로 라우팅되는지 확인한다. */
    @Test
    void routesToPythonEngineWhenMentionedInMessage() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        TrafficDataService trafficData = new TrafficDataService();

        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData);

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");
        cfg.setDays(30);
        cfg.setSeeds(30);
        when(openAiService.extractParamsStrict(anyList(), anyString())).thenReturn(cfg);
        when(tool.runSimulation(any(), eq("python-devs"), eq(false)))
                .thenReturn(ToolResult.ok(new SimulationResult()));

        controller.handleMessage(userMsg("파이썬 엔진으로 12시에 실행해줘"));

        verify(tool).runSimulation(any(), eq("python-devs"), eq(false));
    }

    /** 엔진을 언급하지 않으면 기존과 동일하게 modelId=null(기본 Java 엔진)로 실행돼야 한다. */
    @Test
    void defaultsToNullModelIdWhenEngineNotMentioned() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        TrafficDataService trafficData = new TrafficDataService();

        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData);

        SimulationConfig cfg = new SimulationConfig();
        cfg.setCollectionTimeLabel("12:00");
        cfg.setDays(30);
        cfg.setSeeds(30);
        when(openAiService.extractParamsStrict(anyList(), anyString())).thenReturn(cfg);
        when(tool.runSimulation(any(), isNull(), eq(false)))
                .thenReturn(ToolResult.ok(new SimulationResult()));

        controller.handleMessage(userMsg("12시에 실행해줘"));

        verify(tool).runSimulation(any(), isNull(), eq(false));
    }

    /**
     * "Node_A, Node_C, Node_B, Node_D 순서로 방문하면 얼마나 걸려?"류의 경로
     * 소요시간 질의는 전체 시뮬레이션(LLM 파라미터 추출 + SimulationTool.runSimulation)을
     * 거치지 않고 바로 근사값으로 답해야 한다.
     */
    @Test
    void routeDurationQueryAnswersWithoutRunningFullSimulation() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        TrafficDataService trafficData = new TrafficDataService();

        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData);

        controller.handleMessage(userMsg("Node_A, Node_C, Node_B, Node_D 순서로 방문하면 얼마나 걸려?"));

        verify(openAiService, never()).extractParamsStrict(anyList(), anyString());
        verify(tool, never()).runSimulation(any(), any(), anyBoolean());

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/messages"), captor.capture());
        String botReply = captor.getAllValues().stream()
                .filter(m -> m.getType() == ChatMessage.MessageType.BOT)
                .map(ChatMessage::getContent)
                .findFirst().orElse(null);

        assertNotNull(botReply);
        assertTrue(botReply.contains("Node_A → Node_C → Node_B → Node_D"));
        assertTrue(botReply.contains("근사값"));
    }

    /** 방문 순서나 수거 시각이 달라지면 답변(소요시간)도 그에 맞춰 달라져야 한다. */
    @Test
    void routeDurationReplyChangesWithOrderAndTime() {
        TrafficDataService trafficData = new TrafficDataService();

        String replyAbcd = routeDurationReply(trafficData, "Node_A, Node_C, Node_B, Node_D 순서로 13시에 방문하면 얼마나 걸려?");
        String replyAbdc = routeDurationReply(trafficData, "Node_A, Node_C, Node_D, Node_B 순서로 13시에 방문하면 얼마나 걸려?");
        assertNotEquals(replyAbcd, replyAbdc, "방문 순서가 다르면 응답도 달라져야 한다");

        String replyAt3am = routeDurationReply(trafficData, "Node_A, Node_C, Node_B, Node_D 순서로 3시에 방문하면 얼마나 걸려?");
        assertNotEquals(replyAbcd, replyAt3am, "수거 시각이 다르면 응답도 달라져야 한다");
    }

    private String routeDurationReply(TrafficDataService trafficData, String userText) {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData);

        controller.handleMessage(userMsg(userText));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/messages"), captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> m.getType() == ChatMessage.MessageType.BOT)
                .map(ChatMessage::getContent)
                .findFirst().orElse(null);
    }

    private ChatMessage userMsg(String text) {
        return new ChatMessage(ChatMessage.MessageType.USER, text);
    }
}
