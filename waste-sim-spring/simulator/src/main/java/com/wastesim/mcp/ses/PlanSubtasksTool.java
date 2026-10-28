package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.template.SubtaskPlan;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 현재 답변으로 서브태스크 집합을 계산해 돌려준다.
 *
 * <p>LLM 이 스스로 판정한 결과를 <b>서버가 대조</b>하는 자리다. 둘이 다르면 LLM 이
 * 생성 조건을 잘못 읽었다는 뜻이고, 그 차이 자체가 측정 대상이다.
 */
@Component
public class PlanSubtasksTool implements McpToolProvider {

    private final SubtaskPlanner planner;
    private final ObjectMapper mapper;

    public PlanSubtasksTool(SubtaskPlanner planner, ObjectMapper mapper) {
        this.planner = planner;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "plan_subtasks"; }

    @Override
    public String description() {
        return "현재까지 확정된 답변으로 어떤 서브태스크가 존재해야 하는지 계산한다. "
                + "생성 조건이 거짓이면 만들지 않고, 판정할 수 없으면 보류한다. 보류된 결정은 waitingOn 에 "
                + "먼저 정해야 할 답변키를 적는다 — UNFILLED 를 먼저 묻고, 보류는 그 뒤 다시 계산하면 풀린다. "
                + "비교할 실험 변수가 정해졌으면 build_scenario 와 같은 variableAnswerKey · variableValues 를 함께 준다 — "
                + "비교 값마다 계산해 합치므로 어떤 값에서만 생기는 결정도 한 번에 드러난다(onlyFor). "
                + "mustAskNoDefault 의 결정은 기본값이 없어 사용자에게 물어 값을 받아야 한다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "answers":{"type":"object",
                 "description":"답변키 → 확정값. 예: {\\"numBuildings\\":4,\\"truckCount\\":3}. 실험 변수는 여기에 넣지 않고 variableValues 로 준다"},
               "origins":{"type":"object",
                 "description":"(선택) 답변키 → USER / MODEL_DEFAULT / DERIVED. 주면 인스턴스에 그대로 싣고, 주지 않으면 출처를 비워 둔다. 규칙은 build_scenario 설명을 따른다"},
               "variableAnswerKey":{"type":"string","description":"(선택) 비교할 실험 변수의 답변키 — build_scenario 와 같다"},
               "variableValues":{"type":"array","description":"(선택) 비교할 값들 — build_scenario 와 같다"}},
             "required":["answers"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        try {
            Map<String, Object> answers = new LinkedHashMap<>();
            args.path("answers").fields().forEachRemaining(
                    e -> answers.put(e.getKey(), JsonValues.plain(e.getValue())));
            Map<String, String> origins = new LinkedHashMap<>();
            args.path("origins").fields().forEachRemaining(
                    e -> origins.put(e.getKey(), e.getValue().asText().trim().toUpperCase(java.util.Locale.ROOT)));
            String variableKey = args.path("variableAnswerKey").asText(null);
            java.util.List<Object> variableValues = new java.util.ArrayList<>();
            args.path("variableValues").forEach(v -> variableValues.add(JsonValues.plain(v)));
            SubtaskPlanner.Across across = planner.planAcross(answers, origins, variableKey, variableValues);
            SubtaskPlan plan = across.plan();

            var root = mapper.createObjectNode();
            root.set("instances", mapper.valueToTree(plan.instances()));
            root.set("counts", mapper.valueToTree(plan.counts()));
            root.put("complete", plan.complete());
            // 보류된 결정마다 무엇이 먼저 정해져야 하는지. 이 키들을 먼저 물으면 보류가 풀린다.
            root.set("waitingOn", mapper.valueToTree(across.waitingOn()));
            // 어떤 비교 값에서만 필요한 결정 — 그 값으로 돌 실행에만 쓰인다고 사용자에게 알릴 수 있다.
            root.set("onlyFor", mapper.valueToTree(across.onlyFor()));
            // 기본값이 없는 결정. 예시 값을 대신 넣지 말고 사용자에게 물어야 한다.
            var noDefault = root.putArray("mustAskNoDefault");
            for (var i : plan.instances()) {
                if (i.status() == com.wastesim.template.SubtaskStatus.UNFILLED
                        && planner.defaultFor(i.answerKey(), answers) == null) {
                    noDefault.add(i.answerKey());
                }
            }
            // 차종에 따라 정해지는 기본값. 템플릿 목록에는 고정값이 없어 여기서 알린다.
            var derived = root.putObject("derivedDefaults");
            derived.putPOJO("routeAvailableCapacityKg", planner.defaultFor("routeAvailableCapacityKg", answers));
            root.put("derivedDefaultsNote", "routeAvailableCapacityKg 의 기본값은 선택 차종의 정격용량입니다. 이 블록 규모에서는 가동률이 1~2%로 고정되고 "
                    + "미수거가 0이라 용량·가동률 비교에는 쓸 수 없습니다. 사용자가 배정 몫을 모르면 이 값을 MODEL_DEFAULT 로 제안하십시오.");
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("answers", "서브태스크 계획에 실패했습니다: " + e.getMessage());
        }
    }
}
