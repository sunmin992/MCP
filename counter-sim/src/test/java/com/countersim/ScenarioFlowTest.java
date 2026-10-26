package com.countersim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.countersim.mcp.McpToolRegistry;
import com.countersim.mcp.ses.ConfirmScenarioTool;
import com.countersim.model.CounterConfig;
import com.countersim.template.SubtaskTemplate;
import com.countersim.template.TemplateCatalog;
import com.countersim.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 도구 7개로 템플릿 → 시나리오 → 확인 → 실행까지 한 번에 가는지. 브로커 등록은 끈다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "broker.registration.enabled=false")
class ScenarioFlowTest {

    @Autowired McpToolRegistry registry;
    @Autowired ConfirmScenarioTool confirm;
    @Autowired TemplateCatalog catalog;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(String tool, String argsJson) throws Exception {
        JsonNode args = mapper.readTree(argsJson);
        // build_scenario 는 사용자 요청 원문을 받아야 한다 — 이 시험들의 관심이 아니므로 여기서 채운다.
        if (tool.equals("build_scenario") && !args.has("request")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) args).put("request", "시험 요청");
        }
        ToolResult r = registry.byToolName(tool).call(args);
        assertThat(r.ready()).as(tool + " " + r.errors()).isTrue();
        return mapper.readTree(String.valueOf(r.result()));
    }

    @Test
    void 장량동과_같은_도구_이름을_낸다() {
        assertThat(registry.all().stream().map(p -> p.toolName()).toList()).containsExactlyInAnyOrder(
                "get_capability", "get_templates", "plan_subtasks", "validate_answers",
                "build_scenario", "get_scenario_status", "run_scenario_by_token");
    }

    @Test
    void 모든_템플릿의_설정_항목에_세터가_있다() {
        List<String> setters = Arrays.stream(CounterConfig.class.getMethods())
                .map(Method::getName).filter(n -> n.startsWith("set")).toList();
        for (SubtaskTemplate t : catalog.all()) {
            String setter = "set" + Character.toUpperCase(t.configField().charAt(0)) + t.configField().substring(1);
            assertThat(setters).as(t.templateId()).contains(setter);
        }
    }

    @Test
    void 실험_변수에_출처를_적어도_변수에_걸린_결정을_빠졌다고_적지_않는다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"dailyVisitors":180,"serviceMinutes":8.0,"queueDiscipline":"SINGLE_LINE",
                       "reservationMode":"IGNORE","maxWaitMinutes":30,"days":1,"seeds":1},
             "origins":{"counterCount":"USER"},
             "variableAnswerKey":"counterCount","variableValues":[2,3]}
            """);
        assertThat(built.path("valid").asBoolean()).as(built.toString()).isTrue();
        String id = built.path("scenarioId").asText();
        confirm.call(mapper.createObjectNode().put("scenarioId", id));
        String token = call("get_scenario_status", "{\"scenarioId\":\"" + id + "\"}").path("confirmToken").asText();
        JsonNode run = call("run_scenario_by_token", "{\"scenarioId\":\"" + id + "\",\"confirmToken\":\"" + token + "\"}");

        for (JsonNode r : run.path("runs")) {
            assertThat(r.path("integrity").path("assumptionNotes").toString()).doesNotContain("PES 에 없어");
        }
    }

    @Test
    void 확인_전에는_실행하지_않고_확인하면_한_번_실행한다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"counterCount":3,"queueDiscipline":"SINGLE_LINE","dailyVisitors":200,"serviceMinutes":8.0,
                       "reservationShare":0.3,"maxWaitMinutes":30,"days":3,"seeds":3},
             "origins":{"counterCount":"USER","queueDiscipline":"MODEL_DEFAULT","dailyVisitors":"USER",
                        "reservationShare":"USER","maxWaitMinutes":"USER","days":"USER","seeds":"USER"},
             "variableAnswerKey":"reservationMode","variableValues":["IGNORE","APPLY"]}
            """);
        assertThat(built.path("state").asText()).as(built.toString()).isEqualTo("UNCONFIRMED");
        String id = built.path("scenarioId").asText();

        ToolResult early = registry.byToolName("run_scenario_by_token")
                .call(mapper.readTree("{\"scenarioId\":\"" + id + "\",\"confirmToken\":\"cft-x\"}"));
        assertThat(early.ready()).isFalse();

        confirm.call(mapper.createObjectNode().put("scenarioId", id));
        JsonNode status = call("get_scenario_status", "{\"scenarioId\":\"" + id + "\"}");
        assertThat(status.path("state").asText()).isEqualTo("CONFIRMED");
        String token = status.path("confirmToken").asText();

        JsonNode run = call("run_scenario_by_token",
                "{\"scenarioId\":\"" + id + "\",\"confirmToken\":\"" + token + "\"}");
        assertThat(run.path("runs")).hasSize(2);
        JsonNode first = run.path("runs").get(0);
        assertThat(first.path("settings").path("reservationEnabled").asBoolean()).isFalse();
        assertThat(first.path("integrity").path("visitorBalanceErrorMax").asInt()).isZero();
        // 처리 시간(serviceMinutes)은 출처를 적지 않았다 — 승인하지 않은 기본값으로 보여야 한다.
        String notes = first.path("integrity").path("assumptionNotes").toString();
        assertThat(notes).contains("serviceMinutes", "queueDiscipline").doesNotContain("PES 에 없어");
        assertThat(run.path("runs").get(1).path("judgement").path("byVisitorType").has("RESERVED")).isTrue();

        ToolResult again = registry.byToolName("run_scenario_by_token")
                .call(mapper.readTree("{\"scenarioId\":\"" + id + "\",\"confirmToken\":\"" + token + "\"}"));
        assertThat(again.ready()).isFalse();
    }

    @Test
    void 실험_변수에_따라_생기는_결정이_빠지면_그_실행만_막힌다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"counterCount":3,"queueDiscipline":"SINGLE_LINE","dailyVisitors":180,"serviceMinutes":8.0,
                       "maxWaitMinutes":30,"days":2,"seeds":2},
             "variableAnswerKey":"reservationMode","variableValues":["IGNORE","APPLY"]}
            """);
        String blocks = built.path("blocks").toString();
        assertThat(built.path("valid").asBoolean()).isFalse();
        assertThat(blocks).contains("reservationMode=APPLY", "reservationShare")
                .doesNotContain("reservationMode=IGNORE");
    }

    @Test
    void 기본값_없는_값을_빼면_시나리오가_막힌다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"counterCount":2},
             "variableAnswerKey":"dailyVisitors","variableValues":[150]}
            """);
        assertThat(built.path("valid").asBoolean()).isFalse();
        assertThat(built.path("blocks").toString()).contains("대기 한도");
    }

    @Test
    void plan_subtasks_는_비교_값마다_계산해_합치고_스키마가_올바르다() throws Exception {
        for (var t : registry.all()) mapper.readTree(t.inputSchemaJson());   // tools/list 가 깨지지 않게
        JsonNode plan = call("plan_subtasks", """
            {"answers":{"counterCount":3},"variableAnswerKey":"reservationMode","variableValues":["IGNORE","APPLY"]}
            """);
        assertThat(plan.path("onlyFor").path("reservationShare").toString()).contains("APPLY");
        assertThat(plan.path("mustAskNoDefault").toString()).contains("maxWaitMinutes");
    }

    @Test
    void 요청_원문이_없으면_시나리오를_만들지_않는다() throws Exception {
        ToolResult r = registry.byToolName("build_scenario").call(mapper.readTree(
                "{\"values\":{},\"variableAnswerKey\":\"days\",\"variableValues\":[7]}"));
        assertThat(r.ready()).isFalse();
        assertThat(String.valueOf(r.errors())).contains("request");
    }
}
