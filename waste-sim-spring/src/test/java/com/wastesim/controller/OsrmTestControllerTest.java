package com.wastesim.controller;

import com.wastesim.service.OsrmRouteService;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsrmTestControllerTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void close() throws IOException {
        server.close();
    }

    @Test
    void returnsPerLegAndTotalDurationFromOsrm() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"code":"Ok",
                 "waypoints":[{"distance":5.0},{"distance":62.1},{"distance":11.8}],
                 "routes":[{"distance":3500.0,"duration":420.0,"legs":[
                  {"distance":1200.0,"duration":150.0},
                  {"distance":2300.0,"duration":270.0}
                ]}]}
                """));
        OsrmRouteService service = new OsrmRouteService(true, server.url("/").toString(), new OkHttpClient());
        OsrmTestController controller = new OsrmTestController(service);

        ResponseEntity<?> response = controller.route(new OsrmTestController.RouteRequest(
                List.of("Node_B", "Node_C", "Node_A"), Map.of(
                "Node_A", List.of(129.38, 36.08),
                "Node_B", List.of(129.37, 36.07),
                "Node_C", List.of(129.36, 36.06))));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(7.0, body.get("totalDurationMinutes"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> legs = (List<Map<String, Object>>) body.get("legs");
        assertEquals(2, legs.size());
        assertEquals("Node_B", legs.getFirst().get("from"));
        assertTrue(server.takeRequest().getPath().startsWith(
                "/route/v1/driving/129.37,36.07;129.36,36.06;129.38,36.08"));
    }

    @Test
    void disabledExperimentDoesNotCallOsrm() {
        OsrmRouteService service = new OsrmRouteService(false, "https://example.invalid", new OkHttpClient());
        ResponseEntity<?> response = new OsrmTestController(service).route(null);
        assertEquals(503, response.getStatusCode().value());
    }

    @Test
    void rejectsMissingOrInvalidCoordinatesBeforeNetworkCall() throws Exception {
        server.start();
        OsrmRouteService service = new OsrmRouteService(true, server.url("/").toString(), new OkHttpClient());
        ResponseEntity<?> response = new OsrmTestController(service).route(
                new OsrmTestController.RouteRequest(List.of("Node_A", "Node_B"),
                        Map.of("Node_A", List.of(200.0, 36.0), "Node_B", List.of(129.0, 36.0))));
        assertEquals(400, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    /**
     * OSRM은 좌표를 가장 가까운 도로로 스냅한 뒤 <b>그 도로 사이</b>를 계산한다. 스냅이
     * 수백 km 튀어도 {@code code:"Ok"}에 {@code legs} 개수까지 맞는 응답이 오므로, 형식만
     * 보면 정상이다. 실측으로 확인한 값 — 장량동 노드는 5m·62m, 근해 좌표는 91.6km·5.7km
     * 밀려 스냅되면서 <b>372분</b>이라는 그럴듯한 숫자를 냈다. 아래 두 테스트는 그 응답이
     * 이동시간으로 나가지 않는 것을, 뒤의 두 테스트는 정상 스냅을 막지 않는 것을 고정한다.
     */
    @Test
    void rejectsResultWhenCoordinateSnappedTooFarFromRoad() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"code":"Ok",
                 "waypoints":[{"distance":8.0},{"distance":91597.4}],
                 "routes":[{"distance":120000.0,"duration":22345.0,"legs":[
                  {"distance":120000.0,"duration":22345.0}
                ]}]}
                """));
        ResponseEntity<?> response = routeTwoNodes(server.url("/").toString());

        assertEquals(502, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("OSRM_UNAVAILABLE", body.get("error"));
        // 어느 노드가, 얼마나 밀렸는지 없으면 좌표를 고칠 수 없다.
        assertTrue(String.valueOf(body.get("message")).contains("Node_B"),
                "message=" + body.get("message"));
        assertTrue(String.valueOf(body.get("message")).contains("91597"),
                "message=" + body.get("message"));
    }

    /** 0m·0분이라는 명백한 쓰레기도 같은 검사로 걸린다 — 0분 전용 검사를 두지 않는 이유. */
    @Test
    void rejectsZeroDistanceResultProducedByFarSnapping() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"code":"Ok",
                 "waypoints":[{"distance":819573.2},{"distance":684678.9}],
                 "routes":[{"distance":0.0,"duration":0.0,"legs":[
                  {"distance":0.0,"duration":0.0}
                ]}]}
                """));
        ResponseEntity<?> response = routeTwoNodes(server.url("/").toString());

        assertEquals(502, response.getStatusCode().value());
    }

    /** 대조군 — 스냅 거리를 확인할 수 없는 응답은 통과시키지 않는다(검사를 우회하는 구멍). */
    @Test
    void rejectsResponseWithoutWaypointsBecauseSnapDistanceIsUnknown() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"code":"Ok","routes":[{"distance":3500.0,"duration":420.0,"legs":[
                  {"distance":3500.0,"duration":420.0}
                ]}]}
                """));
        ResponseEntity<?> response = routeTwoNodes(server.url("/").toString());

        assertEquals(502, response.getStatusCode().value());
    }

    /** 대조군 — 임계값과 정확히 같은 스냅은 정상이며, 값이 손상되지 않는다. */
    @Test
    void acceptsSnapDistanceExactlyAtThreshold() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"code":"Ok",
                 "waypoints":[{"distance":300.0},{"distance":300.0}],
                 "routes":[{"distance":3500.0,"duration":420.0,"legs":[
                  {"distance":3500.0,"duration":420.0}
                ]}]}
                """));
        ResponseEntity<?> response = routeTwoNodes(server.url("/").toString());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(7.0, body.get("totalDurationMinutes"));
        assertEquals(3500.0, body.get("totalDistanceMeters"));
    }

    @Test
    void rejectsOsrmResponseWithMissingOrNegativeMetrics() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"code":"Ok","waypoints":[{"distance":5.0},{"distance":8.0}],
                 "routes":[{"distance":3500.0,"legs":[{"distance":-1.0,"duration":20.0}]}]}
                """));
        ResponseEntity<?> response = routeTwoNodes(server.url("/").toString());
        assertEquals(502, response.getStatusCode().value(),
                "누락된 총 duration과 음수 구간 거리를 0으로 바꿔 정상 응답하면 안 된다");
    }

    @Test
    void rejectsInvalidSnapThresholdConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new OsrmRouteService(true, "https://example.com", new OkHttpClient(), Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new OsrmRouteService(true, "https://example.com", new OkHttpClient(), 0));
    }

    private ResponseEntity<?> routeTwoNodes(String baseUrl) {
        OsrmRouteService service = new OsrmRouteService(true, baseUrl, new OkHttpClient());
        return new OsrmTestController(service).route(new OsrmTestController.RouteRequest(
                List.of("Node_A", "Node_B"), Map.of(
                "Node_A", List.of(129.37, 36.07),
                "Node_B", List.of(129.36, 36.06))));
    }
}
