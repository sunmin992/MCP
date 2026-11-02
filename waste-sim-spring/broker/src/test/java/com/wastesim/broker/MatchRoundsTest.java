package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 고친 조회를 matchId 로 이어 세고, 다섯 번을 넘으면 대조 없이 NONE 으로 닫는다. */
class MatchRoundsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateRegistry registry =
            new CandidateRegistry(TestCards.jangnyang(), "classpath*:/mcp/candidates/*.json");
    private final FindSimulatorsTool find = new FindSimulatorsTool(new CandidateMatcher(registry), mapper);

    private ObjectNode 그림의_요청() {
        ObjectNode a = mapper.createObjectNode();
        a.put("domain", "쓰레기수거");
        a.put("spatialScale", "한 동네");
        a.putArray("environmentConditions").add("평일 교통량");
        a.put("objective", "민원이 가장 적은 수거 시각");
        return a;
    }

    private JsonNode call(ObjectNode args, String session) throws Exception {
        var r = find.call(args, session);
        assertTrue(r.ready(), "거절했다: " + r);
        return mapper.readTree(r.result().toString());
    }

    /** 첫 조회 뒤 같은 matchId 로 n 번 더 부른 마지막 응답. */
    private JsonNode 고쳐서(int n, String session) throws Exception {
        JsonNode out = call(그림의_요청(), session);
        String id = out.path("round").path("matchId").asText();
        for (int i = 0; i < n; i++) {
            ObjectNode a = 그림의_요청();
            a.put("matchId", id);
            out = call(a, session);
        }
        return out;
    }

    @Test
    void 첫_조회는_1바퀴이고_매칭_id_를_준다() throws Exception {
        JsonNode round = call(그림의_요청(), "s1").path("round");
        assertFalse(round.path("matchId").asText().isBlank());
        assertEquals(1, round.path("round").asInt());
        assertEquals(0, round.path("revisions").asInt());
        assertEquals(5, round.path("remainingRevisions").asInt());
    }

    @Test
    void 고친_조회마다_바퀴가_는다() throws Exception {
        JsonNode round = 고쳐서(3, "s1").path("round");
        assertEquals(4, round.path("round").asInt());
        assertEquals(3, round.path("revisions").asInt());
        assertEquals(2, round.path("remainingRevisions").asInt());
    }

    @Test
    void 여섯번째_고친_조회는_대조없이_NONE() throws Exception {
        JsonNode out = 고쳐서(6, "s1");
        assertEquals("NONE", out.path("verdict").asText());
        assertEquals("REVISION_LIMIT", out.path("noneReason").asText());
        assertEquals(7, out.path("round").path("round").asInt());
        assertEquals(0, out.path("round").path("remainingRevisions").asInt());
    }

    @Test
    void 다섯번째_고친_조회는_아직_판정한다() throws Exception {
        JsonNode out = 고쳐서(5, "s1");
        assertEquals("MATCH", out.path("verdict").asText());
        assertEquals(5, out.path("round").path("revisions").asInt());
        assertEquals(0, out.path("round").path("remainingRevisions").asInt());
    }

    @Test
    void 일곱번째_이후도_계속_NONE() throws Exception {
        for (int n : new int[]{7, 8}) {
            JsonNode out = 고쳐서(n, "s1");
            assertEquals("NONE", out.path("verdict").asText());
            assertEquals("REVISION_LIMIT", out.path("noneReason").asText());
        }
    }

    @Test
    void 모르는_매칭_id_는_거절() throws Exception {
        ObjectNode a = 그림의_요청();
        a.put("matchId", "없는-id");
        var r = find.call(a, "s1");
        assertFalse(r.ready());
        assertTrue(r.errors().toString().contains("matchId"), r.errors().toString());
    }

    @Test
    void 다른_세션의_매칭_id_는_거절() throws Exception {
        String id = call(그림의_요청(), "s1").path("round").path("matchId").asText();
        ObjectNode a = 그림의_요청();
        a.put("matchId", id);
        var r = find.call(a, "s2");
        assertFalse(r.ready());
        assertTrue(r.errors().toString().contains("matchId"), r.errors().toString());
    }

    @Test
    void 목표만_있고_매칭_id_가_없으면_거절() throws Exception {
        ObjectNode a = 그림의_요청();
        a.put("targetServerId", "jangnyang-waste-sim");
        var r = find.call(a, "s1");
        assertFalse(r.ready());
        assertTrue(r.errors().toString().contains("matchId"), r.errors().toString());
    }

    @Test
    void 목표가_있으면_그_서버하고만_대조한다() throws Exception {
        ObjectNode twin = (ObjectNode) TestCards.jangnyang().deepCopy();
        twin.put("serverId", "twin-waste-sim");
        twin.put("endpoint", "http://localhost:9999/mcp");
        registry.register(twin);

        JsonNode first = call(그림의_요청(), "s1");
        assertEquals("CHOOSE", first.path("verdict").asText());

        ObjectNode a = 그림의_요청();
        a.put("matchId", first.path("round").path("matchId").asText());
        a.put("targetServerId", "twin-waste-sim");
        JsonNode out = call(a, "s1");
        assertEquals("MATCH", out.path("verdict").asText(), out.toString());
        assertEquals("twin-waste-sim", out.path("recommended").path("serverId").asText());
    }

    private JsonNode 다시(ObjectNode first, ObjectNode second) throws Exception {
        String id = call(first, "s1").path("round").path("matchId").asText();
        second.put("matchId", id);
        return call(second, "s1").path("round");
    }

    @Test
    void 환경_조건을_빼면_제거로_센다() throws Exception {
        ObjectNode a = 그림의_요청();
        ((com.fasterxml.jackson.databind.node.ArrayNode) a.path("environmentConditions")).add("분리배출");
        JsonNode round = 다시(a, 그림의_요청());
        assertEquals("[\"environmentConditions: 분리배출\"]", round.path("removedConditions").toString());
        assertTrue(round.path("purposeChanged").asBoolean());
    }

    @Test
    void 구절을_고쳐_쓰면_제거가_아니다() throws Exception {
        ObjectNode a = 그림의_요청();
        a.putArray("environmentConditions").add("평일 교통");
        JsonNode round = 다시(a, 그림의_요청());
        assertEquals(0, round.path("removedConditions").size());
        assertFalse(round.path("purposeChanged").asBoolean());
    }

    @Test
    void 도메인이_바뀌면_목적_변경이다() throws Exception {
        ObjectNode b = 그림의_요청();
        b.put("domain", "주차");
        JsonNode round = 다시(그림의_요청(), b);
        assertEquals(0, round.path("removedConditions").size());
        assertTrue(round.path("purposeChanged").asBoolean());
    }

    @Test
    void 말하지_않던_칸을_채우는_것은_제거가_아니다() throws Exception {
        ObjectNode b = 그림의_요청();
        b.putArray("population").add("학생이 많음");
        JsonNode round = 다시(그림의_요청(), b);
        assertEquals(0, round.path("removedConditions").size());
        assertFalse(round.path("purposeChanged").asBoolean());
    }

    @Test
    void 한도_뒤의_조회는_기록을_늘리지_않는다() {
        var rounds = new MatchRounds();
        var p = new RequestProfile("쓰레기수거", null, null, null, null, null);
        String id = rounds.open("s", p);
        MatchRounds.MatchThread t = null;
        for (int i = 0; i < 9; i++) t = rounds.next(id, "s", p, null).orElseThrow();
        assertEquals(10, t.round());
        assertEquals(6, t.profiles().size());
    }
}
