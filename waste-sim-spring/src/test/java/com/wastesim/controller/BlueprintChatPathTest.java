package com.wastesim.controller;

import com.wastesim.llm.ExtractedValue;
import com.wastesim.llm.InterpreterException;
import com.wastesim.llm.RequestExtraction;
import com.wastesim.llm.RequestInterpreter;
import com.wastesim.model.ChatMessage;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.SubtaskSessionService;
import com.wastesim.tool.SimulationTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 자유 문장으로 시뮬레이터를 요청했을 때 채팅 화면이 무엇을 하는가.
 *
 * <p>지금까지 {@code BlueprintComposer}는 프로덕션에서 아무도 부르지 않았다 — 라이브러리로만
 * 존재했고, 생성 요청은 여전히 34문항을 처음부터 물었다. 이 테스트들이 그 배선을 고정한다.
 *
 * <p><b>LLM은 부르지 않는다.</b> {@link RequestInterpreter}를 고정 응답 스텁으로 바꿔 흐름만
 * 본다 — 실제 호출은 별도 통합 확인이다.
 */
class BlueprintChatPathTest {

    /** 요청에서 건물 수를 읽어내는 해석기. */
    private static RequestInterpreter reads(String field, Object value, String span) {
        return (request, fields) -> new RequestExtraction(
                List.of(new ExtractedValue(field, value, span)), "장량동", null, null);
    }

    /** 아무것도 읽지 못하는 해석기 — 요청이 값을 담고 있지 않은 정상적인 경우. */
    private static RequestInterpreter readsNothing() {
        return (request, fields) -> new RequestExtraction(List.of(), "장량동", null, null);
    }

    private static RequestInterpreter refuses(String region) {
        return (request, fields) -> new RequestExtraction(List.of(), region, null, null);
    }

    private static RequestInterpreter broken() {
        return (request, fields) -> { throw new InterpreterException("해석기 없음"); };
    }

    private record Rig(ChatController controller, SimpMessagingTemplate messaging,
                       SubtaskSessionService sessions) {}

    private static Rig rig(RequestInterpreter interpreter, boolean enabled) {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        SubtaskSessionService sessions = ChatControllerTest.subtaskService();
        com.wastesim.llm.BlueprintComposer composer = new com.wastesim.llm.BlueprintComposer(
                sessions, new JangnyangSubtaskCatalog(), interpreter);

        ChatController c = new ChatController(messaging, mock(OpenAiService.class),
                mock(SimulationTool.class), new SimpleMeterRegistry(), new TrafficDataService(),
                sessions, com.wastesim.site.CollectionSiteRegistry.empty(), composer, enabled);
        return new Rig(c, messaging, sessions);
    }

    private static ChatMessage userMsg(String text) {
        ChatMessage m = new ChatMessage(ChatMessage.MessageType.USER, text);
        m.setDomain("waste");
        return m;
    }

    /** 화면으로 나간 문구를 모두 이어 붙인다. */
    private static String saidTo(SimpMessagingTemplate messaging) {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(messaging, atLeastOnce()).convertAndSend(anyString(), sent.capture());
        StringBuilder all = new StringBuilder();
        for (Object o : sent.getAllValues()) {
            if (o instanceof ChatMessage m && m.getContent() != null) all.append(m.getContent()).append('\n');
        }
        return all.toString();
    }

    // ── 거부 ───────────────────────────────────────────────────────────────

    /**
     * <b>범위 밖 요청은 34문항을 시작하지 않는다.</b> 지금은 "부산 시뮬레이터 만들어 줘"에도
     * 장량동 문항을 처음부터 묻는다 — 사용자는 34개를 답하고 나서야 자기 요청이 애초에
     * 불가능했다는 것을 알게 된다.
     */
    @Test
    void outOfRegionRequestIsRefusedWithoutStartingCollection() {
        Rig r = rig(refuses("부산"), true);

        r.controller().handleMessage(userMsg("부산 시뮬레이터 만들어 줘"));

        assertNull(r.sessions().activeSession("default"),
                "거부한 요청으로 수집을 시작하면 사용자가 34문항을 답한 뒤에야 불가능을 안다");
        String said = saidTo(r.messaging());
        assertTrue(said.contains("장량동"), said);
    }

    /** 거부는 무엇이 있으면 되는지를 함께 말한다 — "안 됩니다"로 끝나면 다음 행동이 없다. */
    @Test
    void refusalListsWhatWouldBeNeeded() {
        Rig r = rig(refuses("부산"), true);

        r.controller().handleMessage(userMsg("부산 시뮬레이터 만들어 줘"));

        String said = saidTo(r.messaging());
        assertTrue(said.contains("교통 구역 정의"), "사람이 채워야 하는 항목이 빠졌다: " + said);
        assertTrue(said.contains("OSRM"), "자동 수집 가능한 항목이 빠졌다: " + said);
    }

    // ── 정상 경로 ──────────────────────────────────────────────────────────

    /**
     * <b>이 배선의 요점.</b> 요청에서 읽어낸 값은 다시 묻지 않는다.
     */
    @Test
    void valuesReadFromTheRequestAreNotAskedAgain() {
        Rig r = rig(reads("numBuildings", 26, "26개 동"), true);

        r.controller().handleMessage(userMsg("장량동 26개 동으로 시뮬레이터 만들어 줘"));

        var session = r.sessions().activeSession("default");
        assertNotNull(session, "정상 요청은 수집을 시작해야 한다");
        var def = r.sessions().definitionOf(session);
        String id = def.subtasks().stream()
                .filter(s -> "numBuildings".equals(s.answerField()))
                .findFirst().orElseThrow().id();
        assertTrue(session.answers().containsKey(id),
                "문장에 있던 값을 다시 묻으면 자유 문장을 받은 의미가 없다");
    }

    // ── 폴백 ───────────────────────────────────────────────────────────────

    /**
     * 해석기가 죽으면 조용히 넘어가지 않는다. 문항으로 진행하되 <b>그 이유를 밝힌다</b> —
     * 아무 말 없이 34문항이 시작되면 사용자는 자기 문장이 무시된 줄 모른다.
     */
    @Test
    void interpreterFailureFallsBackLoudly() {
        Rig r = rig(broken(), true);

        r.controller().handleMessage(userMsg("장량동 시뮬레이터 만들어 줘"));

        assertNotNull(r.sessions().activeSession("default"), "폴백은 문항 흐름으로 가는 것이다");
        assertTrue(saidTo(r.messaging()).contains("해석"),
                "폴백 사유를 말하지 않으면 문장이 무시된 것을 알 수 없다: " + saidTo(r.messaging()));
    }

    // ── 끌 수 있다 ─────────────────────────────────────────────────────────

    /**
     * 속성을 끄면 예전 그대로다. LLM이 값을 잘못 뽑아 지금보다 나빠질 수 있으므로 되돌릴
     * 스위치가 있어야 한다 — 스위치 없이 켜 두는 것은 되돌릴 방법 없이 바꾸는 것이다.
     */
    @Test
    void disabledPropertyKeepsTheOldFixedFlow() {
        Rig r = rig(refuses("부산"), false);

        r.controller().handleMessage(userMsg("부산 시뮬레이터 만들어 줘"));

        assertNotNull(r.sessions().activeSession("default"),
                "꺼져 있으면 예전처럼 문항을 시작해야 한다");
    }

    /** 꺼져 있으면 해석기를 아예 부르지 않는다 — 끈 기능이 비용을 쓰면 안 된다. */
    @Test
    void disabledPropertyDoesNotCallTheInterpreter() {
        boolean[] called = {false};
        RequestInterpreter spy = (request, fields) -> {
            called[0] = true;
            return new RequestExtraction(List.of(), "장량동", null, null);
        };
        Rig r = rig(spy, false);

        r.controller().handleMessage(userMsg("장량동 시뮬레이터 만들어 줘"));

        assertFalse(called[0], "꺼진 경로가 LLM을 부르면 끈 의미가 없다");
    }
}
