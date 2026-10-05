package com.wastesim.templategen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 장량동 시뮬레이터 소스에 돌려, 손으로 쓴 템플릿과의 대조가 성립하는지 본다.
 *
 * <p>일치율 자체를 고정하지 않는다 — 템플릿이나 엔진이 바뀌면 달라지는 것이 정상이다.
 * 대신 "코드에서 확실히 나와야 하는 값" 과 "어긋나는 것이 맞는 값" 을 확인한다.
 */
class JangnyangSourceTest {

    static TemplateGenerator.Result result;
    static TemplateComparator.Report report;

    @BeforeAll
    static void run() throws Exception {
        TemplateGenerator gen = new TemplateGenerator(Path.of("../simulator/src/main/java"));
        result = gen.generate("com.wastesim.model.SimulationConfig",
                List.of("com.wastesim.tool.SimulationConfigValidator"),
                List.of("com.wastesim.service.SimulationService", "com.wastesim.simulation.SimulationEngine",
                        "com.wastesim.simulation.RoutePlanner", "com.wastesim.simulation.TravelTimeCalculator"));
        JsonNode hand = new ObjectMapper().readTree(
                Path.of("../simulator/src/main/resources/ses/jangnyang-templates.json").toFile());
        Map<String, String> dictionary = new LinkedHashMap<>();
        new ObjectMapper().readTree(Path.of("conditions/jangnyang-generate-conditions.json").toFile())
                .path("conditions").fields().forEachRemaining(e -> dictionary.put(e.getKey(), e.getValue().asText()));
        report = new TemplateComparator().compare(hand, result, dictionary);
    }

    static GeneratedTemplate t(String field) {
        return result.templates().stream().filter(x -> x.configField().equals(field)).findFirst().orElseThrow();
    }

    @Test
    void 손으로_쓴_템플릿마다_짝이_있다() {
        assertTrue(report.rows().stream().allMatch(TemplateComparator.Row::found),
                "설정 필드가 코드에 없으면 템플릿이 실행에 실리지 않는다");
    }

    @Test
    void 검증기의_범위가_그대로_나온다() {
        assertEquals(1.0, t("days").min());
        assertEquals(365.0, t("days").max());
        assertEquals(1.0, t("numBuildings").min());
        assertEquals(26.0, t("numBuildings").max());
        assertEquals(0.0, t("collectionTimesMinutes").min());
        assertEquals(1439.0, t("collectionTimesMinutes").max(), "MAX_MINUTE_OF_DAY 상수를 풀어야 한다");
        assertEquals(1.0, t("numTrucks").min(), "검증기는 별칭 getTruckCount() 로 검사한다");
    }

    @Test
    void enum_선택지가_코드_전체로_나온다() {
        assertEquals(List.of("LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"), t("truckType").allowed());
        assertEquals(5, t("occupationMix").allowed().size());
        // 코드에는 OSRM_HYBRID 가 있지만 손으로 쓴 템플릿은 뺐다 — 좁히는 것은 제공자의 판단이다.
        assertTrue(t("travelTimeMode").allowed().contains("OSRM_HYBRID"));
    }

    @Test
    void 차종에_달린_상한은_비우고_검토로_넘긴다() {
        GeneratedTemplate cap = t("routeAvailableCapacityKg");
        assertNull(cap.max());
        assertTrue(cap.needsReview().stream().anyMatch(r -> r.contains("truckType.capacityKg")));
    }

    @Test
    void 분리배출_유형의_같은_이름_게터를_섞지_않는다() {
        assertTrue(t("threshold").evidence().stream().noneMatch(e -> e.contains("w.getThreshold")),
                t("threshold").evidence().toString());
    }

    @Test
    void 대조표의_합계가_칸_수와_맞는다() {
        assertEquals(report.rows().size() * TemplateComparator.SCORED.size(), report.total());
        assertTrue(report.matched() <= report.total());
        assertTrue(report.matchedWithValue() <= report.totalWithValue());
    }

    static TemplateComparator.ConditionRow cond(String templateId) {
        return report.conditions().stream().filter(c -> c.templateId().equals(templateId)).findFirst().orElseThrow();
    }

    @Test
    void 생성_조건이_손으로_쓴_것과_같은_것() {
        assertEquals(Truth.Relation.EQUIVALENT, cond("jn.trafficProfile").relation(), "삼항식 isTrafficEnabled() ? … : null");
        assertEquals(Truth.Relation.EQUIVALENT, cond("jn.collectionTime").relation(), "resolveCollectionSlots 의 조기 반환");
    }

    @Test
    void 뽑은_생성_조건은_물어야_할_때를_빠뜨리지_않는다() {
        assertEquals(0, report.conditionsMatching(Truth.Relation.STRONGER),
                "더 좁은 조건은 필요한 질문을 건너뛴다: " + report.conditions());
        assertEquals(0, report.conditionsMatching(Truth.Relation.DIFFERENT));
    }

    @Test
    void 배차_간격은_실행_조건으로_남는다() {
        assertTrue(cond("jn.dispatchInterval").hints().stream().anyMatch(h -> h.startsWith("반복 변수 k")),
                cond("jn.dispatchInterval").hints().toString());
    }

    @Test
    void 결과_라벨로만_읽는_자리는_조건에서_빠진다() {
        assertTrue(t("collectionTimeMinutes").readSites().stream().anyMatch(r -> r.contains("(결과 기록)")),
                t("collectionTimeMinutes").readSites().toString());
    }
}
