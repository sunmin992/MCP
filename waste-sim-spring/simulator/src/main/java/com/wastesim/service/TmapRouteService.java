package com.wastesim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SK TMAP 경로 조회 어댑터 — <b>실험용</b>이며 DEVS 엔진의 이동시간 계산에 자동 적용되지 않는다.
 *
 * <h2>OSRM과 무엇이 다른가</h2>
 * <b>TMAP의 {@code totalTime}은 이미 교통 상황을 반영한 값이다.</b> OSRM의 자유주행시간과
 * 성질이 다르므로 <b>같은 자리에 넣으면 안 된다</b> — 기존 {@code OSRM_HYBRID}는 자유주행시간에
 * 시간대 혼잡 가중치를 곱하는데, TMAP 값에 그걸 또 곱하면 교통을 두 번 세게 된다.
 * 그래서 이 서비스의 응답은 값이 교통 반영분인지를 {@code trafficIncluded}로 명시한다.
 *
 * <h2>두 가지 한계</h2>
 * <ul>
 *   <li><b>스냅 거리를 알 수 없다.</b> OSRM은 {@code waypoints[].distance}로 좌표가 도로에서
 *       얼마나 밀려 스냅됐는지 알려주고, 그걸로 300m 방어를 걸었다. TMAP 응답에는 대응하는
 *       값이 없어서 <b>같은 방어를 걸 수 없다</b> — 엉뚱한 좌표를 넣어도 그럴듯한 값이 온다.</li>
 *   <li><b>승용차 기준이다.</b> {@code /tmap/routes}는 일반 자동차 경로이므로 화물차 제한
 *       도로를 반영하지 않는다. 5톤 차량 모델에 쓰려면 화물차 경로 API를 따로 검토해야 한다.</li>
 * </ul>
 *
 * <h2>키</h2>
 * App Key는 환경변수({@code TMAP_APP_KEY})로만 주입한다. 이 클래스는 키를 로그에도, 응답에도,
 * 예외 메시지에도 싣지 않는다 — 한 번 새면 회수할 수 없는 값이다.
 */
@Service
public class TmapRouteService {

    /** 요청 길이 제한을 피하려 POST를 쓴다(TMAP 운영 안내 권고). */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String appKey;
    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public TmapRouteService(
            @Value("${tmap.enabled:false}") boolean enabled,
            @Value("${tmap.app-key:}") String appKey,
            @Value("${tmap.base-url:https://apis.openapi.sk.com}") String baseUrl,
            @Value("${tmap.connect-timeout-seconds:3}") int connectTimeoutSeconds,
            @Value("${tmap.read-timeout-seconds:8}") int readTimeoutSeconds) {
        this(enabled, appKey, baseUrl, new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build());
    }

    /** 테스트 또는 별도 런타임 구성에서 고정 HTTP 클라이언트를 주입하는 생성자. */
    public TmapRouteService(boolean enabled, String appKey, String baseUrl, OkHttpClient http) {
        if (HttpUrl.parse(baseUrl) == null) {
            throw new IllegalArgumentException("유효하지 않은 TMAP base URL입니다.");
        }
        this.enabled = enabled;
        this.appKey = appKey == null ? "" : appKey.trim();
        this.baseUrl = baseUrl;
        this.http = http;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 키가 주입돼 있는가. <b>키 값 자체는 어디에도 노출하지 않는다.</b> */
    public boolean hasAppKey() {
        return !appKey.isEmpty();
    }

    /**
     * 두 지점 사이의 경로를 한 번 조회한다.
     *
     * @throws IllegalStateException    기능이 꺼져 있거나 App Key가 없을 때
     * @throws IllegalArgumentException 좌표가 유효하지 않을 때
     * @throws IOException              HTTP 실패, 인증 거부, 응답 형식 이상
     */
    public RouteResult route(Point start, Point end) throws IOException {
        if (!enabled) throw new IllegalStateException("TMAP 실험 기능이 비활성화되어 있습니다.");
        if (!hasAppKey()) {
            throw new IllegalStateException(
                    "TMAP App Key가 없습니다. TMAP_APP_KEY 환경변수로 주입하세요(파일에 적지 않습니다).");
        }
        validate(start, "출발");
        validate(end, "도착");

        HttpUrl url = HttpUrl.parse(baseUrl).newBuilder()
                .addPathSegments("tmap/routes")
                .addQueryParameter("version", "1")
                .addQueryParameter("format", "json")
                .build();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startX", String.valueOf(start.longitude()));
        payload.put("startY", String.valueOf(start.latitude()));
        payload.put("endX", String.valueOf(end.longitude()));
        payload.put("endY", String.valueOf(end.latitude()));
        payload.put("reqCoordType", "WGS84GEO");
        payload.put("resCoordType", "WGS84GEO");
        payload.put("startName", start.name());
        payload.put("endName", end.name());
        payload.put("searchOption", "0");   // 교통최적 + 추천
        payload.put("trafficInfo", "Y");

        Request request = new Request.Builder()
                .url(url)
                .addHeader("appKey", appKey)          // 키는 헤더로만 보낸다 — URL에 실으면 로그에 남는다
                .addHeader("Accept", "application/json")
                .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403) {
                throw new IOException("TMAP 인증이 거부되었습니다(HTTP " + response.code()
                        + "). App Key가 유효한지, 해당 API 사용이 승인됐는지 확인하세요.");
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("TMAP HTTP 오류: " + response.code());
            }
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode props = root.path("features").path(0).path("properties");
            if (props.isMissingNode()) {
                throw new IOException("TMAP 응답에 경로 속성(features[0].properties)이 없습니다.");
            }
            double distance = requirePositiveFinite(props, "totalDistance");
            double seconds = requirePositiveFinite(props, "totalTime");
            return new RouteResult(distance, seconds);
        }
    }

    private static void validate(Point p, String label) {
        if (p == null) throw new IllegalArgumentException(label + " 지점이 없습니다.");
        if (!Double.isFinite(p.longitude()) || !Double.isFinite(p.latitude())
                || p.longitude() < -180 || p.longitude() > 180
                || p.latitude() < -90 || p.latitude() > 90) {
            throw new IllegalArgumentException(label + " 지점의 경도·위도 범위가 올바르지 않습니다.");
        }
    }

    /**
     * 값이 없거나 0 이하면 거부한다. 0초로 응답이 오는 경우를 통과시키면 "두 지점이 같다"와
     * "TMAP이 경로를 못 찾았다"가 구별되지 않는다.
     */
    private static double requirePositiveFinite(JsonNode props, String field) throws IOException {
        JsonNode v = props.get(field);
        if (v == null || !v.isNumber()) {
            throw new IOException("TMAP 응답의 " + field + "가 숫자가 아닙니다.");
        }
        double parsed = v.doubleValue();
        if (!Double.isFinite(parsed) || parsed <= 0) {
            throw new IOException("TMAP 응답의 " + field + "가 0보다 큰 유한한 값이 아닙니다: " + v);
        }
        return parsed;
    }

    /** @param name TMAP에 보내는 지점 이름(응답 대조용). 좌표 판정에는 쓰이지 않는다. */
    public record Point(String name, double longitude, double latitude) {}

    /**
     * @param distanceMeters 총 거리(m)
     * @param durationSeconds 총 주행시간(초). <b>교통 상황이 이미 반영된 값이다</b> —
     *                        여기에 시간대 혼잡 가중치를 다시 곱하면 교통을 두 번 센다.
     */
    public record RouteResult(double distanceMeters, double durationSeconds) {}
}
