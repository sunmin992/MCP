package com.wastesim.broker;

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
    void 자기_카드도_후보도_없으면_예외를_던진다() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CandidateRegistry(null, "classpath*:/mcp/없는디렉터리/*.json"));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("없는디렉터리"),
                "빈 레지스트리로 넘어가면 매칭이 늘 '후보 없음' 을 내고 그 이유를 아무도 모른다");
    }

    @Test
    void 자기_카드는_후보_디렉터리가_비어도_남는다() {
        // 명세 0단계 — 시뮬레이터가 브로커에 등록한다. 등록해 온 외부 서버가 아직 없는 것과
        // 이 서버가 없는 것은 다르다.
        CandidateRegistry registry = new CandidateRegistry(
                new com.wastesim.capability.CapabilityCardLoader().rawJson(),
                "classpath*:/mcp/없는디렉터리/*.json");
        assertEquals(List.of("jangnyang-waste-sim"), registry.serverIds());
    }

    @Test
    void 자기_카드와_후보가_함께_실린다() {
        CandidateRegistry registry = new CandidateRegistry(
                new com.wastesim.capability.CapabilityCardLoader().rawJson(),
                "classpath*:/mcp/cand-ok/*.json");
        assertEquals(List.of("alpha-sim", "beta-sim", "jangnyang-waste-sim"), registry.serverIds());
    }

    @Test
    void serverId_가_없는_카드는_거절한다() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CandidateRegistry(null, "classpath*:/mcp/cand-bad/*.json"));
        assertTrue(ex.getMessage().contains("serverId"));
    }
}
