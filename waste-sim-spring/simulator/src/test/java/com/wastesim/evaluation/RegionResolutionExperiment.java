package com.wastesim.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.region.OsmRegionResolver;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * 등록하지 않은 지역 이름만으로 공개 지도 블록을 만들어 보는 실험. 실제 네트워크를 쓰므로 기본 *Test 실행에서 빠진다.
 * <pre>.\mvnw.cmd -B -pl simulator -am test '-Dtest=RegionResolutionExperiment' '-Dsurefire.failIfNoSpecifiedTests=false'</pre>
 * 일부 지역의 응답 원문은 OsmRegionResolverTest 의 재생 자료로 저장한다.
 */
class RegionResolutionExperiment {
    static final List<String> REGIONS = List.of("덕명동", "장량동", "중앙동", "역삼동", "연남동", "둔산동", "신림동", "송도동",
            "상도동", "봉명동", "중앙동 중구 부산광역시", "풍천면", "가나다라마동");
    static final Set<String> FIXTURES = Set.of("덕명동", "중앙동", "가나다라마동");
    static final Path OUT = Path.of("../docs/reports/region-resolution-2026-11-03");
    static final Path FIXTURE_DIR = Path.of("src/test/resources/regions/osm");

    @Test void resolveRegions() throws Exception {
        var json = new ObjectMapper();
        var live = OsmRegionResolver.httpClient("waste-sim-research/1.0 (Hanbat National University; region experiment)");
        Path cache = Files.createTempDirectory("region-experiment");
        var rows = json.createArrayNode();
        for (String name : REGIONS) {
            Map<String, String> recorded = new LinkedHashMap<>();
            Function<URI, String> http = uri -> { String body = live.apply(uri); recorded.put(uri.toString(), body); return body; };
            long started = System.currentTimeMillis();
            var result = new OsmRegionResolver(http, cache, "https://nominatim.openstreetmap.org",
                    "https://overpass-api.de/api", "https://router.project-osrm.org").resolve(name);
            ObjectNode row = rows.addObject().put("query", name).put("status", result.status().name())
                    .put("reason", result.reason()).put("seconds", (System.currentTimeMillis() - started) / 1000.0);
            if (result.region() != null) {
                var r = result.region();
                row.put("dataGrade", r.path("dataGrade").asText()).put("displayName", r.path("osm").path("displayName").asText())
                        .put("searchArea", r.path("osm").path("searchArea").asText())
                        .put("numBuildings", r.path("defaults").path("numBuildings").asInt())
                        .put("residentsPerBuilding", r.path("defaults").path("residentsPerBuilding").asInt())
                        .put("routeTravelMinutes", r.path("modelOverrides").path("routeTravelMinutes").asInt())
                        .put("roadHopMinutes", r.path("block").path("roadHopMinutes").asDouble())
                        .put("observedLevelShare", r.path("block").path("observedLevelShare").asDouble());
                row.set("provenance", r.path("provenance"));
            }
            if (!result.candidates().isEmpty()) row.set("candidates", json.valueToTree(result.candidates()));
            if (FIXTURES.contains(name)) {
                Files.createDirectories(FIXTURE_DIR);
                json.writerWithDefaultPrettyPrinter().writeValue(FIXTURE_DIR.resolve(name + ".json").toFile(), recorded);
            }
            System.out.println(row);
        }
        Files.createDirectories(OUT);
        json.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("results.json").toFile(), rows);
        Files.createDirectories(OUT.resolve("bundles"));
        try (var s = Files.list(cache)) {
            for (Path p : s.toList()) if (!p.toString().endsWith(".raw.json"))
                Files.copy(p, OUT.resolve("bundles").resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
