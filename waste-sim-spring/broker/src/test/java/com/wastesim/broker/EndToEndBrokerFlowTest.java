package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요청에서 서버 선택까지 — 명세 2·3·4.
 *
 * <p>고른 서버로 이어지는 5단계부터는 이 모듈에서 볼 수 없다. 브로커와 시뮬레이터가 별개
 * 프로세스이기 때문이다. 여기서 지키는 것은 브로커가 내주는 답만으로 LLM 이 그 서버를
 * 찾아갈 수 있는가 — 즉 매칭 결과에 연결 정보가 실리는가다. 마지막 시험들이 계획 2 의
 * 요점이다 — <b>후보가 하나뿐일 때는 물을 수 없던 질문</b>이기 때문이다.
 */
class EndToEndBrokerFlowTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateRegistry registry =
            new CandidateRegistry(TestCards.jangnyang(), "classpath*:/mcp/candidates/*.json");
    private final FindSimulatorsTool find =
            new FindSimulatorsTool(new CandidateMatcher(registry), mapper);

    private JsonNode ok(com.wastesim.mcp.McpToolProvider tool, JsonNode args) throws Exception {
        var r = tool.call(args);
        assertTrue(r.ready(), tool.toolName() + " 이 거절했다: " + r);
        return mapper.readTree(r.result().toString());
    }

    /** 명세 1단계 발화에서 LLM 이 뽑은 프로필. */
    private JsonNode 장량동_조회() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "쓰레기수거");
        args.put("spatialScale", "한 동네");
        args.putArray("environmentConditions").add("평일 교통량");
        args.put("objective", "민원이 가장 적은 수거 시각");
        return ok(find, args);
    }

    @Test
    void 고른_서버의_연결_정보가_함께_나온다() throws Exception {
        // 3·4단계 — 브로커가 고른다.
        JsonNode top = 장량동_조회().path("recommended");
        assertEquals("jangnyang-waste-sim", top.path("serverId").asText());
        assertEquals(TestCards.jangnyang().path("endpoint").asText(), top.path("endpoint").asText(),
                "연결 정보 없이 이름만 내주면 LLM 이 고른 서버로 갈 수 없다 — 그림의 4단계가 끊긴다");

        JsonNode 카드 = registry.byServerId("jangnyang-waste-sim").orElseThrow();
        assertFalse(카드.path("fictional").asBoolean(), "지어낸 서버로는 실행까지 갈 수 없다");
    }

    @Test
    void 교통을_요구하면_교통을_못_하는_후보에_어긋난_점이_적힌다() throws Exception {
        JsonNode matches = 장량동_조회().path("matches");
        assertEquals(2, matches.size());
        JsonNode district = null;
        for (JsonNode m : matches) {
            if (m.path("serverId").asText().equals("district-waste-sim")) district = m;
        }
        assertNotNull(district);
        assertTrue(String.join(" ", 목록(district.path("mismatches"))).contains("교통"),
                "왜 추천되지 않았는지 말하지 않으면 사용자가 그 서버를 고집할 때 답할 수 없다");
    }

    // ── 이것이 계획 2 전체의 요점이다 ─────────────────────────────────────────

    @Test
    void 도메인이_다른_요청에는_다른_서버가_나온다() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "상수도 누수");
        args.put("objective", "누수 지점을 찾고 싶다");
        JsonNode out = ok(find, args);

        List<String> ids = 목록(out.path("matches"), "serverId");
        assertEquals(List.of("water-leak-sim"), ids);
        assertFalse(ids.contains("jangnyang-waste-sim"),
                "후보가 하나뿐일 때는 이 질문을 할 수 없었다 — 무엇을 고르든 장량동이었다");
    }

    @Test
    void 아무_후보도_없으면_오류가_아니라_사유를_낸다() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "교통 신호 최적화");
        JsonNode out = ok(find, args);
        assertEquals(0, out.path("matchCount").asInt());
        assertFalse(out.path("note").asText().isBlank());
    }

    private List<String> 목록(JsonNode arrayNode) {
        List<String> out = new java.util.ArrayList<>();
        arrayNode.forEach(n -> out.add(n.asText()));
        return out;
    }

    private List<String> 목록(JsonNode arrayNode, String field) {
        List<String> out = new java.util.ArrayList<>();
        arrayNode.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }
}
