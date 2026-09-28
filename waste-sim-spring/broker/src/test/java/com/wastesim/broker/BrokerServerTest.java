package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
    void 연결_정보_없는_등록은_400_이다() throws Exception {
        mvc.perform(post("/api/simulators").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverId\":\"no-endpoint\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(registry.byServerId("no-endpoint").isEmpty());
    }
}
