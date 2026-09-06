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

    /**
     * <b>안내는 사용자에게 실제로 보이는 메시지로 나가야 한다.</b>
     *
     * <p>SUBTASK 메시지의 {@code content}는 화면이 그리지 않는다 — 문항 카드는
     * {@code question}만 찍는다. 그래서 안내를 그 카드에 실어 보내면 전송은 되지만 아무도
     * 읽지 못한다. 폴백 사유가 바로 그런 상태였다: 해석기가 죽어도 화면에는 그냥 34문항이
     * 뜨고, 사용자는 자기 문장이 무시된 줄 모른다.
     */
    @Test
    void theNoticeArrivesAsAMessageTheScreenActuallyRenders() {
        Rig r = rig(broken(), true);

        r.controller().handleMessage(userMsg("장량동 시뮬레이터 만들어 줘"));

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(r.messaging(), atLeastOnce()).convertAndSend(anyString(), sent.capture());
        boolean visible = sent.getAllValues().stream()
                .filter(o -> o instanceof ChatMessage)
                .map(o -> (ChatMessage) o)
                .anyMatch(m -> m.getType() != ChatMessage.MessageType.SUBTASK
                        && m.getContent() != null && m.getContent().contains("해석"));
        assertTrue(visible,
                "안내가 SUBTASK 카드에만 실리면 화면이 그리지 않아 사용자에게 닿지 않는다");
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

    // ── 실행 동사만 있고 시각이 없는 요청 ──────────────────────────────────

    /** 세션에서 한 필드의 답을 꺼낸다. 없으면 null. */
    private static Object answerOf(Rig r, String field) {
        var session = r.sessions().activeSession("default");
        if (session == null) return null;
        var def = r.sessions().definitionOf(session);
        String id = def.subtasks().stream()
                .filter(s -> field.equals(s.answerField()))
                .findFirst().orElseThrow().id();
        var a = session.answers().get(id);
        return a == null ? null : a.value();
    }

    /**
     * <b>이 변경의 요점.</b> "돌려줘"는 처음부터 "실행해줘"와 같게 판정됐다 — 갈린 것은
     * 동사가 아니라 수거 시각의 유무였다. 시각이 없으면 즉시 실행 게이트가 받지 않고,
     * 생성 판별기는 실행 동사를 일부러 제외하므로, 조건을 다 말한 문장이 일반 답변으로
     * 떨어졌다.
     *
     * <p>되묻고 즉시 실행하는 방식은 쓸 수 없다. 즉시 실행 경로의 추출 스키마에는
     * {@code numBuildings}가 없고 프롬프트가 히스토리 이어받기를 금지하므로, 시각만 받아
     * 실행하면 "26개 동"이 조용히 사라진다.
     */
    @Test
    void runVerbWithoutATimeStartsCollectionAndKeepsWhatTheRequestSaid() {
        Rig r = rig(reads("numBuildings", 26, "26개 동"), true);

        r.controller().handleMessage(userMsg("장량동 26개 동으로 한 달 돌려줘"));

        assertNotNull(r.sessions().activeSession("default"),
                "조건을 다 말한 요청이 일반 답변으로 떨어지면 사용자는 아무것도 못 얻는다");
        assertEquals(26, answerOf(r, "numBuildings"),
                "요청에 있던 26개 동이 사라지면 되묻는 의미가 없다");
    }

    /** 말하지 않은 수거 시각은 기본값으로 채우지 않고 <b>묻는다</b>. */
    @Test
    void theMissingCollectionTimeIsAskedNotDefaulted() {
        Rig r = rig(reads("numBuildings", 26, "26개 동"), true);

        r.controller().handleMessage(userMsg("장량동 26개 동으로 한 달 돌려줘"));

        assertNull(answerOf(r, "collectionTime"),
                "실행을 요청했는데 정작 수거 시각이 12:00으로 조용히 채워지면 안 된다");
    }

    /**
     * 무엇을 왜 묻는지 말한다 — 이유 없이 질문이 뜨면 요청이 무시된 것처럼 보인다.
     *
     * <p>안내 문구를 직접 확인하고, 그것이 <b>SUBTASK가 아닌</b> 메시지로 나갔는지도 본다.
     * "수거 시각"이라는 낱말만 찾으면 {@code collectionTime} 문항 자체가 그 낱말을 담고 있어
     * 안내를 통째로 지워도 통과한다 — 변이로 확인했다.
     */
    @Test
    void theUserIsToldWhyTheTimeIsBeingAsked() {
        Rig r = rig(reads("numBuildings", 26, "26개 동"), true);

        r.controller().handleMessage(userMsg("장량동 26개 동으로 한 달 돌려줘"));

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(r.messaging(), atLeastOnce()).convertAndSend(anyString(), sent.capture());
        boolean visible = sent.getAllValues().stream()
                .filter(o -> o instanceof ChatMessage)
                .map(o -> (ChatMessage) o)
                .anyMatch(m -> m.getType() != ChatMessage.MessageType.SUBTASK
                        && m.getContent() != null && m.getContent().contains("수거 시각이 없어"));
        assertTrue(visible, "왜 시각을 묻는지가 화면이 그리는 메시지에 없다: " + saidTo(r.messaging()));
    }

    /**
     * 시각이 있는 문장은 즉시 실행 그대로다 — <b>시나리오 조건이 함께 있어도</b> 그렇다.
     *
     * <p>이 게이트가 즉시 실행보다 먼저 서면 "10시에 수거로 26개 동 한 달 돌려줘"가 문항
     * 수집으로 샌다. 조건 없는 "10시에 수거로 돌려줘"로만 확인하면 판정기가 어차피 false를
     * 내므로 순서 가드를 지워도 통과한다 — 변이로 확인했다.
     */
    @Test
    void aRequestThatHasATimeStillRunsImmediately() {
        Rig r = rig(reads("numBuildings", 26, "26개 동"), true);

        r.controller().handleMessage(userMsg("10시에 수거로 26개 동 한 달 돌려줘"));

        assertNull(r.sessions().activeSession("default"),
                "시각이 있는 실행 요청은 수집이 아니라 즉시 실행이다");
    }

    /** 실행 동사가 없는 조회는 그대로 일반 답변이다. */
    @Test
    void aPlainQuestionStillGetsAPlainAnswer() {
        Rig r = rig(reads("numBuildings", 26, "26개 동"), true);

        r.controller().handleMessage(userMsg("장량동 배출량 알려줘"));

        assertNull(r.sessions().activeSession("default"),
                "조회 문장이 수집을 시작하면 아무 질문에나 34문항이 뜬다");
    }
}
