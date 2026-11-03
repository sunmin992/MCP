package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.plan.PlanRounds;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

/** planId 하나의 메모리 기록(단계 · 생성 · 확인 · 실행 사건)을 순서대로 낸다. */
@Component
public class GetPlanHistoryTool implements McpToolProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlanRounds rounds;

    public GetPlanHistoryTool(PlanRounds rounds) {
        this.rounds = rounds;
    }

    @Override public String toolName() { return "get_plan_history"; }

    @Override
    public String description() {
        return "planId 하나의 단계별 서브태스크 판정 기록을 낸다 — 상태와 이유, 바뀐 것, 채움 근거, 생성 조건 의존 계층 수(dependencyDepth), 생성·확인·실행 사건. round는 입력 변경 단계이며 실제 질문 횟수가 아니다.";
    }

    @Override
    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{\"planId\":{\"type\":\"string\"}},\"required\":[\"planId\"]}";
    }

    @Override
    public ToolResult call(JsonNode args) {
        String planId = args == null ? "" : args.path("planId").asText("").trim();
        var history = planId.isEmpty() ? java.util.Optional.<java.util.List<ObjectNode>>empty() : rounds.history(planId);
        if (history.isEmpty()) {
            return ToolFailure.of("planId", "기록이 메모리에 없습니다 — logs/plan-history 파일을 보십시오");
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("planId", planId);
        out.putArray("events").addAll(history.get());
        return ToolResult.ok(out.toString());
    }
}
