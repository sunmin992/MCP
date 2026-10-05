package com.wastesim.templategen;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 규칙 하나하나를 가상 시뮬레이터 소스({@code src/test/resources/fixture})로 시험한다.
 * 장량동 코드가 바뀌어도 이 시험은 규칙 자체가 맞는지만 본다.
 */
class TemplateGeneratorTest {

    static TemplateGenerator.Result result;

    @BeforeAll
    static void generate() throws Exception {
        TemplateGenerator gen = new TemplateGenerator(Path.of("src/test/resources/fixture"));
        result = gen.generate("DemoConfig", List.of("DemoValidator"));
    }

    static GeneratedTemplate t(String field) {
        return result.templates().stream().filter(x -> x.configField().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError("템플릿이 없다: " + field));
    }

    @Test
    void 세터가_있는_필드만_후보가_된다() {
        assertTrue(result.templates().stream().noneMatch(x -> x.configField().equals("internalCounter")));
    }

    @Test
    void 옮길_수_없는_타입은_이유와_함께_뺀다() {
        assertTrue(result.skipped().containsKey("curve"));
        assertTrue(result.skipped().get("curve").contains("double[]"));
    }

    @Test
    void 오류를_내는_if_의_비교가_범위가_된다() {
        assertEquals(1.0, t("robots").min());
        assertEquals(50.0, t("robots").max(),
                "오류를 내지 않는 if(> 99)와 바깥 조건(> 5)은 범위가 아니고, 별칭 게터의 > 50 만 상한이다");
    }

    @Test
    void 별칭_게터는_본문을_따라_필드를_찾는다() {
        assertTrue(t("robots").evidence().stream().anyMatch(e -> e.contains("c.getFleetSize() > 50")),
                t("robots").evidence().toString());
    }

    @Test
    void 다른_객체의_같은_이름_게터는_설정_필드가_아니다() {
        assertTrue(t("robots").evidence().stream().noneMatch(e -> e.contains("part.getRobots")),
                "part.getRobots() > 3 을 robots 의 상한으로 읽으면 안 된다");
    }

    @Test
    void 지역_변수와_다른_클래스_상수를_거쳐도_범위를_푼다() {
        GeneratedTemplate s = t("speed");
        assertEquals(0.0, s.min());
        assertTrue(s.minExclusive(), "s <= 0 을 거절하면 0 초과다");
        assertEquals(60.0, s.max(), "DemoConfig.LIMIT = 10 * 6");
        assertFalse(s.maxExclusive());
    }

    @Test
    void 상수가_아닌_경계는_비우고_검토로_넘긴다() {
        GeneratedTemplate b = t("budgetKg");
        assertEquals(0.0, b.min());
        assertNull(b.max());
        assertTrue(b.needsReview().stream().anyMatch(r -> r.contains("budget > cap")), b.needsReview().toString());
        assertEquals("kg", b.unit());
    }

    @Test
    void 목록_원소의_범위를_목록_필드에_단다() {
        GeneratedTemplate s = t("slotsMinutes");
        assertEquals("INTEGER_LIST", s.valueType());
        assertEquals(0.0, s.min());
        assertEquals(1439.0, s.max());
        assertEquals("minute", s.unit());
    }

    @Test
    void 해석_메서드의_fromName_이_선택지를_준다() {
        GeneratedTemplate m = t("mode");
        assertEquals("ENUM", m.valueType());
        assertEquals(List.of("FAST", "SAFE", "ECO"), m.allowed());
        assertEquals("FAST", m.defaultValue(), "Mode.FAST.name() 은 \"FAST\"");
    }

    @Test
    void 목록_원소의_valueOf_가_선택지를_준다() {
        GeneratedTemplate c = t("crew");
        assertEquals("ENUM_LIST", c.valueType());
        assertEquals(List.of("PILOT", "MEDIC"), c.allowed());
    }

    @Test
    void enum_으로_해석되지_않는_문자열은_자유_문자열이다() {
        assertEquals("STRING", t("color").valueType());
        assertEquals("red", t("color").defaultValue());
    }

    @Test
    void 기본값과_질문_초안을_필드에서_읽는다() {
        GeneratedTemplate r = t("robots");
        assertEquals(2L, r.defaultValue());
        assertEquals("투입할 로봇 수.", r.question(), "Javadoc 첫 문장만 쓴다");
        assertNull(t("budgetKg").defaultValue());
    }

    @Test
    void 초기값이_null_이면_설정_클래스의_대체값을_따라간다() {
        // if (crew == null || crew.isEmpty()) return Role.defaults();  →  Arrays.asList(PILOT)
        GeneratedTemplate crew = t("crew");
        assertEquals(List.of("PILOT"), crew.defaultValue());
        assertTrue(crew.defaultBasis().contains("resolveCrew()"), crew.defaultBasis());
        assertTrue(crew.needsReview().stream().anyMatch(r -> r.contains("대체값에서 찾음")));
    }

    @Test
    void 삼항식의_대체값도_따라간다() {
        // profileId == null ? "basic" : profileId
        assertEquals("basic", t("profileId").defaultValue());
    }

    @Test
    void 다른_필드로_대신하면_기본값을_채우지_않고_알린다() {
        // if (slotsMinutes 가 있으면) return slotsMinutes;  return List.of(startMinutes);
        GeneratedTemplate slots = t("slotsMinutes");
        assertNull(slots.defaultValue(), "건물 수만큼 같은 값은 고정 기본값이 아니다");
        assertTrue(slots.needsReview().stream().anyMatch(r -> r.contains("다른 필드 startMinutes")),
                slots.needsReview().toString());
    }

    @Test
    void 대체값이_없으면_그대로_null_이다() {
        assertNull(t("budgetKg").defaultValue());
        assertTrue(t("budgetKg").needsReview().stream().anyMatch(r -> r.startsWith("기본값: 초기값이 null 이다")));
    }

    @Test
    void 불리언은_선택지_이름을_사람이_정하라고_표시한다() {
        GeneratedTemplate v = t("verbose");
        assertEquals("BOOLEAN", v.valueType());
        assertEquals(false, v.defaultValue());
        assertTrue(v.needsReview().stream().anyMatch(r -> r.startsWith("값 종류")));
    }

    @Test
    void 생성_조건은_뽑지_않았다고_밝힌다() {
        for (GeneratedTemplate x : result.templates()) {
            assertEquals("ALWAYS", x.generateWhen());
            assertTrue(x.needsReview().stream().anyMatch(r -> r.startsWith("생성 조건")), x.configField());
        }
    }

    @Test
    void 값마다_근거_자리를_남긴다() {
        assertTrue(t("robots").evidence().stream().anyMatch(e -> e.startsWith("하한 DemoValidator.java:13")),
                t("robots").evidence().toString());
    }
}
