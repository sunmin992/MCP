package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.capability.CapabilityCardLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 후보 카드가 장량동 카드와 같은 형식인가, 그리고 <b>서로 구분되는가</b>.
 *
 * <p>형식이 갈라지면 브로커가 어느 한쪽을 못 읽는다. 구분되지 않으면 매칭을 평가할 수
 * 없다 — 후보가 전부 같은 답을 내면 무엇을 고르든 정답이다.
 *
 * <p>후보 둘은 <b>지어낸 서버</b>다. 실재하는 것처럼 두면 나중에 누가 그 endpoint 를
 * 부르려 한다. 카드가 스스로 그 사실을 밝히는지도 여기서 확인한다.
 */
class CandidateCardsTest {

    private final CandidateRegistry registry =
            new CandidateRegistry(new CapabilityCardLoader().rawJson(),
                    "classpath*:/mcp/candidates/*.json");

    @Test
    void 카드_세_장이_실린다() {
        assertEquals(List.of("district-waste-sim", "jangnyang-waste-sim", "water-leak-sim"),
                registry.serverIds());
    }

    @Test
    void 세_카드가_같은_필수_키를_갖는다() {
        for (JsonNode card : registry.cards()) {
            String id = card.path("serverId").asText();
            for (String key : List.of("serverId", "name", "endpoint", "domain",
                    "region", "analysisUnit", "applicability", "unsupported")) {
                assertFalse(card.path(key).isMissingNode(), id + " 카드에 " + key + " 가 없다");
            }
            assertFalse(card.path("applicability").path("environment").isMissingNode(),
                    id + " 카드에 applicability.environment 가 없다");
            assertTrue(card.path("domain").isArray(), id + " 의 domain 이 배열이 아니다");
            assertTrue(card.path("unsupported").isArray(), id + " 의 unsupported 가 배열이 아니다");
        }
    }

    @Test
    void 지어낸_후보는_스스로_그렇다고_밝힌다() {
        for (JsonNode card : registry.cards()) {
            String id = card.path("serverId").asText();
            if (id.equals("jangnyang-waste-sim")) {
                assertTrue(card.path("fictional").isMissingNode(),
                        "장량동은 실재하는 서버다. fictional 을 달면 안 된다");
                continue;
            }
            assertTrue(card.path("fictional").asBoolean(), id + " 이 가상임을 밝히지 않는다");
            assertFalse(card.path("fictionalReason").asText().isBlank(),
                    id + " 이 왜 가상인지 적지 않았다");
            assertTrue(card.path("endpoint").asText().contains("example.invalid"),
                    id + " 의 endpoint 가 실제로 부를 수 있어 보인다: " + card.path("endpoint").asText());
        }
    }

    // ── 구분 가능성 — 이것이 없으면 매칭을 평가할 수 없다 ────────────────────────

    @Test
    void 도메인이_같은_둘과_다른_하나로_갈린다() {
        assertEquals(List.of("district-waste-sim", "jangnyang-waste-sim"),
                idsWhereDomain("waste-collection"),
                "도메인이 같은 둘 사이에서 고르는 것이 진짜 시험이다");
        assertEquals(List.of("water-leak-sim"), idsWhereDomain("water-supply"),
                "도메인으로 걸러지는 대조군이 하나 있어야 한다");
    }

    @Test
    void 같은_도메인_둘이_규모에서_어긋난다() {
        assertEquals("block", scopeOf("jangnyang-waste-sim"));
        assertEquals("district", scopeOf("district-waste-sim"));
    }

    @Test
    void 같은_도메인_둘이_교통에서_어긋난다() {
        assertTrue(environmentOf("jangnyang-waste-sim").contains("traffic-profile"));
        assertFalse(environmentOf("district-waste-sim").contains("traffic-profile"),
                "교통 축에서도 같으면 '한 동네 + 평일 교통량' 요청에서 둘을 가를 수 없다");
    }

    @Test
    void 후보A가_교통_미지원을_사유와_함께_밝힌다() {
        JsonNode card = registry.byServerId("district-waste-sim").orElseThrow();
        JsonNode traffic = null;
        for (JsonNode u : card.path("unsupported")) {
            for (JsonNode k : u.path("matchKeys")) {
                if ("feature:traffic".equals(k.asText())) traffic = u;
            }
        }
        assertNotNull(traffic, "교통을 못 한다는 사실이 unsupported 에 없다 — 점수로만 깎이면 사유를 낼 수 없다");
        assertFalse(traffic.path("message").asText().isBlank());
    }

    private List<String> idsWhereDomain(String domain) {
        return registry.cards().stream()
                .filter(c -> {
                    for (JsonNode d : c.path("domain")) if (domain.equals(d.asText())) return true;
                    return false;
                })
                .map(c -> c.path("serverId").asText())
                .sorted()
                .toList();
    }

    private String scopeOf(String serverId) {
        return registry.byServerId(serverId).orElseThrow().path("analysisUnit").path("scope").asText();
    }

    private List<String> environmentOf(String serverId) {
        List<String> out = new java.util.ArrayList<>();
        registry.byServerId(serverId).orElseThrow()
                .path("applicability").path("environment").forEach(n -> out.add(n.asText()));
        return out;
    }
}
