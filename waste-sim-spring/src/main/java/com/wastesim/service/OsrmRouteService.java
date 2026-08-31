package com.wastesim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 실험용 OSRM 경로 조회 어댑터. 기존 DEVS 엔진의 이동시간 모델을 바꾸지 않고,
 * 실제 도로 기반 값과 나란히 비교할 수 있게 별도 API에서만 사용한다.
 */
@Service
public class OsrmRouteService {

    /** 좌표가 도로에서 이만큼 넘게 떨어져 스냅되면 결과를 신뢰하지 않는다. */
    static final double DEFAULT_MAX_SNAP_METERS = 300.0;

    private final boolean enabled;
    private final String baseUrl;
    private final OkHttpClient http;
    private final double maxSnapMeters;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public OsrmRouteService(
            @Value("${osrm.enabled:false}") boolean enabled,
            @Value("${osrm.base-url:https://router.project-osrm.org}") String baseUrl,
            @Value("${osrm.connect-timeout-seconds:3}") int connectTimeoutSeconds,
            @Value("${osrm.read-timeout-seconds:8}") int readTimeoutSeconds,
            @Value("${osrm.max-snap-meters:300}") double maxSnapMeters) {
        this(enabled, baseUrl, new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build(), maxSnapMeters);
    }

    /** 테스트 또는 별도 런타임 구성에서 고정 HTTP 클라이언트를 주입하는 생성자. */
    public OsrmRouteService(boolean enabled, String baseUrl, OkHttpClient http) {
        this(enabled, baseUrl, http, DEFAULT_MAX_SNAP_METERS);
    }

    public OsrmRouteService(boolean enabled, String baseUrl, OkHttpClient http, double maxSnapMeters) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.http = http;
        this.maxSnapMeters = maxSnapMeters;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RouteResult route(List<Waypoint> waypoints) throws IOException {
        if (!enabled) throw new IllegalStateException("OSRM 실험 기능이 비활성화되어 있습니다.");
        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("경로 조회에는 좌표가 2개 이상 필요합니다.");
        }

        String coordinates = waypoints.stream()
                .map(p -> p.longitude() + "," + p.latitude())
                .reduce((a, b) -> a + ";" + b)
                .orElseThrow();
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null || !("http".equals(base.scheme()) || "https".equals(base.scheme()))) {
            throw new IllegalStateException("유효하지 않은 OSRM base URL입니다.");
        }
        HttpUrl url = base.newBuilder()
                .addPathSegments("route/v1/driving/" + coordinates)
                .addQueryParameter("overview", "false")
                .addQueryParameter("steps", "false")
                .build();

        try (Response response = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("OSRM HTTP 오류: " + response.code());
            }
            JsonNode root = mapper.readTree(response.body().string());
            if (!"Ok".equals(root.path("code").asText())) {
                throw new IOException("OSRM 경로 계산 실패: "
                        + root.path("code").asText("unknown") + " " + root.path("message").asText(""));
            }
            JsonNode route = root.path("routes").path(0);
            if (route.isMissingNode()) throw new IOException("OSRM 응답에 경로가 없습니다.");

            verifySnapping(root.path("waypoints"), waypoints);

            JsonNode legsNode = route.path("legs");
            if (!legsNode.isArray() || legsNode.size() != waypoints.size() - 1) {
                throw new IOException("OSRM 구간 수가 요청 경로와 일치하지 않습니다.");
            }
            List<Leg> legs = new ArrayList<>();
            for (int i = 0; i < legsNode.size(); i++) {
                JsonNode leg = legsNode.get(i);
                legs.add(new Leg(waypoints.get(i).id(), waypoints.get(i + 1).id(),
                        leg.path("distance").asDouble(), leg.path("duration").asDouble()));
            }
            return new RouteResult(route.path("distance").asDouble(),
                    route.path("duration").asDouble(), List.copyOf(legs));
        }
    }

    /**
     * OSRM이 각 좌표를 <b>얼마나 멀리 떨어진 도로로 스냅했는지</b> 확인한다.
     *
     * <p>OSRM은 주어진 좌표를 가장 가까운 도로에 붙인 뒤 그 도로 사이를 계산하고, 스냅이
     * 수백 km 튀어도 {@code code:"Ok"}를 돌려준다. 그래서 형식만 보면 정상 응답이고
     * {@code legs} 개수도 맞는다 — 여기서 막지 않으면 요청한 지점과 아무 상관 없는 값이
     * 정당한 이동시간으로 나간다. 실측 예:
     *
     * <ul>
     *   <li>장량동 노드 — 스냅 5m·62m (정상)</li>
     *   <li>바다 위 두 점 — 둘 다 같은 지점으로 스냅(819km·685km 밀림) → 0m/0분, code:Ok</li>
     *   <li>근해 두 점 — 각각 91.6km·5.7km 밀려 스냅 → <b>372분</b>, code:Ok</li>
     * </ul>
     *
     * <p>세 번째가 이 검사의 존재 이유다. <b>0분만 막는 검사로는 잡히지 않는다</b> — 그럴듯한
     * 숫자가 나오기 때문이다. 반대로 스냅 거리는 정상(수십 m)과 실패(수십 km)가 세 자릿수
     * 차이로 갈려서 임계값 하나로 가른다.
     *
     * <p>같은 이유로 "구간 시간이 0이면 거부"는 넣지 않았다. 같은 노드를 연속으로 방문하거나
     * 두 노드가 같은 도로 위에 붙어 있으면 0이 정당한 값이라, 그 검사는 정상 입력을 막는다.
     */
    private void verifySnapping(JsonNode waypointsNode, List<Waypoint> requested) throws IOException {
        if (!waypointsNode.isArray() || waypointsNode.size() != requested.size()) {
            // 스냅 거리를 확인할 수 없으면 통과시키지 않는다 — 검증하지 못한 값을 정상으로
            // 내보내는 것이 이 검사가 막으려는 상황 그 자체다.
            throw new IOException("OSRM 응답에 waypoints 정보가 없어 스냅 거리를 확인할 수 없습니다.");
        }
        for (int i = 0; i < waypointsNode.size(); i++) {
            double snap = waypointsNode.get(i).path("distance").asDouble(Double.NaN);
            if (!Double.isFinite(snap)) {
                throw new IOException(requested.get(i).id() + "의 스냅 거리를 읽을 수 없습니다.");
            }
            if (snap > maxSnapMeters) {
                throw new IOException(String.format(
                        "%s의 좌표가 도로에서 %.0fm 떨어져 스냅됐습니다(허용 %.0fm). "
                                + "좌표를 확인하세요 — 이 결과는 요청한 지점의 이동시간이 아닙니다.",
                        requested.get(i).id(), snap, maxSnapMeters));
            }
        }
    }

    public record Waypoint(String id, double longitude, double latitude) {}
    public record Leg(String from, String to, double distanceMeters, double durationSeconds) {}
    public record RouteResult(double distanceMeters, double durationSeconds, List<Leg> legs) {}
}
