package com.wastesim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.template.SubtaskInstance;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.SubtaskStatus;
import com.wastesim.template.TemplateCatalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 시험이 build_scenario 요청을 만들 때, 시험의 요점과 상관없는 결정을 장량동 기준값으로 채운다.
 *
 * <p>build_scenario 는 필요한 결정이 모두 정해지고 값마다 출처가 있어야 시나리오를 만든다. 시험마다
 * 차량 유형 · 세대 수 · 직업 구성까지 일일이 적으면 무엇을 시험하는지가 묻힌다. 시험이 적은 값은
 * 덮지 않는다.
 */
public final class TestScenarioRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SubtaskPlanner PLANNER = new SubtaskPlanner(new TemplateCatalog());

    /** 물어야 할 결정이 열리면 채울 값. 기본값이 없는 결정(적재 몫 · 구역 배정 · 구역 내 이동)도 여기서 정한다. */
    private static final Map<String, Object> FILL = new LinkedHashMap<>();
    static {
        FILL.put("truckType", "LARGE_5TON");
        FILL.put("truckCount", 1);
        FILL.put("dispatchIntervalMinutes", 0);
        FILL.put("collectionTimeMinutes", 720);
        FILL.put("trafficMode", "IGNORE");
        FILL.put("trafficProfileId", "jangryang-weekday");
        FILL.put("travelTimeMode", "LEGACY_CONSTANT");
        FILL.put("zoneAssignmentRule", "ROUND_ROBIN");
        FILL.put("intraZoneTravelMinutes", 2);
        FILL.put("numBuildings", 4);
        FILL.put("residentsPerBuilding", 25);
        FILL.put("occupationMix", java.util.List.of("BlueCollar", "Student", "Housewife"));
        FILL.put("days", 30);
        FILL.put("seeds", 30);
        FILL.put("routeAvailableCapacityKg", 150);
    }

    private TestScenarioRequests() {}

    /** 요청을 고쳐 돌려준다. 빠진 결정을 채우고, 출처가 없는 값은 USER 로 적는다. */
    public static ObjectNode completed(ObjectNode args) {
        ObjectNode values = args.has("values") ? (ObjectNode) args.get("values") : args.putObject("values");
        ObjectNode origins = args.has("origins") ? (ObjectNode) args.get("origins") : args.putObject("origins");
        String variable = args.path("variableAnswerKey").asText();
        JsonNode first = args.path("variableValues").path(0);

        // 채운 값이 다른 결정을 열 수 있으므로(예: 교통 반영 → 프로파일) 더 채울 것이 없을 때까지 돈다.
        for (int round = 0; round < FILL.size(); round++) {
            Map<String, Object> answers = MAPPER.convertValue(values, Map.class);
            if (!first.isMissingNode()) answers.put(variable, MAPPER.convertValue(first, Object.class));
            boolean filled = false;
            for (SubtaskInstance i : PLANNER.plan(answers).instances()) {
                if (i.status() == SubtaskStatus.UNFILLED && FILL.containsKey(i.answerKey())
                        && !i.answerKey().equals(variable)) {
                    values.set(i.answerKey(), MAPPER.valueToTree(FILL.get(i.answerKey())));
                    filled = true;
                }
            }
            if (!filled) break;
        }
        values.fieldNames().forEachRemaining(k -> { if (!origins.has(k)) origins.put(k, "USER"); });
        return args;
    }
}
