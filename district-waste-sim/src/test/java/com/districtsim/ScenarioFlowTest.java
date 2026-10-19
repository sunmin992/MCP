package com.districtsim;

import com.districtsim.mcp.McpToolRegistry;
import com.districtsim.mcp.ses.ConfirmScenarioTool;
import com.districtsim.model.DistrictConfig;
import com.districtsim.template.SubtaskTemplate;
import com.districtsim.template.TemplateCatalog;
import com.districtsim.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        List<String> setters = Arrays.stream(DistrictConfig.class.getMethods())
                .map(Method::getName).filter(n -> n.startsWith("set")).toList();
        for (SubtaskTemplate t : catalog.all()) {
            String setter = "set" + Character.toUpperCase(t.configField().charAt(0)) + t.configField().substring(1);
            assertThat(setters).as(t.templateId()).contains(setter);
        }
    }

    @Test
    void 결합_조건_둘을_참거짓으로_옮기고_확인하면_한_번_실행한다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"zoneCount":8,"householdsPerZone":600,"housingMix":["Apartment","Detached","Studio"],
                       "truckType":"LARGE_5TON","truckCount":1,"holidayMode":"APPLY","holidayPolicy":"NEXT_DAY",
                       "transferMode":"APPLY","transferRoundTripMinutes":30,"collectionStartMinutes":360,
                       "days":28,"seeds":3},
             "origins":{"zoneCount":"USER","householdsPerZone":"MODEL_DEFAULT","housingMix":"USER","truckType":"MODEL_DEFAULT",
                        "truckCount":"USER","holidayMode":"USER","holidayPolicy":"USER","transferMode":"USER",
                        "transferRoundTripMinutes":"USER","collectionStartMinutes":"MODEL_DEFAULT","days":"USER","seeds":"USER"},
             "variableAnswerKey":"scheduleMode","variableValues":["POHANG_BUKGU","DAILY"]}
            """);
        assertThat(built.path("valid").asBoolean()).as(built.toString()).isTrue();
        assertThat(built.path("backVerificationBlocks")).isEmpty();
        String id = built.path("scenarioId").asText();

        confirm.call(mapper.createObjectNode().put("scenarioId", id));
        String token = call("get_scenario_status", "{\"scenarioId\":\"" + id + "\"}").path("confirmToken").asText();
        JsonNode run = call("run_scenario_by_token", "{\"scenarioId\":\"" + id + "\",\"confirmToken\":\"" + token + "\"}");

        assertThat(run.path("runs")).hasSize(2);
        JsonNode bukgu = run.path("runs").get(0);
        assertThat(bukgu.path("settings").path("scheduleMode").asText()).isEqualTo("POHANG_BUKGU");
        assertThat(bukgu.path("explanation").path("skippedForHoliday").asDouble()).isPositive();
        assertThat(bukgu.path("integrity").path("massBalanceErrorKgMax").asDouble()).isZero();
        assertThat(bukgu.path("integrity").path("dataQualityFlags").toString())
                .contains("SCHEDULE_INTERPRETED", "HOLIDAY_CALENDAR_TRANSCRIBED", "TRANSFER_TIME_USER_GIVEN");
        // 실험 변수 · 결합 조건 · 연쇄된 값이 모두 PES 에 있었다 — 빠진 결정으로 적히면 안 된다.
        assertThat(bukgu.path("integrity").path("assumptionNotes").toString()).doesNotContain("PES 에 없어");
    }

    @Test
    void 기본값_없는_값을_빼면_시나리오가_막힌다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"scheduleMode":"WEEKDAYS","transferMode":"APPLY"},
             "variableAnswerKey":"truckCount","variableValues":[2]}
            """);
        assertThat(built.path("valid").asBoolean()).isFalse();
        assertThat(built.path("blocks").toString()).contains("수거 요일", "적환장");
    }

    @Test
    void 수거_일정이_없으면_요일은_물어야_할_결정이_아니라_보류로_적는다() throws Exception {
        JsonNode built = call("build_scenario", """
            {"values":{"zoneCount":8,"householdsPerZone":600,"housingMix":["Apartment"],"truckType":"LARGE_5TON",
                       "truckCount":1,"holidayMode":"IGNORE","transferMode":"IGNORE",
                       "collectionStartMinutes":360,"days":7,"seeds":1},
             "variableAnswerKey":"days","variableValues":[7]}
            """);
        String blocks = built.path("blocks").toString();
        assertThat(blocks).contains("물어야 할 결정 scheduleMode", "보류된 결정 collectionDays — scheduleMode")
                .doesNotContain("물어야 할 결정 collectionDays");
    }

    @Test
    void plan_subtasks_는_보류된_결정이_기다리는_키를_낸다() throws Exception {
        JsonNode plan = call("plan_subtasks", "{\"answers\":{\"holidayMode\":\"APPLY\"}}");
        assertThat(plan.path("waitingOn").path("holidayPolicy").toString()).contains("scheduleMode");
        assertThat(plan.path("waitingOn").path("zoneAllocation").toString()).contains("zoneCount", "truckCount");
    }
}
