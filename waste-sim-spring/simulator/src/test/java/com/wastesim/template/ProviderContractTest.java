package com.wastesim.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.readiness.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProviderContractTest {
    final ObjectMapper json = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void providerCanRegisterAnActualArtifactValidatorAndCannotOmitItsFunction() throws Exception {
        var file=directory.resolve("input.json");
        java.nio.file.Files.writeString(file,"{\"nodes\":[]}");
        var tasks=List.of(
                new TaskManifest.Definition("data","데이터 준비",List.of(),List.of("path"),List.of("validated-data"),"DATA"),
                new TaskManifest.Definition("config","설정 검사",List.of("data"),List.of(),List.of("config"),"CONFIG"),
                new TaskManifest.Definition("mapping","반영 검사",List.of("config"),List.of(),List.of("mapping"),"ROUNDTRIP"),
                new TaskManifest.Definition("scenario","통합 검사",List.of("mapping"),List.of(),List.of("scenario"),"SCENARIO"));
        var manifest=new TaskManifest("1",tasks,List.of(),List.of());
        manifest.validate(Set.of("DATA","CONFIG","ROUNDTRIP","SCENARIO"));
        Map<String,java.util.function.Supplier<List<String>>> checks=new HashMap<>();
        checks.put("DATA",()-> {
            try { return json.readTree(file.toFile()).path("nodes").isEmpty() ? List.of("필수 데이터가 비었습니다") : List.of(); }
            catch(Exception e) { return List.of("데이터를 읽을 수 없습니다"); }
        });
        checks.put("CONFIG",List::of); checks.put("ROUNDTRIP",List::of); checks.put("SCENARIO",List::of);
        assertFalse(TaskCompletion.evaluate(manifest.instantiateWithChecks("",checks),Map.of("path","FILLED")).ready());
        java.nio.file.Files.writeString(file,"{\"nodes\":[1,2]}");
        assertTrue(TaskCompletion.evaluate(manifest.instantiateWithChecks("",checks),Map.of("path","FILLED")).ready());
        checks.remove("DATA");
        assertThrows(IllegalArgumentException.class,()->manifest.instantiateWithChecks("",checks));
    }
    @Test void providerManifestCoversEveryTemplateAndHasExplicitOutputs() {
        var manifest = TaskManifest.load(ProviderContractTest.class);
        manifest.verifyCoverage(new TemplateCatalog().all().stream().map(SubtaskTemplate::answerKey).collect(java.util.stream.Collectors.toSet()));
        assertTrue(manifest.tasks().stream().allMatch(t -> !t.outputs().isEmpty() && !t.label().isBlank()));
        assertFalse(manifest.resourceFingerprint(ProviderContractTest.class).isBlank());
        assertThrows(IllegalArgumentException.class, () -> manifest.verifyCoverage(Set.of("missing")));
    }
    @Test void unregisteredValidatorAndDuplicateOwnershipAreRejected() {
        var bad = new TaskManifest.Definition("x", "작업", List.of(), List.of("a"), List.of("artifact"), "LLM_SAYS_DONE");
        assertThrows(IllegalArgumentException.class, () -> new TaskManifest("1", List.of(bad), List.of(), List.of()).validate());
        var a = new TaskManifest.Definition("a", "작업", List.of(), List.of("a"), List.of("artifact"), "INPUTS");
        var b = new TaskManifest.Definition("b", "작업", List.of(), List.of("a"), List.of("artifact"), "INPUTS");
        assertThrows(IllegalArgumentException.class, () -> new TaskManifest("1", List.of(a,b), List.of(), List.of()).validate());
    }
    @Test void receiptInvalidatesWhenItsInputOrDataVersionChanges() {
        var version = new AtomicReference<>("v1");
        var receipt = new ValidationReceipt(json.createObjectNode().put("ready",true), version::get);
        assertTrue(receipt.current());
        version.set("v2"); assertFalse(receipt.current());
        assertFalse(receipt.view().path("current").asBoolean());
        assertTrue(receipt.view().hasNonNull("validatedAt"));
    }
    @Test void fingerprintsIgnoreObjectOrderingButPreserveValues() throws Exception {
        assertEquals(ValidationReceipt.fingerprint(json.readTree("{\"a\":1,\"b\":2}")),
                ValidationReceipt.fingerprint(json.readTree("{\"b\":2,\"a\":1}")));
        assertNotEquals(ValidationReceipt.fingerprint(json.readTree("{\"a\":1}")),
                ValidationReceipt.fingerprint(json.readTree("{\"a\":2}")));
    }
    @Test void purposeRequiresTheRequestedAxisValuesAndAvailableMetric() throws Exception {
        var purpose=json.readTree("{\"mode\":\"COMPARE\",\"comparisonKey\":\"truckCount\",\"comparisonValues\":[1,3],\"observations\":[\"meanComplaints\"]}");
        assertTrue(PurposeCheck.evaluate(purpose,"truckCount",List.of(1,3),List.of("meanComplaints"),Set.of("meanComplaints")).acceptable());
        assertFalse(PurposeCheck.evaluate(purpose,"truckCount",List.of(1,2),List.of("meanComplaints"),Set.of("meanComplaints")).acceptable());
        assertFalse(PurposeCheck.evaluate(purpose,"days",List.of(1,3),List.of("meanComplaints"),Set.of("meanComplaints")).acceptable());
        assertFalse(PurposeCheck.evaluate(purpose,"truckCount",List.of(1,3),List.of(),Set.of("meanComplaints")).acceptable());
        assertFalse(PurposeCheck.evaluate(purpose,"truckCount",List.of(1,3),List.of("meanComplaints"),Set.of()).acceptable());
        assertFalse(PurposeCheck.evaluate(purpose,"truckCount",List.of(1,3),List.of(1,1),List.of("meanComplaints"),Set.of("meanComplaints")).acceptable());
        assertEquals("NOT_SPECIFIED",PurposeCheck.evaluate(null,"truckCount",List.of(1,3),List.of(),Set.of()).status());
    }
    @Test void missingOrNullResultMetricsCannotSatisfyThePurpose() throws Exception {
        var spec=json.readTree("{\"observations\":[\"judgement.wait\"]}");
        assertTrue(PurposeCheck.verifyResults(spec,json.readTree("[{\"judgement\":{\"wait\":3}}]")).isEmpty());
        assertFalse(PurposeCheck.verifyResults(spec,json.readTree("[{\"judgement\":{\"wait\":null}}]")).isEmpty());
        assertFalse(PurposeCheck.verifyResults(spec,json.readTree("[{}]")).isEmpty());
    }
}
