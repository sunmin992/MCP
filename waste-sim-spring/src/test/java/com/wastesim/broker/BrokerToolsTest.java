package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 브로커 도구가 이름·스키마를 내고 호출에 답하는가.
 *
 * <p>후보를 못 고른 것은 <b>오류가 아니다.</b> 그 서버가 없다는 사실을 답으로 내야 하고,
 * 오류로 내면 LLM 이 다시 시도하거나 사용자에게 장애라고 말한다.
 */
class BrokerToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateRegistry registry = new CandidateRegistry(
            new CapabilityCardLoader().rawJson(), "classpath*:/mcp/candidates/*.json");
    private final CandidateMatcher matcher = new CandidateMatcher(registry);
    private final FindSimulatorsTool find = new FindSimulatorsTool(matcher, mapper);
    private final ListCandidatesTool list = new ListCandidatesTool(registry, mapper);

    private JsonNode call(com.wastesim.mcp.McpToolProvider tool, JsonNode args) throws Exception {
        var result = tool.call(args);
        assertTrue(result.ready(), "도구가 거절했다: " + result);
        return mapper.readTree(result.result().toString());
    }

    @Test
    void 이름과_스키마가_유효하다() throws Exception {
        assertEquals("find_simulators", find.toolName());
        assertEquals("list_candidates", list.toolName());
        for (var t : List.of(find, list)) {
            JsonNode schema = mapper.readTree(t.inputSchemaJson());
            assertEquals("object", schema.path("type").asText(), t.toolName());
            assertFalse(t.description().isBlank(), t.toolName());
        }
    }

    @Test
    void 스키마가_도메인을_필수로_요구한다() throws Exception {
        JsonNode required = mapper.readTree(find.inputSchemaJson()).path("required");
        List<String> keys = new java.util.ArrayList<>();
        required.forEach(n -> keys.add(n.asText()));
        assertEquals(List.of("domain"), keys,
                "도메인을 선택으로 두면 LLM 이 도메인 없는 조회를 만들고, 그러면 거를 축이 없다");
    }

    @Test
    void 장량동_요청에서_장량동이_일등으로_나온다() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "쓰레기수거");
        args.put("spatialScale", "한 동네");
        args.putArray("environmentConditions").add("평일 교통량");
        args.put("objective", "민원이 가장 적은 수거 시각");

        JsonNode out = call(find, args);
        assertEquals(2, out.path("matchCount").asInt());
        JsonNode top = out.path("matches").get(0);
        assertEquals("jangnyang-waste-sim", top.path("serverId").asText());
        assertFalse(top.path("reasons").isEmpty(), "근거 없이 1등만 내면 되물을 수 없다");
    }

    @Test
    void 후보가_없으면_오류가_아니라_빈_목록과_사유를_낸다() throws Exception {
        var args = mapper.createObjectNode();
        args.put("domain", "교통 신호 최적화");

        var result = find.call(args);
        assertTrue(result.ready(), "못 고른 것은 오류가 아니다 — 오류로 내면 장애로 오해된다");
        JsonNode out = mapper.readTree(result.result().toString());
        assertEquals(0, out.path("matchCount").asInt());
        assertTrue(out.path("matches").isEmpty());
        assertFalse(out.path("note").asText().isBlank(), "왜 없는지 말하지 않으면 요청을 고칠 수 없다");
    }

    @Test
    void 도메인이_없으면_거절한다() {
        var result = find.call(mapper.createObjectNode());
        assertFalse(result.ready());
        assertTrue(result.toString().contains("도메인"));
    }

    @Test
    void 후보_목록이_가상_여부를_밝힌다() throws Exception {
        JsonNode out = call(list, mapper.createObjectNode());
        assertEquals(3, out.path("candidates").size());

        boolean 장량동확인 = false, 가상확인 = false;
        for (JsonNode c : out.path("candidates")) {
            assertFalse(c.path("serverId").asText().isBlank());
            assertFalse(c.path("scope").asText().isBlank());
            if (c.path("serverId").asText().equals("jangnyang-waste-sim")) {
                assertFalse(c.path("fictional").asBoolean(), "실재하는 서버를 가상으로 내면 안 된다");
                장량동확인 = true;
            } else {
                assertTrue(c.path("fictional").asBoolean(),
                        "가상 후보를 실재하는 것처럼 내면 누가 그 endpoint 를 부른다");
                가상확인 = true;
            }
        }
        assertTrue(장량동확인 && 가상확인);
    }
}
