package com.wastesim.controller;

import com.wastesim.service.TmapRouteService;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TMAP 어댑터를 <b>실제 TMAP을 부르지 않고</b> 검증한다 — 가짜 HTTP 서버로 응답을 물린다.
 *
 * <p>가장 중요한 두 가지는 실패 경로와 <b>값의 성질 표시</b>다. TMAP의 {@code totalTime}은
 * 교통이 이미 반영된 값이어서 OSRM 자유주행시간 자리에 넣으면 혼잡을 두 번 센다. 응답이
 * 그 사실을 말하지 않으면 다음 사람이 그 실수를 한다.
 */
class TmapTestControllerTest {

    private final MockWebServer server = new MockWebServer();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-01T06:30:00Z"), ZoneOffset.UTC);

    @AfterEach
    void close() throws IOException {
        server.close();
    }

    private TmapTestController controller(String appKey) {
        return new TmapTestController(
                new TmapRouteService(true, appKey, server.url("/").toString(), new OkHttpClient()), FIXED);
    }

    private static TmapTestController.RouteRequest nodeAtoB() {
        return new TmapTestController.RouteRequest(
                new TmapTestController.Waypoint("Node_A", 129.37185, 36.06962),
                new TmapTestController.Waypoint("Node_B", 129.39554, 36.07622));
    }

    private static MockResponse ok(String properties) {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"type\":\"FeatureCollection\",\"features\":[{\"properties\":{" + properties + "}}]}");
    }

    // ── 정상 경로 ──────────────────────────────────────────────────────────

    @Test
    void returnsDistanceAndTrafficInclusiveDuration() throws Exception {
        server.start();
        server.enqueue(ok("\"totalDistance\":2841,\"totalTime\":412"));

        ResponseEntity<?> response = controller("test-key").route(nodeAtoB());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("TMAP", body.get("source"));
        assertEquals(2841.0, body.get("totalDistanceMeters"));
        assertEquals(412.0, body.get("totalDurationSeconds"));
        assertEquals(6.87, body.get("totalDurationMinutes"));
        assertEquals("Node_A", body.get("from"));
        assertEquals("Node_B", body.get("to"));
    }

    /**
     * 이 두 필드가 응답의 존재 이유다 — 값이 교통 반영분이라는 표시와 그 기준 시각.
     * 없으면 나중에 OSRM 값과 나란히 놓을 때 무엇을 비교하는지 알 수 없다.
     */
    @Test
    void labelsTheValueAsTrafficInclusiveAndStampsTheTime() throws Exception {
        server.start();
        server.enqueue(ok("\"totalDistance\":2841,\"totalTime\":412"));

        @SuppressWarnings("unchecked") Map<String, Object> body =
                (Map<String, Object>) controller("test-key").route(nodeAtoB()).getBody();

        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("trafficIncluded"));
        assertEquals("2026-09-01T06:30:00Z", body.get("requestedAt"));
        assertTrue(String.valueOf(body.get("note")).contains("두 번"),
                "OSRM 자리에 넣으면 안 된다는 경고가 응답에 있어야 한다");
    }

    /** 키는 헤더로만 나가야 한다 — URL에 실으면 접근 로그와 프록시에 그대로 남는다. */
    @Test
    void sendsAppKeyInHeaderAndNeverInTheUrl() throws Exception {
        server.start();
        server.enqueue(ok("\"totalDistance\":2841,\"totalTime\":412"));

        controller("super-secret-key").route(nodeAtoB());

        RecordedRequest req = server.takeRequest();
        assertEquals("super-secret-key", req.getHeader("appKey"));
        assertFalse(req.getPath().contains("super-secret-key"), "경로=" + req.getPath());
        assertTrue(req.getPath().startsWith("/tmap/routes?version=1&format=json"), req.getPath());
        assertEquals("POST", req.getMethod(), "요청 길이 제한을 피하려 POST를 쓴다");
    }

    @Test
    void sendsWgs84CoordinatesAndTrafficFlag() throws Exception {
        server.start();
        server.enqueue(ok("\"totalDistance\":2841,\"totalTime\":412"));

        controller("test-key").route(nodeAtoB());

        String sent = server.takeRequest().getBody().readUtf8();
        assertTrue(sent.contains("\"startX\":\"129.37185\""), sent);
        assertTrue(sent.contains("\"endY\":\"36.07622\""), sent);
        assertTrue(sent.contains("\"reqCoordType\":\"WGS84GEO\""), sent);
        assertTrue(sent.contains("\"trafficInfo\":\"Y\""), sent);
    }

    // ── 실패 경로 ──────────────────────────────────────────────────────────

    @Test
    void missingAppKeyIsReportedBeforeAnyNetworkCall() throws Exception {
        server.start();

        ResponseEntity<?> response = controller("").route(nodeAtoB());

        assertEquals(503, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("TMAP_APP_KEY_MISSING", body.get("error"));
        assertEquals(0, server.getRequestCount(), "키가 없으면 부르지 않는다");
    }

    @Test
    void disabledExperimentDoesNotCallTmap() {
        TmapTestController c = new TmapTestController(
                new TmapRouteService(false, "k", "https://example.invalid", new OkHttpClient()), FIXED);
        assertEquals(503, c.route(nodeAtoB()).getStatusCode().value());
    }

    @Test
    void rejectsUnauthorizedWithAnActionableMessage() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"));

        ResponseEntity<?> response = controller("bad-key").route(nodeAtoB());

        assertEquals(502, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(String.valueOf(body.get("message")).contains("인증이 거부"), body.toString());
        assertFalse(body.toString().contains("bad-key"), "오류 메시지에 키가 새면 안 된다");
    }

    @Test
    void rejectsOtherHttpErrors() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

        assertEquals(502, controller("test-key").route(nodeAtoB()).getStatusCode().value());
    }

    @Test
    void rejectsResponseWithoutRouteProperties() throws Exception {
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"type\":\"FeatureCollection\",\"features\":[]}"));

        ResponseEntity<?> response = controller("test-key").route(nodeAtoB());

        assertEquals(502, response.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue(String.valueOf(body.get("message")).contains("경로 속성"), body.toString());
    }

    /** totalTime이 없거나 0이면 "두 지점이 같다"와 "경로를 못 찾았다"가 구별되지 않는다. */
    @Test
    void rejectsMissingOrZeroDuration() throws Exception {
        server.start();
        server.enqueue(ok("\"totalDistance\":2841"));
        assertEquals(502, controller("test-key").route(nodeAtoB()).getStatusCode().value());

        server.enqueue(ok("\"totalDistance\":2841,\"totalTime\":0"));
        ResponseEntity<?> zero = controller("test-key").route(nodeAtoB());
        assertEquals(502, zero.getStatusCode().value());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) zero.getBody();
        assertTrue(String.valueOf(body.get("message")).contains("totalTime"), body.toString());
    }

    @Test
    void rejectsNonNumericDistance() throws Exception {
        server.start();
        server.enqueue(ok("\"totalDistance\":\"멀다\",\"totalTime\":412"));

        assertEquals(502, controller("test-key").route(nodeAtoB()).getStatusCode().value());
    }

    @Test
    void rejectsInvalidCoordinatesBeforeNetworkCall() throws Exception {
        server.start();

        ResponseEntity<?> response = controller("test-key").route(new TmapTestController.RouteRequest(
                new TmapTestController.Waypoint("Node_A", 200.0, 36.0),
                new TmapTestController.Waypoint("Node_B", 129.39, 36.07)));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void rejectsMissingWaypoints() throws Exception {
        server.start();
        assertEquals(400, controller("test-key").route(null).getStatusCode().value());
        assertEquals(400, controller("test-key").route(new TmapTestController.RouteRequest(
                new TmapTestController.Waypoint("Node_A", null, 36.0),
                new TmapTestController.Waypoint("Node_B", 129.39, 36.07))).getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void rejectsInvalidBaseUrlAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new TmapRouteService(true, "k", "not a url", new OkHttpClient()));
    }
}
