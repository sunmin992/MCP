package com.wastesim.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 템플릿이 읽히는가, 그리고 실행 대응이 실제 설정 필드를 가리키는가.
 *
 * <p>가리키는 필드가 없으면 PES 평탄화가 조용히 아무것도 안 하고, 사용자가 준 값이
 * 실행에 반영되지 않는다.
 */
class TemplateCatalogTest {

    private final TemplateCatalog catalog = new TemplateCatalog();

    @Test
    void 템플릿_열네개를_읽는다() {
        assertEquals(14, catalog.all().size());
    }

    @Test
    void 경로배정용량은_범위를_다시_정의하지_않는다() {
        SubtaskTemplate t = catalog.byId("jn.routeAvailableCapacity").orElseThrow();
        assertEquals("routeAvailableCapacityKg", t.configField());
        assertEquals("NUMBER", t.valueType());
        assertNull(t.min(), "상한이 차종에 달려 있어 SimulationConfigValidator 가 정본이다");
        assertNull(t.max(), "상한이 차종에 달려 있어 SimulationConfigValidator 가 정본이다");
        assertTrue(t.requiresExplicitAnswer(),
                "지정하지 않으면 가동률이 5톤 기준 1.8%로 죽는다 — 조용히 기본값을 쓰면 안 된다");
    }

    @Test
    void 배차간격의_생성조건은_차량두대이상이다() {
        SubtaskTemplate t = catalog.byId("jn.dispatchInterval").orElseThrow();
        assertEquals(GenerateCondition.EFFECTIVE_TRUCKS_AT_LEAST_2, t.generateWhen());
        assertEquals("dispatchIntervalMinutes", t.configField());
    }

    @Test
    void 이동시간_선택지에_실제도로가_없다() {
        SubtaskTemplate t = catalog.byId("jn.travelTimeMode").orElseThrow();
        assertEquals(List.of("LEGACY_CONSTANT", "ZONE_PROXY_HYBRID"), t.allowed(),
                "OSRM_HYBRID 는 지점 좌표가 없어 선택지에서 빠져야 한다");
    }

    @Test
    void 답변키로_찾을_수_있다() {
        assertTrue(catalog.byAnswerKey("truckCount").isPresent());
        assertTrue(catalog.byAnswerKey("없는키").isEmpty());
    }

    @Test
    void 모든_템플릿의_실행대응이_설정의_실제_게터를_가리킨다() throws Exception {
        for (SubtaskTemplate t : catalog.all()) {
            String field = t.configField();
            boolean found = false;
            for (var m : com.wastesim.model.SimulationConfig.class.getMethods()) {
                String n = m.getName();
                if (m.getParameterCount() == 0
                        && (n.equals("get" + capitalize(field)) || n.equals("is" + capitalize(field)))) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, t.templateId() + " 의 실행 대응 " + field + " 에 해당하는 게터가 없다");
        }
    }

    @Test
    void 답변키가_중복되지_않는다() {
        long distinct = catalog.all().stream().map(SubtaskTemplate::answerKey).distinct().count();
        assertEquals(catalog.all().size(), distinct, "같은 답변키를 두 템플릿이 쓰고 있다");
    }

    @Test
    void 템플릿_리소스가_없으면_빈_카탈로그로_넘어가지_않고_예외를_던진다() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TemplateCatalog("/ses/존재하지-않는-템플릿.json"));
        assertNotNull(ex.getMessage(), "예외 메시지가 없다 — 운영에서 원인을 알 수 없다");
        assertTrue(ex.getMessage().contains("/ses/존재하지-않는-템플릿.json"),
                "예외 메시지가 어떤 리소스가 없는지 말하지 않는다: " + ex.getMessage());
    }

    @Test
    void sesId_가_없으면_빈_문자열로_넘어가지_않고_예외를_던진다() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TemplateCatalog("/ses/no-ses-id.json"));
        assertTrue(ex.getMessage().contains("sesId"),
                "어느 필드가 없는지 말하지 않는다: " + ex.getMessage());
    }

    @Test
    void 정상_리소스는_두_식별자를_모두_돌려준다() {
        assertEquals("jangnyang-ses", catalog.sesId());
        assertEquals("1.0.0", catalog.sesVersion());
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
