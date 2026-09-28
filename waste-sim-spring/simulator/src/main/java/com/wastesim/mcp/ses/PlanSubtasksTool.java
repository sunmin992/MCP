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
                + "생성 조건이 거짓이면 만들지 않고, 판정할 수 없으면 보류한다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "answers":{"type":"object",
                 "description":"답변키 → 확정값. 예: {\\"numBuildings\\":4,\\"truckCount\\":3}"}},
             "required":["answers"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        try {
            Map<String, Object> answers = new LinkedHashMap<>();
            args.path("answers").fields().forEachRemaining(
                    e -> answers.put(e.getKey(), JsonValues.plain(e.getValue())));
            SubtaskPlan plan = planner.plan(answers);

            var root = mapper.createObjectNode();
            root.set("instances", mapper.valueToTree(plan.instances()));
            root.set("counts", mapper.valueToTree(plan.counts()));
            root.put("complete", plan.complete());
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("answers", "서브태스크 계획에 실패했습니다: " + e.getMessage());
        }
    }
}
