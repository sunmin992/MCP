package com.wastesim.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원문 답변을 허용값·범위에 맞추는가, 그리고 <b>맞지 않으면 거절하는가</b>.
 *
 * <p>가까운 값으로 보정하면 요청한 것과 다른 실험이 돌아간다.
 */
class AnswerNormalizerTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final AnswerNormalizer normalizer = new AnswerNormalizer();

    private AnswerNormalizer.NormalizeResult run(String templateId, String raw) {
        return normalizer.normalize(catalog.byId(templateId).orElseThrow(), raw);
    }

    @Test
    void 열거형_값을_대소문자_무관하게_받는다() {
        var r = run("jn.truckType", "small_1ton");
        assertTrue(r.ok());
        assertEquals("SMALL_1TON", r.value());
    }

    @Test
    void 허용되지_않은_열거형은_거절한다() {
        var r = run("jn.truckType", "MEDIUM_3TON");
        assertFalse(r.ok());
        assertEquals("OUT_OF_CLOSURE", r.errorCode());
        assertTrue(r.message().contains("LARGE_5TON"), "허용값을 알려줘야 다시 답할 수 있다");
    }

    @Test
    void 정수를_받는다() {
        var r = run("jn.truckCount", "3");
        assertTrue(r.ok());
        assertEquals(3, r.value());
    }

    @Test
    void 범위를_벗어난_정수는_거절한다() {
        var r = run("jn.truckCount", "0");
        assertFalse(r.ok());
        assertEquals("OUT_OF_RANGE", r.errorCode());
    }

    @Test
    void 상한을_넘는_건물수는_거절한다() {
        var r = run("jn.numBuildings", "27");
        assertFalse(r.ok());
        assertEquals("OUT_OF_RANGE", r.errorCode());
        assertTrue(r.message().contains("26"));
    }

    @Test
    void 숫자가_아니면_거절한다() {
        var r = run("jn.truckCount", "세 대");
        assertFalse(r.ok());
        assertEquals("NOT_A_NUMBER", r.errorCode());
    }

    @Test
    void 빈_답변은_거절한다() {
        var r = run("jn.truckCount", "   ");
        assertFalse(r.ok());
        assertEquals("EMPTY_ANSWER", r.errorCode());
    }

    @Test
    void 실제도로_모드는_선택지에_없어_거절된다() {
        var r = run("jn.travelTimeMode", "OSRM_HYBRID");
        assertFalse(r.ok());
        assertEquals("OUT_OF_CLOSURE", r.errorCode());
    }
}
