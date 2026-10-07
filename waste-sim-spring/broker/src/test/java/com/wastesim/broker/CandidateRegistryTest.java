package com.wastesim.broker;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 후보 카드 여러 장을 읽는가, 그리고 읽지 못한 것을 조용히 넘기지 않는가.
 *
 * <p>브로커가 후보를 고르는 근거는 카드뿐이다. 한 장이라도 조용히 빠지면 그 서버는
 * 존재하지 않는 것이 되고, 왜 후보에 없었는지 아무도 되물을 수 없다.
 */
class CandidateRegistryTest {

    @Test
    void 디렉터리의_카드를_전부_읽는다() {
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/cand-ok/*.json");
        assertEquals(2, registry.cards().size());
    }

    @Test
    void 서버_id_순으로_고정된다() {
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/cand-ok/*.json");
        assertEquals(List.of("alpha-sim", "beta-sim"), registry.serverIds(),
                "클래스패스 순서는 환경마다 다르다 — 순서가 흔들리면 같은 요청이 같은 후보 순위를 낸다고 말할 수 없다");
    }

    @Test
    void 서버_id_로_찾는다() {
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/cand-ok/*.json");
        assertTrue(registry.byServerId("beta-sim").isPresent());
        assertTrue(registry.byServerId("없는서버").isEmpty());
    }

    @Test
    void 서버_id_가_겹치면_예외를_던진다() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CandidateRegistry(null, "classpath*:/mcp/cand-dup/*.json"));
        assertTrue(ex.getMessage().contains("same-id"),
                "어느 id 가 겹쳤는지 말하지 않으면 카드 두 장을 다 뒤져야 한다: " + ex.getMessage());
    }

    @Test
    void 아무도_등록하지_않았으면_빈_채로_뜬다() {
        // 브로커가 시뮬레이터보다 먼저 뜨면 아직 등록이 없다. 그것은 사실이므로 예외가 아니다 —
        // 매칭은 "등록된 서버가 없다" 는 사유를 낸다.
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/없는디렉터리/*.json");
        assertEquals(List.of(), registry.serverIds());
    }

    @Test
    void 등록한_카드는_후보_디렉터리가_비어도_남는다() {
        CandidateRegistry registry = new CandidateRegistry(
                TestCards.jangnyang(),
                "classpath*:/mcp/없는디렉터리/*.json");
        assertEquals(List.of("jangnyang-waste-sim"), registry.serverIds());
    }

    @Test
    void 등록한_카드와_후보가_함께_실린다() {
        CandidateRegistry registry = new CandidateRegistry(
                TestCards.jangnyang(),
                "classpath*:/mcp/cand-ok/*.json");
        assertEquals(List.of("alpha-sim", "beta-sim", "jangnyang-waste-sim"), registry.serverIds());
    }

    @Test
    void serverId_가_없는_카드는_거절한다() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CandidateRegistry(null, "classpath*:/mcp/cand-bad/*.json"));
        assertTrue(ex.getMessage().contains("serverId"));
    }

    // ── 0단계 등록 ─────────────────────────────────────────────────────────────

    @Test
    void 등록하면_처음엔_true_다시_보내면_false_이고_새_카드로_바뀐다() {
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/없는디렉터리/*.json");
        var card = (com.fasterxml.jackson.databind.node.ObjectNode) TestCards.jangnyang();
        assertTrue(registry.register(card));

        var changed = card.deepCopy().put("name", "바뀐 이름");
        assertFalse(registry.register(changed),
                "시뮬레이터는 주기적으로 다시 등록한다 — 재등록을 새 서버로 세면 안 된다");
        assertEquals("바뀐 이름", registry.byServerId("jangnyang-waste-sim").orElseThrow()
                .path("name").asText(), "재등록은 카드를 새것으로 바꿔야 한다");
    }

    @Test
    void endpoint_가_없는_카드는_등록하지_않는다() {
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/없는디렉터리/*.json");
        var noEndpoint = ((com.fasterxml.jackson.databind.node.ObjectNode) TestCards.jangnyang());
        noEndpoint.remove("endpoint");
        var ex = assertThrows(IllegalArgumentException.class, () -> registry.register(noEndpoint));
        assertTrue(ex.getMessage().contains("endpoint"),
                "연결 정보 없는 서버를 고르면 LLM 이 거기로 갈 수 없다");
    }

    @Test
    void 후보_파일의_서버를_등록으로_덮지_못한다() {
        CandidateRegistry registry = new CandidateRegistry(null, "classpath*:/mcp/cand-ok/*.json");
        var fake = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("serverId", "alpha-sim").put("endpoint", "http://localhost:9999/mcp");
        assertThrows(IllegalArgumentException.class, () -> registry.register(fake));
    }

    @Test
    void 거주민_유형_key_가_겹치는_카드는_등록을_거절한다() {
        ObjectNode card = (ObjectNode) TestCards.jangnyang().deepCopy();
        ArrayNode types = (ArrayNode) card.path("populationTypes");
        types.add(types.get(0).deepCopy());
        var registry = new CandidateRegistry(null, "classpath*:/mcp/candidates/*.json");

        var e = assertThrows(IllegalArgumentException.class, () -> registry.register(card));
        assertTrue(e.getMessage().contains("BlueCollar"), e.getMessage());
    }
}
