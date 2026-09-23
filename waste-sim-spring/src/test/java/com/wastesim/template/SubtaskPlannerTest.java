package com.wastesim.template;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변에 따라 서브태스크 집합 자체가 달라지는가.
 *
 * <p>이것이 고정 질문지와 갈리는 자리다. 고정 질문지는 언제나 같은 수를 묻는다.
 */
class SubtaskPlannerTest {

    private final SubtaskPlanner planner = new SubtaskPlanner(new TemplateCatalog());

    private SubtaskInstance find(SubtaskPlan plan, String templateId) {
        return plan.instances().stream()
                .filter(i -> i.templateId().equals(templateId))
                .findFirst().orElseThrow(() -> new AssertionError(templateId + " 인스턴스가 없다"));
    }

    @Test
    void 답변이_없으면_배차간격은_보류다() {
        SubtaskPlan plan = planner.plan(Map.of());
        assertEquals(SubtaskStatus.DEFERRED, find(plan, "jn.dispatchInterval").status(),
                "차량 수를 모르면 생성 조건을 판정할 수 없다");
    }

    @Test
    void 차량이_한대면_배차간격은_생성되지_않는다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("numBuildings", 4);
        answers.put("truckCount", 1);
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.NOT_GENERATED, find(plan, "jn.dispatchInterval").status());
        assertTrue(plan.toAsk().stream().noneMatch(i -> i.templateId().equals("jn.dispatchInterval")),
                "생성되지 않은 결정을 물으면 안 된다");
    }

    @Test
    void 차량이_세대면_배차간격을_묻는다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("numBuildings", 4);
        answers.put("truckCount", 3);
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.dispatchInterval").status());
        assertTrue(plan.toAsk().stream().anyMatch(i -> i.templateId().equals("jn.dispatchInterval")));
    }

    @Test
    void 교통을_끄면_프로파일은_생성되지_않는다() {
        SubtaskPlan plan = planner.plan(Map.of("trafficMode", "IGNORE"));
        assertEquals(SubtaskStatus.NOT_GENERATED, find(plan, "jn.trafficProfile").status());
    }

    @Test
    void 교통을_켜면_프로파일을_묻는다() {
        SubtaskPlan plan = planner.plan(Map.of("trafficMode", "APPLY"));
        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.trafficProfile").status());
    }

    @Test
    void 이미_답한_결정은_채워진_상태다() {
        SubtaskPlan plan = planner.plan(Map.of("truckType", "SMALL_1TON"));
        SubtaskInstance i = find(plan, "jn.truckType");
        assertEquals(SubtaskStatus.FILLED, i.status());
        assertEquals("SMALL_1TON", i.value());
        assertTrue(plan.toAsk().stream().noneMatch(x -> x.templateId().equals("jn.truckType")),
                "이미 답한 것을 다시 물으면 중복 질문이다");
    }

    @Test
    void 구역근사_다섯동이면_구역배정규칙을_묻는다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("travelTimeMode", "ZONE_PROXY_HYBRID");
        answers.put("numBuildings", 5);
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.zoneAssignmentRule").status());
    }

    @Test
    void 구역근사_네동이면_구역배정규칙을_묻지_않는다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("travelTimeMode", "ZONE_PROXY_HYBRID");
        answers.put("numBuildings", 4);
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.NOT_GENERATED, find(plan, "jn.zoneAssignmentRule").status());
    }

    @Test
    void 덩어리배정이면_구역내이동시간을_묻는다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("zoneAssignmentRule", "CONTIGUOUS");
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.intraZoneTravel").status());
    }

    @Test
    void 조건이_연쇄한다() {
        // 구역 근사 + 6동 → 배정 규칙을 묻는다. 덩어리로 답하면 구역 내 이동시간이 새로 생긴다.
        Map<String, Object> a1 = new HashMap<>();
        a1.put("travelTimeMode", "ZONE_PROXY_HYBRID");
        a1.put("numBuildings", 6);
        assertEquals(SubtaskStatus.DEFERRED, find(planner.plan(a1), "jn.intraZoneTravel").status());

        Map<String, Object> a2 = new HashMap<>(a1);
        a2.put("zoneAssignmentRule", "CONTIGUOUS");
        assertEquals(SubtaskStatus.UNFILLED, find(planner.plan(a2), "jn.intraZoneTravel").status());

        Map<String, Object> a3 = new HashMap<>(a1);
        a3.put("zoneAssignmentRule", "ROUND_ROBIN");
        assertEquals(SubtaskStatus.NOT_GENERATED, find(planner.plan(a3), "jn.intraZoneTravel").status());
    }
}
