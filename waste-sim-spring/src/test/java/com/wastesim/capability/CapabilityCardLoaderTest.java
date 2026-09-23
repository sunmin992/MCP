package com.wastesim.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 능력 카드가 읽히는가, 그리고 미지원 항목을 자연어로 찾을 수 있는가.
 *
 * <p>매칭이 안 되면 브로커가 "요청 중 미지원"을 낼 수 없고, 지원하지 않는 요청이
 * 7단계까지 살아남는다.
 */
class CapabilityCardLoaderTest {

    private final CapabilityCardLoader loader = new CapabilityCardLoader();

    @Test
    void 능력카드를_읽는다() {
        CapabilityCard card = loader.card();
        assertEquals("jangnyang-waste-sim", card.serverId());
        assertTrue(card.domain().contains("waste-collection"));
    }

    @Test
    void 미지원_항목이_열두개다() {
        assertEquals(12, loader.card().unsupported().size());
    }

    @Test
    void 비용이라는_말로_미지원을_찾는다() {
        Optional<UnsupportedItem> hit = loader.matchOne("가장 적은 비용으로 돌려줘");
        assertTrue(hit.isPresent(), "'비용'이 매칭 키에 있는데 못 찾았다");
        assertEquals("UNSUPPORTED_METRIC", hit.get().code());
        assertEquals("NOT_IN_MODEL", hit.get().reasonType());
        assertFalse(hit.get().alternatives().isEmpty(), "대안 없이 거절만 하면 안 된다");
    }

    @Test
    void 실제도로라는_말로_미지원을_찾는다() {
        Optional<UnsupportedItem> hit = loader.matchOne("실제 도로 기준으로 계산해줘");
        assertTrue(hit.isPresent());
        assertEquals("DATA_UNAVAILABLE", hit.get().code());
        assertEquals("NO_DATA", hit.get().reasonType());
    }

    @Test
    void 지원되는_요청은_매칭되지_않는다() {
        assertTrue(loader.matchOne("민원이 가장 적은 수거 시각").isEmpty());
    }

    @Test
    void 여러_구절을_한꺼번에_대조한다() {
        List<UnsupportedItem> hits = loader.matchAll(
                List.of("가장 적은 비용", "실제 도로", "민원 수"));
        assertEquals(2, hits.size(), "비용과 실제 도로 둘만 걸려야 한다");
    }

    @Test
    void 모든_미지원_항목에_문안과_근거가_있다() {
        for (UnsupportedItem u : loader.card().unsupported()) {
            assertNotNull(u.code(), u.capability() + ": 코드 없음");
            assertFalse(u.matchKeys().isEmpty(), u.capability() + ": 매칭 키 없음 — 브로커가 찾을 수 없다");
            assertNotNull(u.message(), u.capability() + ": 거절 문안 없음");
            assertNotNull(u.reason(), u.capability() + ": 근거 없음");
        }
    }

    @Test
    void 리소스와_문서의_능력카드가_같다() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fromResource = mapper.readTree(
                CapabilityCardLoaderTest.class.getResourceAsStream("/mcp/jangnyang-capability-card.json"));
        var fromDocs = mapper.readTree(
                new java.io.File("docs/superpowers/specs/jangnyang-capability-card.json"));
        assertEquals(fromDocs, fromResource,
                "문서의 정본과 서빙 리소스가 갈라졌다. 문서를 고쳤으면 리소스에도 복사하라");
    }
}
