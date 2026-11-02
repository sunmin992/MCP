package com.wastesim.template;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변에 따라 서브태스크 집합 자체가 달라지는가.
 *
 * <p>이것이 고정 질문지와 갈리는 자리다. 고정 질문지는 언제나 같은 수를 묻는다.
 */
class SubtaskPlannerTest {

    private final SubtaskPlanner planner = new SubtaskPlanner(new TemplateCatalog(), new AnswerNormalizer());

    private SubtaskInstance find(SubtaskPlan plan, String templateId) {
        return plan.instances().stream()
                .filter(i -> i.templateId().equals(templateId))
                .findFirst().orElseThrow(() -> new AssertionError(templateId + " 인스턴스가 없다"));
    }

    @Test
    void 구간상수_모드에서_필요한_값을_다_채우면_완결이다() {
        // 실제 모델이 보내는 모양 — 생성되지 않는 구역 배정 규칙은 보내지 않는다.
        Map<String, Object> answers = new HashMap<>();
        answers.put("truckType", "SMALL_1TON");
        answers.put("truckCount", 3);
        answers.put("dispatchIntervalMinutes", 15);
        answers.put("collectionTimeMinutes", 720);
        answers.put("trafficMode", "APPLY");
        answers.put("trafficProfileId", "jangryang-weekday");
        answers.put("travelTimeMode", "LEGACY_CONSTANT");
        answers.put("numBuildings", 4);
        answers.put("residentsPerBuilding", 25);
        answers.put("days", 30);
        answers.put("seeds", 30);
        answers.put("routeAvailableCapacityKg", 150);
        answers.put("binCapacityKg", 30.0);
        answers.put("complaintThreshold", 0.8);
        answers.put("occupationMix", List.of("BlueCollar", "Student", "Housewife"));
        answers.put("dischargeTimeMode", "PAPER_BASELINE");
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.NOT_GENERATED, find(plan, "jn.intraZoneTravel").status());
        assertTrue(plan.complete(), "필요한 값을 다 채웠는데 완결이 아니면 LLM 이 오지 않을 질문을 기다린다: "
                + plan.counts());
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
    void 덩어리배정이어도_구역근사를_모르면_구역내이동시간은_보류다() {
        // 배정 규칙 자체가 아직 보류인데 그 아래 결정만 물으면 순서가 뒤집힌다. 묻는 경우는 조건이_연쇄한다 가 본다.
        Map<String, Object> answers = new HashMap<>();
        answers.put("zoneAssignmentRule", "CONTIGUOUS");
        SubtaskPlan plan = planner.plan(answers);
        assertEquals(SubtaskStatus.DEFERRED, find(plan, "jn.intraZoneTravel").status());
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
        // 경로 배정용량은 ALWAYS 라 항상 생성된다 — 채우지 않으면 계획이 완결이 아니다.
        answers.put("routeAvailableCapacityKg", 150.0);
        answers.put("binCapacityKg", 30.0);
        answers.put("complaintThreshold", 0.8);
        answers.put("occupationMix", List.of("BlueCollar", "Student", "Housewife"));
        answers.put("dischargeTimeMode", "PAPER_BASELINE");
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
    void null_답변은_완료로_처리하지_않는다() {
        Map<String, Object> answers = 완결된_답변();
        answers.put("seeds", null);

        SubtaskPlan plan = planner.plan(answers);

        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.seeds").status());
        assertFalse(plan.complete());
        assertFalse(plan.answeredValues().containsKey("seeds"));
    }

    @Test
    void null_조건값은_먼저_답할_항목에_포함된다() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("numBuildings", 4);
        answers.put("truckCount", null);

        assertEquals(SubtaskStatus.DEFERRED, find(planner.plan(answers), "jn.dispatchInterval").status());
        assertEquals(List.of("truckCount"), planner.waitingOn("dispatchIntervalMinutes", answers));
    }

    @Test
    void 상태별_개수가_섞인_계획에서_맞게_집계된다() {
        // FILLED 4개(truckType,truckCount,numBuildings,trafficMode) · NOT_GENERATED 4개(trafficProfile,
        // collectionTimes — 다회 수거를 말하지 않았다 — 그리고 zoneAssignmentRule,intraZoneTravel — 4동이면
        // 이동시간 방식을 몰라도 구역 배정이 걸리지 않는다) · DEFERRED 0개 · UNFILLED 나머지 11개
        // (collectionTime·dispatchInterval·travelTimeMode·residentsPerBuilding·days·seeds
        //  ·routeAvailableCapacity·occupationMix·dischargeTimeMode·binCapacity·complaintThreshold).
        Map<String, Object> answers = new HashMap<>();
        answers.put("truckType", "SMALL_1TON");
        answers.put("truckCount", 3);
        answers.put("numBuildings", 4);
        answers.put("trafficMode", "IGNORE");
        SubtaskPlan plan = planner.plan(answers);

        Map<SubtaskStatus, Integer> counts = plan.counts();
        assertEquals(4, counts.size(), "네 상태 키가 다 있어야 한다 — 없으면 counts().get(DEFERRED) 가 null 이 된다");
        assertEquals(4, counts.get(SubtaskStatus.FILLED));
        assertEquals(11, counts.get(SubtaskStatus.UNFILLED));
        assertEquals(0, counts.get(SubtaskStatus.DEFERRED));
        assertEquals(4, counts.get(SubtaskStatus.NOT_GENERATED));
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

    @Test
    void 범위_밖_값은_UNFILLED_와_사유() {
        SubtaskPlan plan = planner.plan(Map.of("numBuildings", 99));
        SubtaskInstance i = find(plan, "jn.numBuildings");
        assertEquals(SubtaskStatus.UNFILLED, i.status());
        assertNull(i.value());
        assertEquals("OUT_OF_RANGE", i.reason().code());
        assertFalse(plan.complete());
    }

    @Test
    void 범위_안_값은_정규화되어_FILLED() {
        SubtaskInstance i = find(planner.plan(Map.of("numBuildings", "4")), "jn.numBuildings");
        assertEquals(SubtaskStatus.FILLED, i.status());
        assertEquals(Integer.valueOf(4), i.value());
        assertEquals("NOT_CHECKED", i.evidence());
        assertNull(i.reason());
    }

    @Test
    void 형식에서_떨어진_값은_조건_판정에_쓰지_않는다() {
        SubtaskPlan plan = planner.plan(Map.of("truckCount", "세 대", "numBuildings", 4));
        assertEquals(SubtaskStatus.UNFILLED, find(plan, "jn.truckCount").status());
        assertEquals("NOT_A_NUMBER", find(plan, "jn.truckCount").reason().code());
        assertEquals(SubtaskStatus.DEFERRED, find(plan, "jn.dispatchInterval").status());
        assertTrue(planner.waitingOn("dispatchIntervalMinutes", Map.of("truckCount", "세 대", "numBuildings", 4))
                .contains("truckCount"), "형식에서 떨어진 키도 먼저 정해야 할 키다");
    }

    @Test
    void 비교_값_하나가_범위_밖이면_변수는_UNFILLED() {
        SubtaskPlanner.Across a = planner.planAcross(Map.of(), Map.of(), "numBuildings", List.of(4, 99));
        SubtaskInstance i = find(a.plan(), "jn.numBuildings");
        assertEquals(SubtaskStatus.UNFILLED, i.status());
        assertEquals("OUT_OF_RANGE", i.reason().code());
        assertEquals(List.of(99), a.onlyFor().get("numBuildings"));
    }

    @Test
    void 허용값_밖_열거값은_UNFILLED() {
        SubtaskInstance i = find(planner.plan(Map.of("truckType", "TANK")), "jn.truckType");
        assertEquals(SubtaskStatus.UNFILLED, i.status());
        assertEquals("OUT_OF_CLOSURE", i.reason().code());
    }

    @Test
    void 비교_값이_모두_범위_밖이면_실패_값을_모두_알린다() {
        SubtaskPlanner.Across a = planner.planAcross(Map.of(), Map.of(), "numBuildings", List.of(99, 100));
        SubtaskInstance i = find(a.plan(), "jn.numBuildings");
        assertEquals(SubtaskStatus.UNFILLED, i.status());
        assertEquals("OUT_OF_RANGE", i.reason().code());
        assertTrue(i.reason().message().contains("99") && i.reason().message().contains("100"), i.reason().message());
        assertEquals(List.of(99, 100), a.onlyFor().get("numBuildings"));
    }

    @Test
    void 형식이_틀린_다회_수거_시각은_가려지지_않고_사유와_함께_묻는다() {
        SubtaskPlan plan = planner.plan(Map.of("collectionTimesMinutes", List.of(660, 2000)));
        SubtaskInstance times = find(plan, "jn.collectionTimes");
        assertEquals(SubtaskStatus.UNFILLED, times.status());
        assertEquals("OUT_OF_RANGE", times.reason().code());
        assertEquals(SubtaskStatus.NOT_GENERATED, find(plan, "jn.collectionTime").status());
    }

    @Test
    void 정수형에_온_정수값_실수는_정수로_채우고_소수는_막는다() {
        SubtaskInstance whole = find(planner.plan(Map.of("numBuildings", 4.0)), "jn.numBuildings");
        assertEquals(SubtaskStatus.FILLED, whole.status());
        assertEquals(Integer.valueOf(4), whole.value());
        assertEquals(List.of(660, 1380), find(planner.plan(Map.of("collectionTimesMinutes", List.of(660.0, 1380.0))),
                "jn.collectionTimes").value());
        SubtaskInstance frac = find(planner.plan(Map.of("numBuildings", 4.5)), "jn.numBuildings");
        assertEquals(SubtaskStatus.UNFILLED, frac.status());
        assertEquals("NOT_A_NUMBER", frac.reason().code());
    }
}
