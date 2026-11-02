package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.plan.PlanRounds;
import com.wastesim.template.SubtaskStatus;
import com.wastesim.template.SubtaskPlan;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger callLog = LoggerFactory.getLogger("mcp.calls");

    private final SubtaskPlanner planner;
    private final ObjectMapper mapper;
    private final PlanRounds rounds;

    public PlanSubtasksTool(SubtaskPlanner planner, ObjectMapper mapper, PlanRounds rounds) {
        this.planner = planner;
        this.mapper = mapper;
        this.rounds = rounds;
    }

    @Override public String toolName() { return "plan_subtasks"; }

    @Override
    public String description() {
        return "현재까지 확정된 답변으로 어떤 서브태스크가 존재해야 하는지 계산한다. "
                + "생성 조건이 거짓이면 만들지 않고, 판정할 수 없으면 보류한다. 보류된 결정은 waitingOn 에 "
                + "먼저 정해야 할 답변키를 적는다 — UNFILLED 를 먼저 묻고, 보류는 그 뒤 다시 계산하면 풀린다. "
                + "비교할 실험 변수가 정해졌으면 build_scenario 와 같은 variableAnswerKey · variableValues 를 함께 준다 — "
                + "비교 값마다 계산해 합치므로 어떤 값에서만 생기는 결정도 한 번에 드러난다(onlyFor). "
                + "mustAskNoDefault 의 결정은 기본값이 없어 사용자에게 물어 값을 받아야 한다. "
                + "응답의 planId 는 이 요청의 id 다 — 이후 호출에 이 planId 를 그대로 넣으십시오. "
                + "답변 · 출처 · 실험 변수가 바뀌면 round 가 오르고 changes 에 상태가 바뀐 결정이 실린다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "planId":{"type":"string","description":"(선택) 이전 응답의 planId. 없으면 새 요청으로 보고 새로 발급한다"},
               "matchId":{"type":"string","description":"(선택) 브로커 매칭의 matchId. 기록에 함께 남긴다"},
               "previousPlanId":{"type":"string","description":"(선택) 거절된 옛 planId. planId 없이 새로 시작할 때 주면 기록에 남긴다"},
               "answers":{"type":"object",
                 "description":"답변키 → 확정값. 예: {\\"numBuildings\\":4,\\"truckCount\\":3}. 실험 변수는 여기에 넣지 않고 variableValues 로 준다"},
               "origins":{"type":"object",
                 "description":"(선택) 답변키 → USER / MODEL_DEFAULT / DERIVED. 주면 인스턴스에 그대로 싣고, 주지 않으면 출처를 비워 둔다. 규칙은 build_scenario 설명을 따른다"},
               "variableAnswerKey":{"type":"string","description":"(선택) 비교할 실험 변수의 답변키 — build_scenario 와 같다"},
               "variableValues":{"type":"array","description":"(선택) 비교할 값들 — build_scenario 와 같다"},
               "userUtterances":{"type":"array","items":{"type":"string"},"description":"근거 검사용 사용자 발화 원문 목록. 클라이언트가 대화 기록에서 넣는다. 있으면 evidence 를 검사한다"},
               "evidence":{"type":"object","description":"답변키 → 그 값의 근거가 된 사용자 발화 구절(그대로 옮긴다). USER 값과, 조건부 결정에 기본값을 쓸 때 필요하다"}},
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
            SubtaskPlanner.Evidence ev = null;
            if (args.has("userUtterances")) {
                java.util.List<String> said = new java.util.ArrayList<>();
                args.path("userUtterances").forEach(v -> said.add(v.asText()));
                Map<String, String> quotes = new LinkedHashMap<>();
                args.path("evidence").fields().forEachRemaining(e -> quotes.put(e.getKey(), e.getValue().asText()));
                ev = new SubtaskPlanner.Evidence(said, quotes);
            }
            SubtaskPlanner.Across across = planner.planAcross(answers, origins, variableKey, variableValues, ev);
            SubtaskPlan plan = across.plan();
            PlanRounds.Step step;
            try {
                step = rounds.record(args.path("planId").asText(null), args.path("matchId").asText(null),
                        args.path("previousPlanId").asText(null), args.path("answers"), args.path("origins"),
                        variableKey, args.path("variableValues"), plan, across.waitingOn(), across.minRounds());
            } catch (IllegalArgumentException e) {
                return ToolFailure.of("planId", e.getMessage());
            }
            log(step, plan);

            var root = mapper.createObjectNode();
            root.put("planId", step.planId());
            root.put("round", step.round());
            root.set("changes", mapper.valueToTree(step.changes()));
            // 완료 단계의 minRounds 가 정본이다 — 초반에는 보류가 많아 작게 나오다 커질 수 있다.
            root.putPOJO("completedAtRound", step.completedAtRound());
            root.put("minRounds", step.minRounds());
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

    /** 예: [6 서브태스크] planId=plan-… 3단계 · 새로 채움 2 · 새로 생김 1 · 남음 4 · 완료 아님 */
    private static void log(PlanRounds.Step step, SubtaskPlan plan) {
        var c = plan.counts();
        String rest = "남음 " + (c.get(SubtaskStatus.UNFILLED) + c.get(SubtaskStatus.DEFERRED))
                + " · " + (plan.complete() ? "완료" : "완료 아님");
        if (!step.newRound()) {
            callLog.info("[6 서브태스크] planId={} {}단계 다시 확인 · {}", step.planId(), step.round(), rest);
            return;
        }
        long filled = step.changes().stream().filter(x -> x.to().equals("FILLED")).count();
        long born = step.changes().stream().filter(x -> active(x.to()) && !active(x.from())).count();
        callLog.info("[6 서브태스크] planId={} {}단계 · 새로 채움 {} · 새로 생김 {} · {}",
                step.planId(), step.round(), filled, born, rest);
    }

    private static boolean active(String status) {
        return "UNFILLED".equals(status) || "FILLED".equals(status);
    }
}
