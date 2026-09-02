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
 * 프로덕션 자유주행시간 행렬의 출처를 잠근다.
 *
 * <p>이 행렬의 값은 <b>교통 구역 좌표로 잰 것이고 수거 지점 실측이 아니다.</b> 수거 지점
 * 좌표가 비어 있는 동안 {@code OSRM_HYBRID}를 쓸 수 있게 하려고 구역 측정치를 잠정으로
 * 넣었다. 구간 id가 {@code Node_A~D}라서 수거 지점 id와 형태가 같지만 가리키는 대상이
 * 다르다 — 그 구분이 이 프로젝트에서 하루를 들여 갈라낸 것이라, 표시가 사라지면 다시
 * 뒤섞인다.
 *
 * <p>그래서 두 가지를 단언한다 — <b>잠정이라는 표시가 남아 있는가</b>, 그리고 <b>원 기록과
 * 값이 일치하는가</b>. 한쪽만 갱신되면 어느 쪽이 맞는지 알 수 없게 된다.
 */
class TravelTimeMatrixProvenanceTest {

    private static JsonNode matrix;
    private static JsonNode zones;

    @BeforeAll
    static void load() throws IOException {
        matrix = read(TravelTimeMatrix.RESOURCE);
        zones = read(TrafficZoneRegistry.RESOURCE);
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream in = TravelTimeMatrixProvenanceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + "가 클래스패스에 없습니다.");
            return new ObjectMapper().readTree(in);
        }
    }

    /**
     * 값이 원 기록과 일치해야 한다. 행렬은 {@code jangryang-traffic-zones.json}의
     * {@code measuredOsrm}에서 시간 성분만 가져온 것이므로, 한쪽을 다시 재고 다른 쪽을 두면
     * 같은 측정에 두 값이 생긴다.
     */
    @Test
    void everyPairMatchesTheOriginalZoneMeasurement() {
        JsonNode pairs = matrix.path("pairs");
        JsonNode origin = zones.path("measuredOsrm").path("pairs");
        assertTrue(pairs.size() > 0, "행렬이 비었습니다.");

        List<String> mismatched = new ArrayList<>();
        pairs.fields().forEachRemaining(e -> {
            JsonNode src = origin.path(e.getKey());
            if (!src.isArray() || src.size() != 2
                    || Math.abs(src.get(1).asDouble() - e.getValue().asDouble()) > 0.05) {
                mismatched.add(e.getKey() + " 행렬=" + e.getValue() + " 원기록=" + src);
            }
        });
        assertEquals(List.of(), mismatched,
                "행렬과 구역 측정 기록이 어긋납니다. 다시 쟀다면 두 파일을 함께 갱신하세요.");
    }

    /** 12개 순서쌍이 모두 있어야 4개 지점 경로를 어느 순서로든 계산할 수 있다. */
    @Test
    void coversAllTwelveOrderedPairs() {
        JsonNode pairs = matrix.path("pairs");
        assertEquals(12, pairs.size());
        for (String a : List.of("Node_A", "Node_B", "Node_C", "Node_D")) {
            for (String b : List.of("Node_A", "Node_B", "Node_C", "Node_D")) {
                if (a.equals(b)) continue;
                assertTrue(pairs.has(a + "->" + b), a + "->" + b + "가 없습니다.");
            }
        }
    }

    /**
     * 잠정이라는 표시가 남아 있어야 한다. 이 표시가 사라지면 다음 사람은 이 값을 수거 지점
     * 실측으로 읽고, 교통 구역과 수거 지점을 갈라낸 작업이 무의미해진다.
     */
    @Test
    void provisionalCoordinateSourceStaysOnTheRecord() {
        assertEquals("TRAFFIC_ZONE_PROVISIONAL", matrix.path("coordinateSource").asText(),
                "수거 지점 좌표로 다시 쟀다면 COLLECTION_SITE_MEASURED로 바꾸고 이 단언도 함께 고치세요.");
        String meaning = matrix.path("coordinateSourceMeaning").asText("");
        assertTrue(meaning.contains("수거 지점 실측이 아니다"), meaning);
        assertTrue(meaning.contains("별개의 이름공간"), meaning);
    }

    /** 자유주행시간이라는 성질 표시 — TMAP 값을 여기 넣으면 혼잡을 두 번 센다. */
    @Test
    void statesThatValuesAreFreeFlowNotTrafficInclusive() {
        assertTrue(matrix.path("notTrafficWeights").asText("").contains("두 번 센다"),
                "자유주행시간이라는 점과 그 이유가 기록돼 있어야 합니다.");
    }

    /** 방향성이 있다는 것도 실제 값으로 확인한다 — 같은 두 지점의 왕복이 다르다. */
    @Test
    void matrixIsDirectionalInPractice() {
        double ab = matrix.path("pairs").path("Node_A->Node_B").asDouble();
        double ba = matrix.path("pairs").path("Node_B->Node_A").asDouble();
        assertTrue(Math.abs(ab - ba) > 1.0,
                "왕복이 같으면 방향성 서술이 데이터로 뒷받침되지 않는다: " + ab + " vs " + ba);
    }

    /**
     * 이 커밋의 요점 — 행렬이 채워졌으므로 {@code OSRM_HYBRID}가 이제 <b>검증을 통과한다.</b>
     * 그전에는 V-T6이 "자유주행시간이 없는 구간이 있다"로 막았다.
     */
    @Test
    void osrmHybridIsNowUsableWithTheShippedMatrix() {
        SimulationConfig c = new SimulationConfig();
        c.setNumBuildings(4);
        c.setTravelTimeMode("OSRM_HYBRID");

        var r = new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty())
                .validate(c);
        assertTrue(r.ready(), "기본 행렬로 OSRM_HYBRID가 통과해야 한다: " + r.errors());
    }

    /** 다만 5개 지점은 아직 덮지 못한다 — 좌표가 4곳뿐이라는 사실이 그대로 드러나야 한다. */
    @Test
    void fifthSiteIsStillUncovered() {
        SimulationConfig c = new SimulationConfig();
        c.setNumBuildings(5);
        c.setTravelTimeMode("OSRM_HYBRID");

        var r = new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty())
                .validate(c);
        assertFalse(r.ready(), "측정하지 않은 구간을 조용히 통과시키면 안 된다");
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("Node_E")),
                r.errors().toString());
    }
}
