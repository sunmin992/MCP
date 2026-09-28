package com.wastesim.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 두 자유주행시간 행렬의 출처를 잠근다 — <b>수거 지점</b> 간 행렬과 <b>교통 구역</b> 간 행렬.
 *
 * <p>구간 키가 양쪽 모두 {@code Node_A->Node_B} 형태라서, 한 파일에 두면 어느 이름공간의
 * 값인지 구별할 수 없다. 실제로 한때 구역 좌표로 잰 값이 지점 간 행렬에 잠정으로 들어가
 * 있었고, 그 상태에서는 {@code OSRM_HYBRID}가 스스로를 실측이라고 부르면서 실은 구역 근사를
 * 계산했다. 그래서 파일을 갈랐다 —
 *
 * <ul>
 *   <li>{@link TravelTimeMatrix#RESOURCE}: 실제 지점 좌표로 잰 값만. 지금은 <b>비어 있다.</b></li>
 *   <li>{@link TravelTimeMatrix#ZONE_RESOURCE}: 구역 좌표 실측 12쌍.
 *       {@code ZONE_PROXY_HYBRID}가 읽는다.</li>
 * </ul>
 *
 * <p>이 테스트가 지키는 것은 <b>그 경계</b>다. 지점 간 행렬에 구역 값이 다시 흘러들면 실패한다.
 */
class TravelTimeMatrixProvenanceTest {

    private static JsonNode siteMatrix;
    private static JsonNode zoneMatrix;
    private static JsonNode zones;

    @BeforeAll
    static void load() throws IOException {
        siteMatrix = read(TravelTimeMatrix.RESOURCE);
        zoneMatrix = read(TravelTimeMatrix.ZONE_RESOURCE);
        zones = read(TrafficZoneRegistry.RESOURCE);
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream in = TravelTimeMatrixProvenanceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + "가 클래스패스에 없습니다.");
            return new ObjectMapper().readTree(in);
        }
    }

    private static SimulationConfigValidator validator() {
        return new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty());
    }

    // ── 구역 간 행렬: 값이 원 기록과 일치해야 한다 ──────────────────────────

    /**
     * 구역 행렬은 {@code jangryang-traffic-zones.json}의 {@code measuredOsrm}에서 시간 성분만
     * 가져온 것이다. 한쪽을 다시 재고 다른 쪽을 두면 같은 측정에 두 값이 생긴다.
     */
    @Test
    void everyZonePairMatchesTheOriginalZoneMeasurement() {
        JsonNode pairs = zoneMatrix.path("pairs");
        JsonNode origin = zones.path("measuredOsrm").path("pairs");
        assertTrue(pairs.size() > 0, "구역 행렬이 비었습니다.");

        List<String> mismatched = new ArrayList<>();
        pairs.fields().forEachRemaining(e -> {
            JsonNode src = origin.path(e.getKey());
            if (!src.isArray() || src.size() != 2
                    || Math.abs(src.get(1).asDouble() - e.getValue().asDouble()) > 0.05) {
                mismatched.add(e.getKey() + " 행렬=" + e.getValue() + " 원기록=" + src);
            }
        });
        assertEquals(List.of(), mismatched,
                "구역 행렬과 구역 측정 기록이 어긋납니다. 다시 쟀다면 두 파일을 함께 갱신하세요.");
    }

    /** 12개 순서쌍이 모두 있어야 4개 구역 경로를 어느 순서로든 계산할 수 있다. */
    @Test
    void zoneMatrixCoversAllTwelveOrderedPairs() {
        JsonNode pairs = zoneMatrix.path("pairs");
        assertEquals(12, pairs.size());
        for (String a : List.of("Node_A", "Node_B", "Node_C", "Node_D")) {
            for (String b : List.of("Node_A", "Node_B", "Node_C", "Node_D")) {
                if (a.equals(b)) continue;
                assertTrue(pairs.has(a + "->" + b), a + "->" + b + "가 없습니다.");
            }
        }
    }

    /**
     * 구역 행렬의 키가 <b>교통 구역</b>을 가리킨다는 표시가 남아 있어야 한다. 이 표시가
     * 사라지면 다음 사람은 이 값을 수거 지점 실측으로 읽는다.
     */
    @Test
    void zoneMatrixSaysItsKeysAreZonesNotSites() {
        String s = zoneMatrix.path("keysAreZones").asText("");
        assertTrue(s.contains("교통 구역"), s);
        assertTrue(s.contains("별개의 이름공간"), s);
    }

    /** 구역 행렬에는 대각 성분이 없다 — 구역은 점이 아니라 영역이다. */
    @Test
    void zoneMatrixHasNoDiagonalAndSaysWhy() {
        zoneMatrix.path("pairs").fieldNames().forEachRemaining(k -> {
            String[] ends = k.split("->");
            assertNotEquals(ends[0], ends[1], "같은 구역끼리의 구간이 있습니다: " + k);
        });
        assertTrue(zoneMatrix.path("noDiagonal").asText("").contains("intraZoneTravelMinutes"),
                "구역 내 이동을 무엇이 담당하는지가 기록돼 있어야 합니다.");
    }

    /** 자유주행시간이라는 성질 표시 — TMAP 값을 여기 넣으면 혼잡을 두 번 센다. */
    @Test
    void zoneMatrixStatesValuesAreFreeFlowNotTrafficInclusive() {
        assertTrue(zoneMatrix.path("notTrafficWeights").asText("").contains("두 번 센다"),
                "자유주행시간이라는 점과 그 이유가 기록돼 있어야 합니다.");
    }

    /** 방향성이 있다는 것을 실제 값으로 확인한다 — 같은 두 구역의 왕복이 다르다. */
    @Test
    void zoneMatrixIsDirectionalInPractice() {
        double ab = zoneMatrix.path("pairs").path("Node_A->Node_B").asDouble();
        double ba = zoneMatrix.path("pairs").path("Node_B->Node_A").asDouble();
        assertTrue(Math.abs(ab - ba) > 1.0,
                "왕복이 같으면 방향성 서술이 데이터로 뒷받침되지 않는다: " + ab + " vs " + ba);
    }

    // ── 지점 간 행렬: 비어 있는 것이 맞다 ──────────────────────────────────

    /**
     * <b>이 테스트가 경계를 지킨다.</b> 지점 간 행렬에 값이 들어와 있다면, 그 값이 실제 수거
     * 지점 좌표로 잰 것이어야 한다. 등록된 지점이 0곳인데 값이 있으면 어딘가에서 구역 값이
     * 흘러든 것이다.
     */
    @Test
    void siteMatrixIsEmptyWhileNoSiteCoordinatesExist() {
        assertEquals(0, CollectionSiteRegistry.empty().size(),
                "등록된 수거 지점이 생겼다면 이 테스트의 전제를 다시 세우세요.");
        assertEquals(0, siteMatrix.path("pairs").size(),
                "수거 지점 좌표가 0곳인데 지점 간 행렬에 값이 있습니다. 구역 좌표로 잰 값을 "
                        + "여기 넣으면 안 됩니다 — traffic/jangryang-zone-travel-times.json으로 가세요.");
        assertEquals("MEASURED_SITE_REQUIRED", siteMatrix.path("coordinateSource").asText());
        assertTrue(siteMatrix.path("whyEmpty").asText("").contains("실제 수거 지점 좌표로 잰 값만"),
                "비어 있는 이유가 기록돼 있어야 합니다.");
    }

    /**
     * 따라서 {@code OSRM_HYBRID}는 <b>막혀 있다.</b> 좌표가 없다는 사실의 정확한 반영이며,
     * 조용히 통과시켜 근사값을 실측으로 부르는 것보다 낫다.
     */
    @Test
    void osrmHybridIsBlockedUntilSiteCoordinatesExist() {
        SimulationConfig c = new SimulationConfig();
        c.setNumBuildings(4);
        c.setTravelTimeMode("OSRM_HYBRID");

        var r = validator().validate(c);
        assertFalse(r.ready(), "지점 좌표가 없는데 OSRM_HYBRID가 통과하면 안 된다");
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("ZONE_PROXY_HYBRID")),
                "무엇을 대신 쓰면 되는지 알려줘야 한다: " + r.errors());
    }
}
