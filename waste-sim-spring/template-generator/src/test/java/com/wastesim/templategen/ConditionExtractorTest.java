package com.wastesim.templategen;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 생성 조건 추출 규칙을 가상 엔진({@code fixture/demo/DemoEngine.java})으로 시험한다.
 * 규칙마다 필드 하나를 쓰고, 기대하는 조건과 진리표로 맞댄다.
 */
class ConditionExtractorTest {

    static TemplateGenerator.Result result;
    static Map<String, Truth.Kind> kinds;

    @BeforeAll
    static void generate() throws Exception {
        TemplateGenerator gen = new TemplateGenerator(Path.of("src/test/resources/fixture"));
        result = gen.generate("DemoConfig", List.of("DemoValidator"), List.of("DemoService", "DemoEngine"));
        kinds = result.kinds();
    }

    static GeneratedTemplate t(String field) {
        return result.templates().stream().filter(x -> x.configField().equals(field)).findFirst().orElseThrow();
    }

    static void assertEquivalent(String expected, String field) {
        Truth.Comparison c = Truth.compare(Cond.parse(expected), t(field).condition(), kinds);
        assertEquals(Truth.Relation.EQUIVALENT, c.relation(),
                field + ": 뽑은 식 " + t(field).generateWhenExpr() + " · 반례 " + c.counterexample());
    }

    @Test
    void 삼항식의_조건_아래에서만_읽으면_그_조건이다() {
        assertEquivalent("verbose == true", "profileId");
        assertNull(t("profileId").generateWhen(), "조건부면 이름 붙은 조건을 사람이 고른다");
    }

    @Test
    void 부르는_자리의_조건이_메서드_안의_읽기로_이어진다() {
        // budgetKg 는 fast() 안에서 읽고, fast() 는 mode == FAST 일 때만 불린다.
        assertEquivalent("mode == FAST", "budgetKg");
    }

    @Test
    void 설정_클래스의_조기_반환은_나머지_자리의_조건이_된다() {
        assertEquivalent("!given(slotsMinutes)", "startMinutes");
    }

    @Test
    void 반복_변수에_곱해지면_실행_조건으로_알린다() {
        GeneratedTemplate step = t("stepMinutes");
        assertEquals("ALWAYS", step.generateWhen(), "설정만으로는 갈리지 않는다");
        assertTrue(step.conditionHints().stream().anyMatch(h -> h.startsWith("반복 변수 k ≥ 1")),
                step.conditionHints().toString());
    }

    @Test
    void 검증기가_막는_설정에서만_갈리는_조건은_생성_조건이_아니다() {
        // run() 첫 줄의 if (speed <= 0) throw 가 모든 읽기를 감싸지만, 검증기가 speed > 0 을 강제한다.
        assertEquals("ALWAYS", t("robots").generateWhen(), t("robots").generateWhenExpr());
    }

    @Test
    void 결과에_그대로_기록만_하는_읽기는_영향이_아니다() {
        assertEquals("false", t("color").generateWhenExpr(), "new DemoResult(cfg.getColor()) 는 기록이다");
        assertEquals("false", t("crew").generateWhenExpr(), "결과 기록만 하는 if 의 조건이다");
        assertTrue(t("color").readSites().stream().anyMatch(r -> r.contains("(결과 기록)")));
        assertTrue(t("color").needsReview().stream().anyMatch(r -> r.contains("읽지 않음")));
    }

    @Test
    void 계산값을_결과에_넣는_것은_기록이_아니다() {
        // result.add(fast(cfg)) — fast 의 계산은 시뮬레이션이다. budgetKg 가 사라지면 안 된다.
        assertNotEquals("false", t("budgetKg").generateWhenExpr());
    }

    @Test
    void 조건_안에서_읽는_필드는_그_자리에서_언제나_읽힌다() {
        // verbose 는 삼항식의 조건, mode 는 if 의 조건 — 둘 다 조건 없이 평가된다.
        assertEquals("ALWAYS", t("verbose").generateWhen(), t("verbose").generateWhenExpr());
        assertEquals("ALWAYS", t("mode").generateWhen(), t("mode").generateWhenExpr());
    }

    @Test
    void 엔진을_주지_않으면_생성_조건을_뽑지_않는다() throws Exception {
        TemplateGenerator.Result none = new TemplateGenerator(Path.of("src/test/resources/fixture"))
                .generate("DemoConfig", List.of("DemoValidator"));
        assertTrue(none.templates().stream().allMatch(x -> x.condition() == null && "ALWAYS".equals(x.generateWhen())));
    }
}
