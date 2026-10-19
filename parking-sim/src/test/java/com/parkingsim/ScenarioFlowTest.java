package com.parkingsim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkingsim.mcp.McpToolRegistry;
import com.parkingsim.mcp.ses.ConfirmScenarioTool;
import com.parkingsim.model.ParkingConfig;
import com.parkingsim.template.SubtaskTemplate;
import com.parkingsim.template.TemplateCatalog;
import com.parkingsim.tool.ToolResult;
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
        ToolResult r = registry.byToolName(tool).call(mapper.readTree(argsJson));
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
        List<String> setters = Arrays.stream(ParkingConfig.class.getMethods())
                .map(Method::getName).filter(n -> n.startsWith("set")).toList();
        for (SubtaskTemplate t : catalog.all()) {
            String setter = "set" + Character.toUpperCase(t.configField().charAt(0)) + t.configField().substring(1);
            assertThat(setters).as(t.templateId()).contains(setter);
        }
    }

    @Test
    void 확인_전에는_실행하지_않고_확인하면_한_번_실행한다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"lotCount":4,"spacesPerLot":60,"pricingPolicy":"FLAT","baseFeePer30Min":500,
                       "userMix":["Commuter","Shopper","Visitor"],"dailyArrivals":1000,
                       "congestionMode":"APPLY","congestionProfileId":"weekday-downtown",
                       "maxSearchMinutes":12,"guidanceRefreshMinutes":5,"days":3,"seeds":3},
             "origins":{"lotCount":"USER","spacesPerLot":"MODEL_DEFAULT","pricingPolicy":"MODEL_DEFAULT",
                        "baseFeePer30Min":"MODEL_DEFAULT","userMix":"USER","dailyArrivals":"USER",
                        "congestionMode":"USER","congestionProfileId":"MODEL_DEFAULT",
                        "maxSearchMinutes":"USER","days":"USER","seeds":"USER"},
             "variableAnswerKey":"guidanceMode","variableValues":["NONE","REALTIME_APP"]}
            """);
        assertThat(built.path("valid").asBoolean()).isTrue();
        assertThat(built.path("state").asText()).isEqualTo("UNCONFIRMED");
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
        assertThat(first.path("settings").path("guidanceMode").asText()).isEqualTo("NONE");
        assertThat(first.path("integrity").path("vehicleBalanceErrorMax").asInt()).isZero();
        assertThat(first.path("integrity").path("dataQualityFlags").toString()).contains("CONGESTION_ASSUMED");
        // 갱신 주기(guidanceRefreshMinutes)는 출처를 적지 않았다 — 승인하지 않은 기본값으로 보여야 한다.
        String notes = first.path("integrity").path("assumptionNotes").toString();
        assertThat(notes).contains("guidanceRefreshMinutes").doesNotContain("PES 에 없어");
        assertThat(built.path("unapprovedDefaults").has("guidanceRefreshMinutes")).isTrue();

        ToolResult again = registry.byToolName("run_scenario_by_token")
                .call(mapper.readTree("{\"scenarioId\":\"" + id + "\",\"confirmToken\":\"" + token + "\"}"));
        assertThat(again.ready()).isFalse();
    }

    @Test
    void 실험_변수에_따라_생기는_결정이_빠지면_그_실행만_막힌다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"lotCount":4,"spacesPerLot":80,"pricingPolicy":"FLAT","baseFeePer30Min":500,
                       "userMix":["Commuter","Shopper"],"dailyArrivals":800,"congestionMode":"IGNORE",
                       "maxSearchMinutes":15,"days":3,"seeds":2},
             "variableAnswerKey":"guidanceMode","variableValues":["NONE","REALTIME_APP"]}
            """);
        String blocks = built.path("blocks").toString();
        assertThat(built.path("valid").asBoolean()).isFalse();
        assertThat(blocks).contains("guidanceMode=REALTIME_APP", "guidanceRefreshMinutes")
                .doesNotContain("guidanceMode=NONE");
    }

    @Test
    void 기본값_없는_값을_빼면_시나리오가_막힌다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"lotCount":2,"pricingPolicy":"TIME_OF_DAY"},
             "variableAnswerKey":"dailyArrivals","variableValues":[300]}
            """);
        assertThat(built.path("valid").asBoolean()).isFalse();
        assertThat(built.path("blocks").toString()).contains("탐색 포기 시간", "할증률");
    }
}
