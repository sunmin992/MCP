package com.wastesim.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinRoundsTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog, new AnswerNormalizer());

    private int rounds(Map<String, Object> answers) {
        return MinRounds.of(planner.plan(answers, Map.of()), catalog);
    }

    @Test
    void 조건부_결정이_모두_비활성이면_1() {
        assertEquals(1, rounds(Map.of("truckCount", 1, "numBuildings", 4,
                "trafficMode", "IGNORE", "travelTimeMode", "LEGACY_CONSTANT")));
    }

    @Test
    void 배차간격이_살면_2() {
        assertEquals(2, rounds(Map.of("truckCount", 3, "numBuildings", 4)));
    }

    @Test
    void 구역_연쇄면_3() {
        assertEquals(3, rounds(Map.of("travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 6,
                "zoneAssignmentRule", "CONTIGUOUS")));
    }

    @Test
    void 자기_답을_읽는_조건은_간선이_아니다() {
        var plan = planner.plan(Map.of("collectionTimesMinutes", List.of(360, 1080)), Map.of());
        assertEquals(0, MinRounds.levels(plan, catalog).get("jn.collectionTimes"));
    }

    @Test
    void 비교_변수면_실행별_최댓값() {
        var a = planner.planAcross(Map.of("numBuildings", 4), Map.of(), "truckCount", List.of(1, 3));
        assertEquals(2, a.minRounds());
    }
}
