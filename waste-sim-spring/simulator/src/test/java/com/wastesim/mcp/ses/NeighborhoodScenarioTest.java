package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.contract.*;
import com.wastesim.pes.*;
import com.wastesim.template.*;
import com.wastesim.service.*;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class NeighborhoodScenarioTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog, new AnswerNormalizer());
    private final ScenarioStore store = new ScenarioStore();
    private final ScenarioBuilder builder = new ScenarioBuilder(new PesFlattener(catalog),new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()), planner);
    private final CreateNeighborhoodScenarioTool tool = new CreateNeighborhoodScenarioTool(
            new BuildScenarioTool(builder,store,catalog,json), catalog,planner,store,json,new GetScenarioStatusTool(store,json,8090));
    private ObjectNode args(String region) {
        var a = json.createObjectNode().put("request", region + " 시뮬레이터 실행해줘").put("regionName",region);
        a.putObject("values").put("days",2).put("seeds",1);
        return a;
    }
    private JsonNode create(ObjectNode args) throws Exception {
        var result = tool.call(args); assertTrue(result.ready(),result.toString());
        return json.readTree(result.result().toString());
    }
    @Test void arbitraryNamesProduceExplicitAssumedBlocksWithoutCodeOrCatalogChanges() throws Exception {
        for (String name : List.of("덕명동","사용자가 지정한 처음 보는 동네","Somewhere-123")) {
            var out = create(args(name)); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
            assertEquals(name,out.path("region").path("id").asText());
            assertTrue(out.path("region").path("synthetic").asBoolean());
            assertEquals("ASSUMED_BLOCK",out.path("scenarioMode").asText());
            assertEquals("UNCONFIRMED",out.path("state").asText());
            assertFalse(out.has("confirmToken"));
            assertTrue(out.path("confirmUrl").asText().contains("confirm.html?id="));
            var entry = store.entry(out.path("scenarioId").asText()).orElseThrow();
            assertEquals(4,entry.scenario().runs().get(0).getNumBuildings());
            assertEquals("MODEL_DEFAULT",entry.pes().origins().get("numBuildings"));
            assertFalse(entry.scenario().runs().get(0).isTrafficEnabled());
        }
    }
    @Test void noRegionOrInputsUsesCompleteProviderDefaultsButStillRequiresApproval() throws Exception {
        var out = create(json.createObjectNode().put("request","아무 동네 시뮬레이터 실행해줘"));
        assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        assertEquals("가상 동네",out.path("region").path("id").asText());
        assertEquals(1,out.path("runCount").asInt());
        var entry=store.entry(out.path("scenarioId").asText()).orElseThrow();
        assertEquals(30,entry.scenario().runs().get(0).getDays());
        assertEquals("RUN",out.path("purposeCheck").path("specification").path("mode").asText());
        assertTrue(entry.pes().reviewRequiredValues().containsKey("routeAvailableCapacityKg"));
    }
    @Test void knownRegionDefaultsTakePrecedenceOverGenericTemplateDefaults() throws Exception {
        var out=create(args("synthetic-block")); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        var cfg=store.entry(out.path("scenarioId").asText()).orElseThrow().scenario().runs().get(0);
        assertEquals(6,cfg.getNumBuildings()); assertEquals(12,cfg.getResidentsPerBuilding());
    }
    @Test void brokerConditionalRegionBecomesVerifiedOnlyAfterActualComposition() throws Exception {
        var a=args("덕명동");
        var req=json.createObjectNode().put("regionId","덕명동");
        req.putObject("simulationDuration").put("value",2).put("unit","day");
        var card=new com.wastesim.capability.CapabilityCardLoader().rawJson();
        assertEquals("CONDITIONAL",RequirementContract.evaluate(req,card).get(0).status());
        a.set("selection",SelectionContract.handoff("match-deokmyeong",card,req));
        var out=create(a); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        assertTrue(out.path("requirementReady").asBoolean());
        assertEquals("CONFIGURATION_VERIFIED",out.path("requirementChecks").get(0).path("code").asText());
        assertEquals("match-deokmyeong",out.path("validation").path("matchId").asText());
    }
    @Test void requestedValuesAreKeptAndInvalidValuesAreNeverSilentlyReplaced() throws Exception {
        var a=args("덕명동"); ((ObjectNode)a.get("values")).put("numBuildings",8).put("truckCount",2);
        var out=create(a); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        var cfg=store.entry(out.path("scenarioId").asText()).orElseThrow().scenario().runs().get(0);
        assertEquals(8,cfg.getNumBuildings()); assertEquals(2,cfg.getNumTrucks());
        ((ObjectNode)a.get("values")).put("numBuildings",100);
        var blocked=create(a); assertFalse(blocked.path("valid").asBoolean());
        assertEquals("NEEDS_INPUT",blocked.path("state").asText());
        assertFalse(blocked.path("missingInputs").isEmpty());
    }
    @Test void anotherRegionsTrafficAndDischargePoliciesCannotLeakIntoAssumedRegions() throws Exception {
        for (var setting : List.of(new String[]{"trafficMode","APPLY"},new String[]{"travelTimeMode","ZONE_PROXY_HYBRID"},
                new String[]{"dischargeTimeMode","POHANG_ACTUAL"})) {
            var a=args("덕명동"); ((ObjectNode)a.get("values")).put(setting[0],setting[1]);
            var out=create(a); assertFalse(out.path("valid").asBoolean(),out.toPrettyString());
        }
    }
    @Test void realExecutionRetainsAssumedRegionNoticeAndRejectsUnapprovedRun() throws Exception {
        var out=create(args("덕명동")); String id=out.path("scenarioId").asText();
        var runner=new RunScenarioByTokenTool(builder,store,
                new SimulationService(new com.wastesim.simulation.SimulationEngine(new TrafficDataService())),json);
        var runArgs=json.createObjectNode().put("scenarioId",id);
        assertFalse(runner.call(runArgs).ready());
        // Approval simulation confined to this in-memory test store.
        var confirmed=new ConfirmScenarioTool(builder,store,json).call(runArgs); assertTrue(confirmed.ready());
        runArgs.put("confirmToken",json.readTree(confirmed.result().toString()).path("confirmToken").asText());
        var result=runner.call(runArgs); assertTrue(result.ready(),result.toString());
        var executed=json.readTree(result.result().toString());
        assertEquals("EXECUTED",executed.path("state").asText());
        assertEquals("덕명동",executed.path("region").path("id").asText());
        assertEquals("ASSUMED_BLOCK",executed.path("scenarioMode").asText());
        assertFalse(executed.path("dataNotice").asText().isBlank());
        assertFalse(executed.path("runs").get(0).path("meanComplaints").isMissingNode());
    }
    @Test void changingRegionInvalidatesEarlierApprovalEvenWhenTheNewRequestFails() throws Exception {
        var a=args("덕명동"); a.put("requestId","region-revision");
        var out=create(a); String id=out.path("scenarioId").asText();
        var runArgs=json.createObjectNode().put("scenarioId",id);
        assertTrue(new ConfirmScenarioTool(builder,store,json).call(runArgs).ready());
        a.put("regionName",""); assertFalse(tool.call(a).ready());
        assertFalse(store.validationCurrent(id));
    }

    @Test void spokenRequirementsAreMappedToThisSimulatorsFields() throws Exception {
        var a=args("덕명동");
        a.set("requirements", json.readTree("""
            {"requiredOutputs":["평균 민원 수"],"constraints":[{"name":"배차 간격","value":1,"unit":"시간"}]}"""));
        ((ObjectNode)a.get("values")).put("truckCount",2);
        var out=create(a); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        var cfg=store.entry(out.path("scenarioId").asText()).orElseThrow().scenario().runs().get(0);
        assertEquals(60,cfg.getDispatchIntervalMinutes());
        assertTrue(out.path("purposeCheck").path("specification").path("observations").toString().contains("meanComplaints"));
    }

    @Test void valuesAreGroupedByWhoCanDecideThem() throws Exception {
        var a=args("덕명동"); ((ObjectNode)a.get("values")).put("truckCount",1);
        a.putObject("origins").put("truckCount","USER").put("seeds","USER").put("days","USER");
        var roles=create(a).path("decisionRoles");
        assertTrue(roles.path("intentGiven").has("truckCount"), roles.toPrettyString());
        // 사용자가 정해야 하는데 말하지 않은 것 — 수거 시각 · 민원 기준
        assertTrue(roles.path("intentDefaults").has("collectionTimeMinutes"), roles.toPrettyString());
        assertTrue(roles.path("intentDefaults").has("complaintThreshold"));
        // 가상 블록이라 건물 수 · 세대 수는 자료가 아니라 기본값이다
        assertTrue(roles.path("dataGaps").has("numBuildings"));
        assertTrue(roles.path("dataGaps").has("routeAvailableCapacityKg"));
        assertTrue(roles.path("assumptions").has("travelTimeMode"));
        for (String g : List.of("intentGiven","intentDefaults","dataFromRegion","dataGaps","assumptions"))
            roles.path(g).fieldNames().forEachRemaining(k -> assertFalse(k.isBlank()));
    }
}
