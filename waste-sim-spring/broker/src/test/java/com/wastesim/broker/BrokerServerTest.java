package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브로커가 별개 서버로서 두 문을 갖는가 — LLM 이 부르는 {@code /mcp} 와, 시뮬레이터가
 * 등록하는 {@code /api/simulators}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BrokerServerTest {

    @Autowired MockMvc mvc;
    @Autowired CandidateRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode rpc(String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
        String out = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(out);
    }

    @Test
    void 도구는_조회_둘뿐이고_등록은_도구가_아니다() throws Exception {
        List<String> names = new ArrayList<>();
        rpc("tools/list", null).path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertEquals(List.of("find_simulators", "list_candidates"), names.stream().sorted().toList(),
                "등록을 도구로 내면 LLM 이 카드를 지어 등록하고 스스로 고를 수 있다");
    }

    @Test
    void 시뮬레이터가_등록하면_조회에서_연결_정보와_함께_나온다() throws Exception {
        mvc.perform(post("/api/simulators").contentType(MediaType.APPLICATION_JSON)
                        .content(TestCards.jangnyang().toString()))
                .andExpect(status().isOk());

        JsonNode res = rpc("tools/call", "{\"name\":\"find_simulators\",\"arguments\":"
                + "{\"domain\":\"쓰레기수거\",\"spatialScale\":\"한 동네\",\"environmentConditions\":[\"평일 교통량\"]}}");
        JsonNode body = mapper.readTree(res.path("result").path("content").get(0).path("text").asText());
        JsonNode top = body.path("matches").get(0);
        assertEquals("jangnyang-waste-sim", top.path("serverId").asText());
        assertEquals("http://localhost:8090/mcp", top.path("endpoint").asText());
    }

    @Test
    void 조회와_매칭을_서버_로그에_한_줄씩_남긴다(CapturedOutput output) throws Exception {
        // LLM 쪽 화면에는 모델이 보낸 것과 받은 것만 보인다. 브로커가 실제로 무엇을 받아 어떻게
        // 판정했는지는 서버 로그에서만 볼 수 있다.
        mvc.perform(post("/api/simulators").contentType(MediaType.APPLICATION_JSON)
                .content(TestCards.jangnyang().toString())).andExpect(status().isOk());
        rpc("tools/call", "{\"name\":\"find_simulators\",\"arguments\":"
                + "{\"domain\":\"쓰레기수거\",\"spatialScale\":\"장량동 전체\"}}");
        assertTrue(output.getOut().contains("[3 조회] domain=쓰레기수거 · scale=장량동 전체"), output.getOut());
        assertTrue(output.getOut().contains("[4 매칭] ADJUST_REQUEST → jangnyang-waste-sim 쓰려면 고칠 것: [spatialScale='장량동 전체']"),
                output.getOut());
    }

    private JsonNode rpcIn(String session, String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
        String out = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .header("Mcp-Session-Id", session).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(out);
    }

    @Test
    void 연결을_시작하면_세션_id_를_준다() throws Exception {
        // 표준 MCP HTTP 방식 — 클라이언트는 이 id 를 이후 요청마다 붙인다. 채팅 세션 하나가 곧
        // MCP 세션 하나라, 브로커가 "같은 대화" 를 알아볼 수 있는 유일한 단서다.
        String sid = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Mcp-Session-Id");
        assertNotNull(sid);
        assertFalse(sid.isBlank());
    }

    @Test
    void 같은_대화에서_매칭_뒤_다시_조회하면_알리고_로그에_남긴다(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/simulators").contentType(MediaType.APPLICATION_JSON)
                .content(TestCards.jangnyang().toString())).andExpect(status().isOk());
        String call = "{\"name\":\"find_simulators\",\"arguments\":{\"domain\":\"쓰레기수거\","
                + "\"spatialScale\":\"한 동네\",\"environmentConditions\":[\"평일 교통량\"]}}";

        JsonNode first = rpcIn("chat-1", "tools/call", call);
        JsonNode firstBody = mapper.readTree(first.path("result").path("content").get(0).path("text").asText());
        assertTrue(firstBody.path("previousMatch").isMissingNode(), "처음 조회에는 붙지 않는다");

        JsonNode again = rpcIn("chat-1", "tools/call", call);
        JsonNode body = mapper.readTree(again.path("result").path("content").get(0).path("text").asText());
        assertEquals("MATCH", body.path("verdict").asText(),
                "막지는 않는다 — 같은 대화에서 전혀 다른 시뮬레이션을 새로 물을 수도 있다");
        assertEquals("jangnyang-waste-sim", body.path("previousMatch").path("serverId").asText(),
                "이미 연결된 서버를 알려야 LLM 이 되풀이인지 새 요청인지 가를 수 있다");
        assertTrue(output.getOut().contains("이미 jangnyang-waste-sim 로 연결된 대화에서 다시 조회"), output.getOut());

        JsonNode other = rpcIn("chat-2", "tools/call", call);
        JsonNode otherBody = mapper.readTree(other.path("result").path("content").get(0).path("text").asText());
        assertTrue(otherBody.path("previousMatch").isMissingNode(), "다른 대화의 매칭은 섞지 않는다");
    }

    @Test
    void 연결_정보_없는_등록은_400_이다() throws Exception {
        mvc.perform(post("/api/simulators").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverId\":\"no-endpoint\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(registry.byServerId("no-endpoint").isEmpty());
    }
}
