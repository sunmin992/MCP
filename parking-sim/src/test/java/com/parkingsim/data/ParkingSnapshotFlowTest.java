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
}
