package com.wastesim.controller;

import com.wastesim.edge.EdgeThermalProfileStore;
import com.wastesim.mcp.McpToolRegistry;
import com.wastesim.model.ChatMessage;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

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
    void comparesEachExplicitCollectionTimeInsteadOfFallingBackToPlainAnswer() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), new TrafficDataService(),
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

        ScenarioResponse response = new ScenarioResponse(
                "COLLECTION_TIME_COMPARISON", "지정 수거 시각 비교", "수거 시각");
        when(tool.compareCollectionTimes(any(), eq(List.of(600, 660))))
                .thenReturn(ToolResult.ok(response));

        controller.handleMessage(userMsg("10시와 11시에 각각 수거해줘"));

        verify(tool).compareCollectionTimes(any(), eq(List.of(600, 660)));
        verify(openAiService, never()).answerPlain(anyList(), anyString());
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/messages"), captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m ->
                m.getType() == ChatMessage.MessageType.SCENARIO
                        && "collection-time-comparison".equals(m.getScenarioType())));
    }

    @Test
    void newConfirmRequestOverwritesPendingAndNotifiesDiscard() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        TrafficDataService trafficData = new TrafficDataService();

        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData,
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

        // SimulationConfig.getCollectionTimeLabel()은 collectionTimeMinutes를
        // 매번 "%02d:%02d"로 재포맷하므로("8:30"→setter가 파싱 후 getter가
        // "08:30"으로 정규화) 한 자리 시로는 cfgToConfirm 분기를 재현할 수
        // 없다 — 시(hour)가 0~23 범위를 벗어나야 isValidCollectionTime이
        // false가 되어 확인 대기로 빠진다.
        //
        // 예전에는 setCollectionTimeLabel("25:30")으로 만들었지만 W-04 이후
        // 문자열 파서가 그런 값을 거부한다. 분(minute) 필드에 직접 넣어
        // 같은 상태(라벨 "25:30")를 만든다 — 이 경로가 오히려 실제 상황에
        // 더 가깝다. LLM이 아니라 내부 계산이 범위를 벗어난 분을 넣는 경우다.
        SimulationConfig cfgA = new SimulationConfig();
        cfgA.setCollectionTimeMinutes(25 * 60 + 30);   // 라벨 "25:30" → 확인 대기 분기(cfgToConfirm)
        cfgA.setDays(30);
        cfgA.setSeeds(30);

        SimulationConfig cfgB = new SimulationConfig();
        cfgB.setCollectionTimeMinutes(26 * 60 + 15);   // 라벨 "26:15"
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
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData,
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

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
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData,
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

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
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData,
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

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

    @Test
    void koreanNodeListReturnsPerNodeTimesAndNoFullSimulation() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        SimulationTool tool = mock(SimulationTool.class);
        ChatController controller = new ChatController(
                messaging, openAiService, tool, new SimpleMeterRegistry(), new TrafficDataService(),
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

        controller.handleMessage(userMsg("노드 b,c,a,d순서로 12시에 수거하면 얼마나 걸려?"));

        verify(openAiService, never()).extractParamsStrict(anyList(), anyString());
        verify(tool, never()).runSimulation(any(), any(), anyBoolean());

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/messages"), captor.capture());
        String reply = captor.getAllValues().stream()
                .filter(m -> m.getType() == ChatMessage.MessageType.BOT)
                .map(ChatMessage::getContent)
                .findFirst().orElseThrow();
        assertTrue(reply.contains("Node_B → Node_C → Node_A → Node_D"));
        assertTrue(reply.contains("출발(수거) 시각: 12:00"));
        assertTrue(reply.contains("Node_B: 수거 시작 12:00"));
        assertTrue(reply.contains("Node_C 도착"));
        assertTrue(reply.contains("Node_A 도착"));
        assertTrue(reply.contains("Node_D 도착"));
        assertTrue(reply.contains("총 이동시간"));
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
                messaging, openAiService, tool, new SimpleMeterRegistry(), trafficData,
                new McpToolRegistry(List.of()), new EdgeThermalProfileStore());

        controller.handleMessage(userMsg(userText));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/messages"), captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> m.getType() == ChatMessage.MessageType.BOT)
                .map(ChatMessage::getContent)
                .findFirst().orElse(null);
    }

    /**
     * 장량동 화면(/waste)에 있는 사용자가 보낸 메시지를 흉내낸다.
     *
     * <p>{@code domain}을 실어 보내는 것이 <b>실제 클라이언트 동작</b>이다 — 브라우저
     * UI는 도메인이 확정된 뒤 모든 메시지에 현재 화면의 도메인을 붙여 보내고
     * (js/chat.js#send), 서버는 그 값을 키워드 추측보다 우선한다. 이 필드가 없으면
     * "12시에 실행해줘"처럼 도메인 어휘가 하나도 없는 문장은 도메인 미정으로
     * 판정되어 되묻기로 빠지는데, 그건 시작화면에서만 일어나야 하는 동작이다.
     */
    private ChatMessage userMsg(String text) {
        ChatMessage m = new ChatMessage(ChatMessage.MessageType.USER, text);
        m.setDomain("waste");
        return m;
    }
}
