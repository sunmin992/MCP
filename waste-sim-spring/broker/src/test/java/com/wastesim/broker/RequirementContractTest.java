package com.wastesim.broker;

import com.fasterxml.jackson.databind.*;
import com.wastesim.contract.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode read(String s) throws Exception { return json.readTree(s); }
    private JsonNode card() throws Exception { return read("""
        {"schemaVersion":"1.0.0","serverId":"weekly","domain":["waste"],"endpoint":"http://localhost:9999/mcp",
         "capabilities":{"timeResolution":{"value":1,"unit":"week"},"regions":["A"],
         "simulationDuration":{"min":{"value":1,"unit":"day"},"max":{"value":30,"unit":"day"}},
         "outputs":["mean"],"constraints":{"trucks":{"min":1,"max":3}}}}
        """); }
    @Test void rejectsWeeklyEngineForHourlyRequestEvenWithGoodPreference() throws Exception {
        var request = read("""
            {"timeResolution":{"value":1,"unit":"hour"},"preferences":{"trucks":2}}
            """);
        var profile = new RequestProfile("waste", null,null,null,null,null,request);
        var matcher = new CandidateMatcher(new CandidateRegistry(card(), "classpath*:/nonexistent/*.json"));
        var result = matcher.evaluateOne(card(),profile);
        assertFalse(result.fitsAsIs());
        assertEquals("UNSATISFIED",result.conditionChecks().get(0).status());
        assertEquals("SATISFIED",result.conditionChecks().get(1).status());
    }
    @Test void convertsUnitsWithoutInventingAggregation() throws Exception {
        var c = card();
        assertEquals("SATISFIED",RequirementContract.evaluate(read("{\"timeResolution\":{\"value\":7,\"unit\":\"day\"}}"),c).get(0).status());
        assertEquals("UNSATISFIED",RequirementContract.evaluate(read("{\"timeResolution\":{\"value\":2,\"unit\":\"week\"}}"),c).get(0).status());
    }
    @Test void undeclaredCapabilityIsUnknownAndPreferenceDoesNotBlock() throws Exception {
        var c=read("{\"domain\":[\"waste\"]}");
        var checks=RequirementContract.evaluate(read("{\"regionId\":\"A\",\"preferences\":{\"trucks\":2}}"),c);
        assertEquals("UNKNOWN",checks.get(0).status()); assertFalse(checks.get(1).required());
    }
    @Test void validatesVersionsTypesAndUnknownFields() throws Exception {
        for(String s:List.of("{\"schemaVersion\":\"2.0.0\"}","{\"timeResolution\":{\"value\":0,\"unit\":\"hour\"}}",
                "{\"constraints\":{\"x\":\"3\"}}","{\"typo\":1}")) {
            JsonNode node=read(s); assertThrows(IllegalArgumentException.class,()->RequirementContract.validate(node));
        }
        assertTrue(RequirementContract.validate(null).isEmpty());
        var schema=read(RequirementContract.toolSchema("{\"type\":\"object\",\"properties\":{}}"));
        var properties=schema.path("properties").path("requirements").path("properties");
        assertTrue(java.util.regex.Pattern.matches(properties.path("schemaVersion").path("pattern").asText(),"1.0.0"));
        assertTrue(properties.path("timeResolution").path("required").toString().contains("unit"));
    }
    @Test void handoffRejectsChangedCardWrongProviderAndChangedRequirements() throws Exception {
        var card=card(); var req=read("{\"regionId\":\"A\"}");
        var args=json.createObjectNode(); args.set("selection",SelectionContract.handoff("match-1",card,req));
        assertEquals(req,SelectionContract.requirements(args,card));
        var changed=card.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("name","changed");
        assertThrows(IllegalArgumentException.class,()->SelectionContract.requirements(args,changed));
        args.set("requirements",read("{\"regionId\":\"B\"}"));
        assertThrows(IllegalArgumentException.class,()->SelectionContract.requirements(args,card));
    }
    @Test void supportedCardCannotHideWrongActualDurationOrMissingOutput() throws Exception {
        var req=read("{\"simulationDuration\":{\"value\":2,\"unit\":\"day\"},\"requiredOutputs\":[\"mean\"]}");
        assertFalse(SelectionContract.verify(req,card(),read("[{\"days\":1}]"),List.of("mean"),Set.of("mean")).ready());
        assertFalse(SelectionContract.verify(req,card(),read("[{\"days\":2}]"),List.of(),Set.of("mean")).ready());
        assertTrue(SelectionContract.verify(req,card(),read("[{\"days\":2}]"),List.of("mean"),Set.of("mean")).ready());
    }
    @Test void conditionalOutputRequiresActualConfigurationVerification() throws Exception {
        var c=(com.fasterxml.jackson.databind.node.ObjectNode)card();
        ((com.fasterxml.jackson.databind.node.ObjectNode)c.get("capabilities")).putArray("conditionalOutputs").add("mean");
        var req=read("{\"requiredOutputs\":[\"mean\"]}");
        assertEquals("CONDITIONAL",RequirementContract.evaluate(req,c).get(0).status());
        assertFalse(SelectionContract.verify(req,c,read("[{\"days\":2}]"),List.of("mean"),Set.of()).ready());
        var report=SelectionContract.verify(req,c,read("[{\"days\":2}]"),List.of("mean"),Set.of("mean"));
        assertTrue(report.ready());
        assertEquals("CONFIGURATION_VERIFIED",report.checks().get(0).code());
    }
    @Test void unknownRegionCanUseAnExplicitAssumedBlockPolicyWithoutClaimingMeasuredCoverage() throws Exception {
        var c=(com.fasterxml.jackson.databind.node.ObjectNode)card();
        ((com.fasterxml.jackson.databind.node.ObjectNode)c.get("capabilities")).putObject("regionPolicy")
                .put("mode","ASSUMED_BLOCK_FOR_ANY_LABEL");
        var req=read("{\"regionId\":\"덕명동\"}");
        var check=RequirementContract.evaluate(req,c).get(0);
        assertEquals("CONDITIONAL",check.status());
        var matcher = new CandidateMatcher(new CandidateRegistry(c,"classpath*:/nonexistent/*.json"));
        var result=matcher.evaluateOne(c,new RequestProfile("waste",null,null,null,null,null,req));
        assertTrue(result.fitsAsIs());
        assertEquals("CONDITIONAL",result.conditionChecks().get(0).status());
        assertFalse(SelectionContract.verify(req,c,read("[{\"regionId\":\"A\"}]"),List.of(),List.of()).ready());
    }
}
