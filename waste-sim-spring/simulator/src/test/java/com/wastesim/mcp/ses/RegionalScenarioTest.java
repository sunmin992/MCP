package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.*;
import com.wastesim.pes.*;
import com.wastesim.template.*;
import com.wastesim.region.RegionCatalog;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalScenarioTest {
    private final ObjectMapper json=new ObjectMapper();
    private final TemplateCatalog catalog=new TemplateCatalog();
    private final ScenarioBuilder builder=new ScenarioBuilder(new PesFlattener(catalog),new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(),CollectionSiteRegistry.empty()),new SubtaskPlanner(catalog,new AnswerNormalizer()));
    private final ScenarioStore store=new ScenarioStore();
    private JsonNode args() throws Exception { return json.readTree("""
      {"request":"합성 블록에서 수거 시각 비교", "regionId":"synthetic-block",
       "values":{"truckType":"SMALL_1TON","truckCount":1,"days":2,"seeds":1,"trafficMode":"IGNORE",
         "travelTimeMode":"LEGACY_CONSTANT","occupationMix":["Student"],"routeAvailableCapacityKg":150,
         "binCapacityKg":30,"complaintThreshold":0.8,"dischargeTimeMode":"PAPER_BASELINE"},
       "variableAnswerKey":"collectionTimeMinutes","variableValues":[360,1080],"observations":["meanComplaints"]}
      """); }
    private JsonNode build(JsonNode args) throws Exception {
        var result=new BuildScenarioTool(builder,store,catalog,json).call(args);
        assertTrue(result.ready(),result.toString()); return json.readTree(result.result().toString());
    }
    @Test void regionalDefaultsBecomeRealSettingsAndTraceableDataOrigins() throws Exception {
        var out=build(args()); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        var entry=store.entry(out.path("scenarioId").asText()).orElseThrow();
        assertEquals(6,entry.scenario().runs().get(0).getNumBuildings());
        assertEquals(12,entry.scenario().runs().get(0).getResidentsPerBuilding());
        assertEquals("REGION_DATA",entry.pes().origins().get("numBuildings"));
        assertEquals("synthetic-block",entry.scenario().runs().get(0).getRegionId());
        assertTrue(out.path("region").path("synthetic").asBoolean());
        assertEquals(2,out.path("structureSelection").size());
        assertTrue(out.path("structureSelection").get(0).path("decisions").get(0).hasNonNull("sesPath"));
        assertTrue(store.validation(out.path("scenarioId").asText()).path("region").isObject());
    }
    @Test void syntheticRegionCannotUseJangnyangTraffic() throws Exception {
        var a=(com.fasterxml.jackson.databind.node.ObjectNode)args();
        ((com.fasterxml.jackson.databind.node.ObjectNode)a.get("values")).put("trafficMode","APPLY").put("trafficProfileId","weekday");
        var out=build(a); assertFalse(out.path("valid").asBoolean());
    }
    @Test void originalRequirementMismatchPreventsStorage() throws Exception {
        var a=(com.fasterxml.jackson.databind.node.ObjectNode)args();
        a.set("requirements",json.readTree("{\"simulationDuration\":{\"value\":3,\"unit\":\"day\"}}"));
        var out=build(a); assertFalse(out.path("valid").asBoolean());
        assertTrue(store.entry(out.path("scenarioId").asText()).isEmpty());
        assertFalse(out.path("requirementErrors").isEmpty());
    }
    @Test void missingRegionAndInvalidBundleFailExplicitly() throws Exception {
        assertTrue(new RegionCatalog().region("unknown").path("synthetic").asBoolean());
        assertThrows(IllegalArgumentException.class,()->new RegionCatalog(json.readTree("{\"regions\":[]}")));
        var cfg=new com.wastesim.model.SimulationConfig(); cfg.setRegionDataDigest("changed");
        assertFalse(new RegionCatalog().check(cfg).isEmpty());
    }
    @Test void selectionSurvivesApprovalExecutionAndRequestRevisionInvalidatesIt() throws Exception {
        var a=(com.fasterxml.jackson.databind.node.ObjectNode)args();
        a.put("requestId","regional-flow");
        var requirements=json.readTree("{\"regionId\":\"synthetic-block\",\"requiredOutputs\":[\"meanComplaints\"]}");
        var card=new com.wastesim.capability.CapabilityCardLoader().rawJson();
        a.set("selection",com.wastesim.contract.SelectionContract.handoff("match-test",card,requirements));
        a.remove("regionId"); // Forwarded requirements select the region without duplicating user input.
        var out=build(a); assertTrue(out.path("valid").asBoolean(),out.toPrettyString());
        var id=out.path("scenarioId").asText();
        var runArgs=json.createObjectNode().put("scenarioId",id);
        var service=new com.wastesim.service.SimulationService(new com.wastesim.simulation.SimulationEngine(new TrafficDataService()));
        var runner=new RunScenarioByTokenTool(builder,store,service,json);
        assertFalse(runner.call(runArgs).ready(),"Unconfirmed scenarios must be rejected");
        // In-memory approval simulation for this test; does not approve any user-owned scenario.
        var confirmed=new ConfirmScenarioTool(builder,store,json).call(runArgs);
        assertTrue(confirmed.ready());
        runArgs.put("confirmToken",json.readTree(confirmed.result().toString()).path("confirmToken").asText());
        var executed=runner.call(runArgs); assertTrue(executed.ready(),executed.toString());
        var result=json.readTree(executed.result().toString());
        assertEquals("match-test",result.path("validation").path("matchId").asText());
        assertEquals(2,result.path("runs").size());
        assertFalse(result.path("runs").get(0).path("meanComplaints").isMissingNode());
        a.put("regionId","jangnyang"); // Conflicts with selection, but still invalidates the preceding proof.
        build(a);
        assertFalse(store.validationCurrent(id));
        assertFalse(runner.call(runArgs).ready());
    }
}
