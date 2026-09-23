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

    /**
     * 13개 템플릿 전부가 FILLED 또는 NOT_GENERATED 로만 떨어지도록 답을 채운다.
     * UNFILLED·DEFERRED 가 하나도 없어야 계획이 완결이다.
     */
    private Map<String, Object> 완결된_답변() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("truckType", "LARGE_5TON");
        answers.put("truckCount", 1);
        answers.put("collectionTimeMinutes", 720);
        answers.put("trafficMode", "IGNORE");
        answers.put("travelTimeMode", "LEGACY_CONSTANT");
        answers.put("numBuildings", 4);
        answers.put("residentsPerBuilding", 25);
        answers.put("days", 30);
        answers.put("seeds", 30);
        // travelTimeMode 가 ZONE_PROXY_HYBRID 가 아니므로 zoneAssignmentRule 자체는 NOT_GENERATED 지만,
        // intraZoneTravel 의 CONTIGUOUS_ZONE_RULE 은 zoneAssignmentRule 값을 직접 보므로 채워 둬야
        // UNKNOWN(DEFERRED) 이 아니라 INACTIVE(NOT_GENERATED) 로 판정된다.
        answers.put("zoneAssignmentRule", "ROUND_ROBIN");
        return answers;
    }

    @Test
    void 모든_결정이_채워지면_계획은_완결이다() {
        SubtaskPlan plan = planner.plan(완결된_답변());
        assertTrue(plan.complete(), "UNFILLED 도 DEFERRED 도 없어야 완결이다");
    }

    @Test
    void 보류가_남아_있으면_계획은_완결이_아니다() {
        // 아무 답도 없으면 배차간격·교통프로파일·구역배정규칙·구역내이동시간이 전부 DEFERRED다.
        SubtaskPlan plan = planner.plan(Map.of());
        assertFalse(plan.complete(), "DEFERRED 를 완결로 뭉개면 보류가 영영 안 물어진다");
    }

    @Test
    void 미답이_남아_있으면_계획은_완결이_아니다() {
        // DEFERRED 를 만들지 않도록 조건을 전부 판정 가능하게 채우되, seeds 하나만 비운다.
        Map<String, Object> answers = 완결된_답변();
        answers.remove("seeds");
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.seeds").status());
        assertFalse(plan.complete(), "UNFILLED 가 남았는데 완결이라 하면 안 된다");
    }

    @Test
    void 상태별_개수가_섞인_계획에서_맞게_집계된다() {
        // FILLED 4개(truckType,truckCount,numBuildings,trafficMode) · NOT_GENERATED 1개(trafficProfile)
        // · DEFERRED 2개(zoneAssignmentRule,intraZoneTravel) · UNFILLED 나머지 6개.
        Map<String, Object> answers = new HashMap<>();
        answers.put("truckType", "SMALL_1TON");
        answers.put("truckCount", 3);
        answers.put("numBuildings", 4);
        answers.put("trafficMode", "IGNORE");
        SubtaskPlan plan = planner.plan(answers);

        Map<SubtaskStatus, Integer> counts = plan.counts();
        assertEquals(4, counts.size(), "네 상태 키가 다 있어야 한다 — 없으면 counts().get(DEFERRED) 가 null 이 된다");
        assertEquals(4, counts.get(SubtaskStatus.FILLED));
        assertEquals(6, counts.get(SubtaskStatus.UNFILLED));
        assertEquals(2, counts.get(SubtaskStatus.DEFERRED));
        assertEquals(1, counts.get(SubtaskStatus.NOT_GENERATED));
    }

    @Test
    void 답변맵에는_채워진_것만_담긴다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("truckType", "SMALL_1TON");
        answers.put("truckCount", 3);
        answers.put("numBuildings", 4);
        answers.put("trafficMode", "IGNORE");
        SubtaskPlan plan = planner.plan(answers);

        Map<String, Object> answered = plan.answeredValues();
        assertEquals(4, answered.size());
        assertEquals("SMALL_1TON", answered.get("truckType"));
        assertEquals(3, answered.get("truckCount"));
        assertEquals(4, answered.get("numBuildings"));
        assertEquals("IGNORE", answered.get("trafficMode"));
        assertFalse(answered.containsKey("dispatchIntervalMinutes"), "UNFILLED 는 담기지 않는다");
        assertFalse(answered.containsKey("trafficProfileId"), "NOT_GENERATED 는 담기지 않는다");
        assertFalse(answered.containsKey("zoneAssignmentRule"), "DEFERRED 는 담기지 않는다");
    }
}
