package com.parkingsim.data;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkingsim.mcp.McpToolRegistry;
import com.parkingsim.mcp.ses.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "broker.registration.enabled=false")
class ParkingSnapshotFlowTest {
    @Autowired ParkingDataService data;
    @Autowired McpToolRegistry tools;
    @Autowired ConfirmScenarioTool confirm;
    @Autowired ScenarioStore store;
    @Autowired com.parkingsim.pes.ScenarioBuilder builder;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode args() throws Exception {
        data.save(new ParkingSnapshot("fixture", "fixture", Instant.now().toString(), "47113", List.of(
                new ParkingSnapshot.Lot("a", "A", "address", "36", "129", 80, 80, 10),
                new ParkingSnapshot.Lot("b", "B", "address", "36", "129", 120, 120, 120))));
        return (ObjectNode) mapper.readTree("""
            {"request":"실시간 잔여면수로 안내 효과 비교","parkingSnapshotId":"fixture",
             "initialVehicleRemainingMinutes":60,
             "values":{"pricingPolicy":"FREE","userMix":["Visitor"],"dailyArrivals":100,
                       "congestionMode":"IGNORE","maxSearchMinutes":10,"lotSpacingMinutes":3.0,
                       "guidanceRefreshMinutes":5,"days":1,"seeds":1},
             "origins":{"maxSearchMinutes":"USER","initialVehicleRemainingMinutes":"USER"},
             "variableAnswerKey":"guidanceMode","variableValues":["NONE","REALTIME_APP"]}
            """);
    }
    private JsonNode call(String name, JsonNode args) throws Exception {
        var r = tools.byToolName(name).call(args);
        assertThat(r.ready()).as(r.errors().toString()).isTrue();
        return mapper.readTree(r.result().toString());
    }
    @Test void snapshotSurvivesPlanConfirmRunAndParticipatesInToken() throws Exception {
        var args = args();
        var planArgs = args.deepCopy();
        planArgs.set("answers", planArgs.remove("values"));
        var plan = call("plan_subtasks", planArgs);
        assertThat(plan.path("complete").asBoolean()).isTrue();
        assertThat(plan.path("instances").toString()).doesNotContain("spacesPerLot");
        var built = call("build_scenario", args);
        assertThat(built.path("valid").asBoolean()).as(built.toString()).isTrue();
        String id = built.path("scenarioId").asText();
        confirm.call(mapper.createObjectNode().put("scenarioId", id));
        var scenario = store.entry(id).orElseThrow().scenario();
        String token = scenario.confirmToken();
        assertThat(builder.tokenMatches(scenario, token)).isTrue();
        scenario.runs().get(0).setInitialVehicleRemainingMinutes(61);
        assertThat(builder.tokenMatches(scenario, token)).isFalse();
        scenario.runs().get(0).setInitialVehicleRemainingMinutes(60);
        var result = call("run_scenario_by_token", mapper.createObjectNode().put("scenarioId", id).put("confirmToken", token));
        for (var run : result.path("runs")) {
            assertThat(run.path("integrity").path("vehicleBalanceErrorMax").asInt()).isZero();
            assertThat(run.path("integrity").path("parkingSnapshot").path("lots")).hasSize(2);
            assertThat(run.path("integrity").path("dataQualityFlags").toString()).contains("SOURCE_TIMESTAMP_UNAVAILABLE");
            assertThat(run.path("integrity").path("assumptionNotes").toString()).doesNotContain("PES 에 없어");
        }
    }
    @Test void rejectsMissingDepartureAssumptionAndConflictingCapacity() throws Exception {
        var args = args();
        args.remove("initialVehicleRemainingMinutes");
        assertThat(tools.byToolName("build_scenario").call(args).ready()).isFalse();
        args = args();
        ((ObjectNode) args.path("values")).put("spacesPerLot", 80);
        assertThat(tools.byToolName("build_scenario").call(args).ready()).isFalse();
        args = args();
        args.put("initialVehicleRemainingMinutes", 0);
        assertThat(call("build_scenario", args).path("valid").asBoolean()).isFalse();
    }

    @Test void noDefaultValueMarkedAsModelDefaultIsRejected() throws Exception {
        // 사용자가 "기본값으로" 라고 하자 LLM 이 탐색 포기 10분을 넣었다(2026-10-27 ST-S5).
        var request = args();
        request.set("origins", mapper.readTree("{\"maxSearchMinutes\":\"MODEL_DEFAULT\",\"initialVehicleRemainingMinutes\":\"USER\"}"));
        var built = call("build_scenario", request);
        assertThat(built.path("valid").asBoolean()).isFalse();
        assertThat(built.path("blocks").toString()).contains("기본값이 없는 결정 maxSearchMinutes");
    }

    @Test void departureTimeWithoutUserOriginIsRejected() throws Exception {
        // 사용자가 "기본값으로" 라고 하자 LLM 이 60분을 넣어 통과했다(2026-10-28 ST-S5-r2).
        var request = args();
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.path("origins")).remove("initialVehicleRemainingMinutes");
        var r = tools.byToolName("build_scenario").call(request);
        assertThat(r.ready()).isFalse();
        assertThat(r.errors().toString()).contains("initialVehicleRemainingMinutes", "USER");
        var planArgs = request.deepCopy();
        planArgs.set("answers", planArgs.remove("values"));
        var plan = call("plan_subtasks", planArgs);
        assertThat(plan.path("mustAskNoDefault").toString()).contains("initialVehicleRemainingMinutes");
        assertThat(plan.path("originNote").asText()).contains("USER");
        assertThat(plan.path("complete").asBoolean()).isFalse();
    }

    @Test void standardSnapshotNeedsUserOriginsForItsAssumptions() throws Exception {
        // LLM 이 7곳을 골라 00:00 전부 빈자리로 넣었다(2026-10-27 ST-S5). 조회 전에 막으므로 API 를 부르지 않는다.
        var r = tools.byToolName("fetch_standard_parking").call(mapper.readTree("""
            {"institutionName":"대구광역시 수성구청","lotIds":["a"],"initialAvailableSpaces":{"a":5},"initialStateTime":"00:00",
             "origins":{"lotIds":"USER","initialAvailableSpaces":"MODEL_DEFAULT"}}
            """));
        assertThat(r.ready()).isFalse();
        assertThat(r.errors().toString()).contains("initialAvailableSpaces", "initialStateTime").doesNotContain("[lotIds");
    }

    @Test void arrivalDefaultFollowsSnapshotCapacity() throws Exception {
        // 4곳 × 80면에 900대가 기본값이다. 스냅샷은 80 + 120 = 200면이라 같은 회전율이면 563대.
        var planArgs = args();
        planArgs.set("answers", planArgs.remove("values"));
        var plan = call("plan_subtasks", planArgs);
        assertThat(plan.path("snapshotDefaults").path("dailyArrivals").asInt()).isEqualTo(563);
    }

    @Test void guidanceCompareWithOneLotWarnsButDoesNotBlock() throws Exception {
        data.save(new ParkingSnapshot("one-lot", "fixture", Instant.now().toString(), "47113", List.of(
                new ParkingSnapshot.Lot("a", "A", "address", "36", "129", 21, 21, 5))));
        var request = args();
        request.put("parkingSnapshotId", "one-lot");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.path("values")).put("dailyArrivals", 59);
        var built = call("build_scenario", request);
        assertThat(built.path("valid").asBoolean()).as(built.toString()).isTrue();
        assertThat(built.path("warnings").toString()).contains("guidanceMode", "lotCount=1");
        var planArgs = request.deepCopy();
        planArgs.set("answers", planArgs.remove("values"));
        assertThat(call("plan_subtasks", planArgs).path("unusedVariableWarning").asText()).contains("guidanceMode");
        assertThat(call("build_scenario", args()).path("warnings")).as("주차장 2곳이면 경고하지 않는다").isEmpty();
    }

    @Test void standardFacilityDataRunsWithExplicitlyAssumedOccupancy() throws Exception {
        var request = args();
        data.save(new ParkingSnapshot("standard-fixture", StandardParkingApiClient.SOURCE, Instant.now().toString(), "",
                List.of(new ParkingSnapshot.Lot("s1", "표준 주차장", "포항시", "36", "129", 80, 80, 20),
                        new ParkingSnapshot.Lot("s2", "표준 주차장2", "포항시", "36", "129", 120, 120, 120)),
                "USER_ASSUMED", java.util.Map.of("s1", java.util.Map.of("referenceDate", "2026-09-01", "basicCharge", "500")), 1110));
        request.put("parkingSnapshotId", "standard-fixture");
        var built = call("build_scenario", request);
        assertThat(built.path("valid").asBoolean()).as(built.toString()).isTrue();
        String id = built.path("scenarioId").asText();
        confirm.call(mapper.createObjectNode().put("scenarioId", id));
        var scenario = store.entry(id).orElseThrow().scenario();
        var run = call("run_scenario_by_token", mapper.createObjectNode().put("scenarioId", id).put("confirmToken", scenario.confirmToken()));
        for (var r : run.path("runs")) {
            assertThat(r.path("integrity").path("dataQualityFlags").toString())
                    .contains("INITIAL_OCCUPANCY_ASSUMED", "STANDARD_API_CAPACITY").doesNotContain("API_CAPACITY_AND_INITIAL_OCCUPANCY");
            assertThat(r.path("integrity").path("parkingSnapshot").path("facilityDetails").path("s1").path("referenceDate").asText()).isEqualTo("2026-09-01");
            assertThat(r.path("integrity").path("vehicleBalanceErrorMax").asInt()).isZero();
            assertThat(r.path("integrity").path("simulationStartMinuteKst").asInt()).isEqualTo(1110);
        }
    }
}
