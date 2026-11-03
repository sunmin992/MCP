package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.pes.*;
import com.wastesim.region.*;
import com.wastesim.service.*;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.*;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** 녹화한 Nominatim · Overpass · OSRM 응답(2026-11-03, OSM ODbL)으로 공개 지도 블록 경로를 결정적으로 검사한다. */
class PublicMapRegionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog, new AnswerNormalizer());
    private final ScenarioStore store = new ScenarioStore();
    private final ScenarioBuilder builder = new ScenarioBuilder(new PesFlattener(catalog), new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()), planner);
    private Path cache;
    private String previousCache;

    @BeforeEach void isolateCache() throws Exception {
        cache = Files.createTempDirectory("region-cache");
        previousCache = System.getProperty("wastesim.region-cache");
        System.setProperty("wastesim.region-cache", cache.toString());
    }
    @AfterEach void restoreCache() {
        if (previousCache == null) System.clearProperty("wastesim.region-cache");
        else System.setProperty("wastesim.region-cache", previousCache);
    }
    private Function<URI, String> replay() {
        Map<String, String> bodies = new HashMap<>();
        for (String name : List.of("덕명동", "중앙동", "가나다라마동"))
            try (var in = getClass().getResourceAsStream("/regions/osm/" + name + ".json")) {
                json.readTree(in).fields().forEachRemaining(e -> bodies.put(e.getKey(), e.getValue().asText()));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return uri -> {
            String body = bodies.get(uri.toString());
            if (body == null) throw new IllegalStateException("녹화되지 않은 요청: " + uri);
            return body;
        };
    }
    private OsmRegionResolver resolver(Function<URI, String> http) {
        return new OsmRegionResolver(http, cache, "https://nominatim.openstreetmap.org",
                "https://overpass-api.de/api", "https://router.project-osrm.org");
    }
    private CreateNeighborhoodScenarioTool tool(OsmRegionResolver resolver) {
        return new CreateNeighborhoodScenarioTool(new BuildScenarioTool(builder, store, catalog, json), catalog, planner, store, json,
                new GetScenarioStatusTool(store, json, 8090), resolver);
    }
    private JsonNode create(CreateNeighborhoodScenarioTool tool, String region) throws Exception {
        var a = json.createObjectNode().put("request", region + " 시뮬레이터 실행해줘").put("regionName", region);
        a.putObject("values").put("days", 2).put("seeds", 1);
        var result = tool.call(a); assertTrue(result.ready(), result.toString());
        return json.readTree(result.result().toString());
    }

    @Test void unregisteredNameBecomesRealBlockWithGradedProvenance() throws Exception {
        var result = resolver(replay()).resolve("덕명동");
        assertEquals(OsmRegionResolver.Status.RESOLVED, result.status(), result.reason());
        var r = result.region();
        assertFalse(r.path("synthetic").asBoolean());
        assertEquals("PUBLIC_MAP_DATA", r.path("basis").asText());
        int n = r.path("defaults").path("numBuildings").asInt();
        assertTrue(n >= 2 && n <= 26);
        assertEquals(n, r.path("block").path("buildings").size());
        assertEquals("Node_A", r.path("block").path("buildings").get(0).path("node").asText());
        assertEquals("OBSERVED", r.path("provenance").path("numBuildings").path("grade").asText());
        assertEquals("ESTIMATED", r.path("provenance").path("residentsPerBuilding").path("grade").asText());
        assertEquals("ASSUMED", r.path("provenance").path("dischargeWindow").path("grade").asText());
        assertEquals("OBSERVED", r.path("provenance").path("routeTravelMinutes").path("grade").asText());
        assertTrue(r.path("modelOverrides").path("routeTravelMinutes").asInt() >= OsmRegionResolver.ASSUMED_STOP_MINUTES);
        try (var files = Files.list(cache)) {
            assertTrue(files.anyMatch(p -> p.toString().endsWith(".raw.json")), "조회 원문을 보관한다");
        }
    }

    @Test void createToolBuildsPublicDataScenarioThatRunsAfterApproval() throws Exception {
        var out = create(tool(resolver(replay())), "덕명동");
        assertTrue(out.path("valid").asBoolean(), out.toPrettyString());
        assertEquals("PUBLIC_DATA_BLOCK", out.path("scenarioMode").asText());
        assertEquals("REAL_BLOCK", out.path("dataGrade").asText());
        var region = RegionCatalog.cached(cache, "덕명동");
        var cfg = store.entry(out.path("scenarioId").asText()).orElseThrow().scenario().runs().get(0);
        assertEquals(region.path("defaults").path("numBuildings").asInt(), cfg.getNumBuildings());
        assertEquals(region.path("defaults").path("residentsPerBuilding").asInt(), cfg.getResidentsPerBuilding());
        assertEquals(region.path("modelOverrides").path("routeTravelMinutes").asInt(), cfg.getRouteTravelMinutes());
        // 수거장 용량은 세대 수에 비례한다 — 기본 30kg(25세대)을 대규모 블록에 그대로 쓰지 않는다.
        int households = region.path("defaults").path("residentsPerBuilding").asInt();
        assertEquals(Math.round(households * OsmRegionResolver.BIN_KG_PER_HOUSEHOLD), Math.round(cfg.getCapacity()));
        assertEquals("ESTIMATED", region.path("provenance").path("capacity").path("grade").asText());
        assertFalse(cfg.isTrafficEnabled());
        var pes = store.entry(out.path("scenarioId").asText()).orElseThrow().pes();
        assertEquals("REGION_DATA", pes.origins().get("numBuildings"));
        assertEquals("REGION_DATA", pes.origins().get("residentsPerBuilding"));
        assertFalse(pes.unapprovedDefaults().containsKey("numBuildings"));
        assertEquals("REGION_DATA", pes.origins().get("binCapacityKg"));

        String id = out.path("scenarioId").asText();
        var runner = new RunScenarioByTokenTool(builder, store,
                new SimulationService(new com.wastesim.simulation.SimulationEngine(new TrafficDataService())), json);
        var runArgs = json.createObjectNode().put("scenarioId", id);
        assertFalse(runner.call(runArgs).ready());
        // 승인 모사는 이 테스트의 메모리 보관소에서만 한다.
        var confirmed = new ConfirmScenarioTool(builder, store, json).call(runArgs); assertTrue(confirmed.ready());
        runArgs.put("confirmToken", json.readTree(confirmed.result().toString()).path("confirmToken").asText());
        var result = runner.call(runArgs); assertTrue(result.ready(), result.toString());
        var executed = json.readTree(result.result().toString());
        assertEquals("EXECUTED", executed.path("state").asText());
        assertEquals("PUBLIC_DATA_BLOCK", executed.path("scenarioMode").asText());
        assertEquals("덕명동", executed.path("region").path("id").asText());
    }

    @Test void secondRequestUsesCacheWithoutNetwork() {
        assertEquals(OsmRegionResolver.Status.RESOLVED, resolver(replay()).resolve("덕명동").status());
        var again = resolver(uri -> { throw new IllegalStateException("네트워크를 쓰면 안 됩니다"); }).resolve("덕명동");
        assertEquals(OsmRegionResolver.Status.RESOLVED, again.status());
        assertEquals("cache", again.reason());
    }

    @Test void bundleFromOlderRulesIsRefetched() throws Exception {
        assertEquals(OsmRegionResolver.Status.RESOLVED, resolver(replay()).resolve("덕명동").status());
        Path bundle;
        try (var files = Files.list(cache)) { bundle = files.filter(p -> !p.toString().endsWith(".raw.json")).findFirst().orElseThrow(); }
        var old = (ObjectNode) json.readTree(bundle.toFile());
        old.put("version", "1.0.0");
        ((ObjectNode) old.get("defaults")).remove("capacity");
        json.writeValue(bundle.toFile(), old);
        var again = resolver(replay()).resolve("덕명동");
        assertEquals("fetched", again.reason());
        assertTrue(again.region().path("defaults").has("capacity"));
    }

    @Test void ambiguousNameAsksInsteadOfGuessing() throws Exception {
        var out = create(tool(resolver(replay())), "중앙동");
        assertEquals("NEEDS_REGION", out.path("state").asText());
        assertFalse(out.path("valid").asBoolean());
        assertFalse(out.has("scenarioId"));
        assertTrue(out.path("regionCandidates").size() > 1);
        assertTrue(out.path("regionCandidates").toString().contains("부산광역시"));
    }

    @Test void unknownOrUnreachableFallsBackToLabelledAssumedBlock() throws Exception {
        var unknown = create(tool(resolver(replay())), "가나다라마동");
        assertEquals("ASSUMED_BLOCK", unknown.path("scenarioMode").asText());
        assertTrue(unknown.path("fallbackReason").asText().contains("찾지 못했습니다"));
        var offline = create(tool(resolver(uri -> { throw new IllegalStateException("연결 실패"); })), "어딘가동");
        assertTrue(offline.path("valid").asBoolean(), offline.toPrettyString());
        assertEquals("ASSUMED_BLOCK", offline.path("scenarioMode").asText());
        assertTrue(offline.path("fallbackReason").asText().contains("조회 실패"));
    }

    @Test void tamperedCacheIsRejectedAndChangedDataBreaksEarlierScenario() throws Exception {
        var out = create(tool(resolver(replay())), "덕명동");
        var cfg = store.entry(out.path("scenarioId").asText()).orElseThrow().scenario().runs().get(0);
        assertTrue(new RegionCatalog().check(cfg).isEmpty());
        Path bundle;
        try (var files = Files.list(cache)) { bundle = files.filter(p -> !p.toString().endsWith(".raw.json")).findFirst().orElseThrow(); }
        var edited = (ObjectNode) json.readTree(bundle.toFile());
        ((ObjectNode) edited.get("modelOverrides")).put("routeTravelMinutes", 3);
        json.writeValue(bundle.toFile(), edited);
        assertTrue(new RegionCatalog().check(cfg).stream().anyMatch(e -> e.contains("식별값")));
        edited.put("allowProviderTraffic", true);
        json.writeValue(bundle.toFile(), edited);
        assertThrows(IllegalArgumentException.class, () -> RegionCatalog.cached(cache, "덕명동"));
    }

    @Test void blockSelectionIsDeterministicAndBounded() {
        List<OsmRegionResolver.Building> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) many.add(new OsmRegionResolver.Building(1000 - i, "apartments", 36.35 + i * 1e-4, 127.30, 400, 10));
        var reversed = new ArrayList<>(many);
        Collections.reverse(reversed);
        var a = OsmRegionResolver.block(many);
        assertEquals(26, a.size());
        assertEquals(a, OsmRegionResolver.block(reversed));
    }
}
