package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 브로커가 LLM 에게 무엇을 말하는가 — 맞는 서버가 있으면 그 서버를, 없으면 요청을 어떻게 고치면
 * 어느 서버를 쓸 수 있는지를.
 *
 * <p>"없습니다" 로 끝나면 LLM 은 사용자에게 돌려줄 말이 없다. 무엇을 바꾸면 되는지를 말해야
 * 대화가 이어진다. 그 제안은 카드에 적힌 것(분석 단위 · 미지원 사유와 대안 · 답할 수 있는 질문)
 * 에서만 나온다 — 브로커가 지어내면 LLM 이 없는 능력을 약속한다.
 */
class BrokerVerdictTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateRegistry registry =
            new CandidateRegistry(TestCards.jangnyang(), "classpath*:/mcp/candidates/*.json");
    private final FindSimulatorsTool find = new FindSimulatorsTool(new CandidateMatcher(registry), mapper);

    private JsonNode find(ObjectNode args) throws Exception {
        var r = find.call(args);
        assertTrue(r.ready(), "거절했다: " + r);
        return mapper.readTree(r.result().toString());
    }

    /** 명세 그림 2단계에서 LLM 이 뽑은 것. */
    private ObjectNode 그림의_요청() {
        ObjectNode a = mapper.createObjectNode();
        a.put("domain", "쓰레기수거");
        a.put("spatialScale", "한 동네");
        a.putArray("environmentConditions").add("평일 교통량");
        a.put("objective", "민원이 가장 적은 수거 시각");
        return a;
    }

    private JsonNode 제안(JsonNode out, String serverId) {
        for (JsonNode s : out.path("suggestions")) {
            if (s.path("serverId").asText().equals(serverId)) return s;
        }
        fail(serverId + " 에 대한 제안이 없다: " + out.path("suggestions"));
        return null;
    }

    private JsonNode 조정(JsonNode suggestion, String axis) {
        for (JsonNode a : suggestion.path("requestAdjustments")) {
            if (a.path("axis").asText().equals(axis)) return a;
        }
        fail(axis + " 조정이 없다: " + suggestion.path("requestAdjustments"));
        return null;
    }

    // ── 있으면 — 어느 서버인지 ──────────────────────────────────────────────

    @Test
    void 그대로_맞는_서버가_있으면_그_서버를_추천한다() throws Exception {
        JsonNode out = find(그림의_요청());
        assertEquals("MATCH", out.path("verdict").asText());
        JsonNode rec = out.path("recommended");
        assertEquals("jangnyang-waste-sim", rec.path("serverId").asText());
        assertEquals("http://localhost:8090/mcp", rec.path("endpoint").asText(),
                "연결 정보가 없으면 LLM 이 추천받은 서버로 갈 수 없다");
    }

    @Test
    void 매칭되면_이_대화에서는_브로커를_다시_부르지_말고_시뮬레이터만_쓰라고_안내한다() throws Exception {
        JsonNode out = find(그림의_요청());
        String next = out.path("nextStep").asText();
        assertTrue(next.contains("http://localhost:8090/mcp"), "어느 서버로 넘어가는지 말해야 한다: " + next);
        assertTrue(next.contains("다시 부르지"),
                "안내가 없으면 LLM 이 단계마다 브로커를 다시 부르며 같은 조회를 되풀이한다: " + next);
        assertTrue(next.contains("get_templates"), "넘어간 뒤 무엇부터 하는지 알려 준다: " + next);
    }

    @Test
    void 고쳐야_하면_사용자에게_묻고_고친_요청으로_다시_조회하라고_안내한다() throws Exception {
        ObjectNode a = 그림의_요청();
        a.put("spatialScale", "장량동 전체");
        String next = find(a).path("nextStep").asText();
        assertTrue(next.contains("사용자") && next.contains("find_simulators"), next);
    }

    @Test
    void 목적도_매칭_근거가_된다() throws Exception {
        // 그림 4단계의 매칭 근거: 도메인 / 한 동네 규모 / 교통 반영 지원 / 수거 시각 비교 가능.
        String reasons = find(그림의_요청()).path("recommended").path("reasons").toString();
        assertTrue(reasons.contains("목적") && reasons.contains("수거 시각") && reasons.contains("민원"),
                "목적을 보지 않으면 답할 수 없는 질문에도 서버를 추천한다: " + reasons);
    }

    // ── 없으면 — 요청을 어떻게 고치면 되는지 ─────────────────────────────────

    @Test
    void 규모가_크면_분석_단위로_줄이라고_제안한다() throws Exception {
        ObjectNode a = 그림의_요청();
        a.put("spatialScale", "장량동 전체");
        JsonNode out = find(a);
        assertEquals("ADJUST_REQUEST", out.path("verdict").asText());
        assertTrue(out.path("recommended").isMissingNode() || out.path("recommended").isNull());

        JsonNode adj = 조정(제안(out, "jangnyang-waste-sim"), "spatialScale");
        assertEquals("장량동 전체", adj.path("current").asText());
        assertTrue(adj.path("suggestion").asText().contains("원룸촌 한 블록"),
                "무엇으로 줄이면 되는지 말하지 않으면 사용자가 다시 물을 수 없다: " + adj);
        assertFalse(adj.path("reason").asText().isBlank(), "왜 안 되는지가 카드에 적혀 있다");
    }

    @Test
    void 못_하는_지표면_카드의_대안_지표를_제안한다() throws Exception {
        ObjectNode a = 그림의_요청();
        a.put("objective", "비용이 가장 적은 수거 시각");
        JsonNode out = find(a);
        assertEquals("ADJUST_REQUEST", out.path("verdict").asText());
        JsonNode adj = 조정(제안(out, "jangnyang-waste-sim"), "objective");
        assertTrue(adj.path("suggestion").asText().contains("metric:complaints"),
                "대안은 카드의 alternatives 에서 온다: " + adj);
    }

    @Test
    void 못_하는_환경_조건이면_빼거나_카드의_대안으로_바꾸라고_제안한다() throws Exception {
        ObjectNode a = 그림의_요청();
        a.putArray("environmentConditions").add("실제 도로 이동시간");
        JsonNode adj = 조정(제안(find(a), "jangnyang-waste-sim"), "environmentConditions");
        assertEquals("실제 도로 이동시간", adj.path("current").asText());
        assertTrue(adj.path("suggestion").asText().contains("feature:zoneProxyTravel"), adj.toString());
    }

    @Test
    void 도메인이_다르면_목적_자체가_바뀐다고_밝히고_답할_수_있는_질문을_보인다() throws Exception {
        ObjectNode a = mapper.createObjectNode();
        a.put("domain", "교통 신호 최적화");
        a.put("spatialScale", "한 동네");
        JsonNode out = find(a);
        assertEquals("ADJUST_REQUEST", out.path("verdict").asText());
        JsonNode adj = 조정(제안(out, "jangnyang-waste-sim"), "domain");
        assertTrue(adj.path("changesPurpose").asBoolean(),
                "도메인을 바꾸는 것은 요청을 고치는 게 아니라 다른 질문을 하는 것이다 — 그 사실을 숨기면 안 된다");
        assertTrue(adj.path("suggestion").asText().contains("수거 시각"),
                "그 서버가 무엇에 답할 수 있는지 보여야 사용자가 고를 수 있다: " + adj);
    }

    @Test
    void 목적이_답할_수_있는_질문에_없으면_그_목록으로_바꾸라고_제안한다() throws Exception {
        ObjectNode a = 그림의_요청();
        a.put("objective", "주민 만족도 설문 결과 예측");
        JsonNode adj = 조정(제안(find(a), "jangnyang-waste-sim"), "objective");
        assertTrue(adj.path("suggestion").asText().contains("민원 수"), adj.toString());
        assertFalse(adj.path("changesPurpose").asBoolean(), "도메인은 같으니 같은 서버 안에서 질문을 좁히는 것이다");
    }

    @Test
    void 지금_운영과의_비교는_기준_자료가_없어_설정_간_비교를_제안한다() throws Exception {
        // 채팅 시험에서 "지금 운영 방식 대비" 가 카드 키(현재 운영 · 지금보다 · …)에 걸리지 않아
        // "민원" 만 보고 MATCH 로 통과했다.
        ObjectNode a = mapper.createObjectNode();
        a.put("domain", "쓰레기수거");
        a.put("spatialScale", "한 동네");
        a.put("objective", "지금 운영 방식 대비 민원 감소");
        JsonNode out = find(a);
        assertEquals("ADJUST_REQUEST", out.path("verdict").asText());
        assertTrue(조정(제안(out, "jangnyang-waste-sim"), "objective").path("suggestion").asText()
                .contains("설정 간 비교"));
    }

    @Test
    void 설정끼리의_비교는_운영이라는_말이_들어가도_막지_않는다() throws Exception {
        // 키를 넓히면(예: "운영 방식") 정당한 설정 비교까지 막는다 — 그 경계를 고정한다.
        ObjectNode a = mapper.createObjectNode();
        a.put("domain", "쓰레기수거");
        a.put("spatialScale", "한 동네");
        a.put("objective", "격일 운영 방식과 매일 운영 방식의 민원 비교");
        assertEquals("MATCH", find(a).path("verdict").asText());
    }

    // ── 지어낸 후보 ─────────────────────────────────────────────────────────

    @Test
    void 지어낸_후보는_맞아도_추천하지_않는다() throws Exception {
        ObjectNode a = mapper.createObjectNode();
        a.put("domain", "상수도 누수");
        a.put("objective", "누수 지점을 찾고 싶다");
        JsonNode out = find(a);
        assertNotEquals("MATCH", out.path("verdict").asText(),
                "example.invalid 로 LLM 을 보내면 부를 수 없는 서버를 부른다");
        assertTrue(out.path("matches").get(0).path("fictional").asBoolean(),
                "목록에는 남기되 지어낸 것임을 밝힌다");
    }

    @Test
    void 실제_서버가_하나도_등록되지_않았으면_NONE_이다() throws Exception {
        var noReal = new FindSimulatorsTool(new CandidateMatcher(
                new CandidateRegistry(null, "classpath*:/mcp/candidates/*.json")), mapper);
        JsonNode out = mapper.readTree(noReal.call(그림의_요청()).result().toString());
        assertEquals("NONE", out.path("verdict").asText());
        assertFalse(out.path("note").asText().isBlank(), "왜 아무것도 없는지 말해야 한다");
    }
}
