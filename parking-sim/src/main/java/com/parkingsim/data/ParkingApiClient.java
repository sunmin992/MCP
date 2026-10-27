package com.parkingsim.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Parking_v1 (official technical guide 2026-10-01). Never expose request URLs or upstream bodies. */
@Component
public class ParkingApiClient {
    public static final String SOURCE = "https://apis.data.go.kr/B553881/Parking_v1";
    private final String key;
    private final ObjectMapper mapper;
    public ParkingApiClient(@Value("${parking.api.service-key:}") String key, ObjectMapper mapper) {
        this.key = key; this.mapper = mapper;
    }
    /**
     * 한 지역의 모든 쪽. 시설 목록은 민영 · 부설까지 들어 있어 시군구 하나가 수천 건이다(포항 북구 7,693건 = 77쪽,
     * 2026-10-27 실측) — 쪽 수는 totalCount 가 정하고, 이 한도는 그것이 터무니없을 때만 막는다.
     */
    static final int MAX_PAGES = 200;

    public List<JsonNode> fetch(String dataset, String sigunguCd) {
        return fetch(dataset, sigunguCd, rows -> false);
    }

    /** {@code enough} 가 참이 되면 남은 쪽을 받지 않는다 — 찾던 관리번호를 다 모았을 때처럼. */
    public List<JsonNode> fetch(String dataset, String sigunguCd, java.util.function.Predicate<List<JsonNode>> enough) {
        if (!Set.of("PrkSttusInfo", "PrkRealtimeInfo").contains(dataset))
            throw new IllegalArgumentException("지원하지 않는 API입니다.");
        if (sigunguCd == null || !sigunguCd.matches("[0-9]{5}"))
            throw new IllegalArgumentException("sigunguCd 는 5자리 시군구 코드여야 합니다.");
        if (key.isBlank()) throw new IllegalArgumentException("서버 환경변수 PARKING_API_SERVICE_KEY 를 설정하고 재시작하십시오.");
        List<JsonNode> all = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (System.nanoTime() > deadline) throw new IllegalArgumentException("API 페이지 조회 시간 한도를 넘었습니다. 다시 조회하십시오.");
            try {
                URI uri = URI.create(SOURCE + "/" + dataset + "_v1?serviceKey=" + encodedKey(key)
                        + "&format=2&numOfRows=100&pageNo=" + page + "&sigunguCd=" + sigunguCd);
                JsonNode root = parse(request(uri), dataset, mapper);
                JsonNode rows = root.path(dataset);
                int total = integer(root, "totalCount");
                if (rows.size() == 0 && all.size() < total) throw new IllegalArgumentException("API 페이지가 누락되었습니다.");
                for (JsonNode row : rows) {
                    String id = row.path("prk_center_id").asText("");
                    if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("API 관리번호가 없거나 중복됩니다. 다시 조회하십시오.");
                    all.add(row);
                }
                if (all.size() >= total || enough.test(all)) return List.copyOf(all);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("주차 API 조회가 중단되었습니다.");
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("주차 API 연결 또는 응답 읽기에 실패했습니다. 인증키·네트워크를 확인하십시오.");
            }
        }
        throw new IllegalArgumentException("지역 조회가 " + MAX_PAGES + "페이지를 넘었습니다. 부분 결과는 사용하지 않습니다.");
    }
    protected String request(URI uri) throws java.io.IOException, InterruptedException {
        // No socket resources are created until an authenticated API request is actually needed.
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalArgumentException("주차 API HTTP 오류: " + response.statusCode());
            return response.body();
        }
    }
    static String encodedKey(String key) {
        // Accept both portal key forms; a raw '+' must not become a space.
        String raw = key.contains("%") ? URLDecoder.decode(key, StandardCharsets.UTF_8) : key;
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
    static JsonNode parse(String body, String dataset, ObjectMapper mapper) {
        try {
            if (body.stripLeading().startsWith("<"))
                throw new IllegalArgumentException("주차 API가 XML 오류를 반환했습니다" + reasonCode(body)
                        + ". 활용승인·인증키·호출 한도를 확인하십시오.");
            JsonNode root = mapper.readTree(body);
            if (root == null || !Set.of("0", "00").contains(root.path("resultCode").asText()))
                throw new IllegalArgumentException("주차 API가 성공 결과를 반환하지 않았습니다. 활용승인·호출 한도를 확인하십시오.");
            if (!root.path(dataset).isArray() || integer(root, "totalCount") < 0)
                throw new IllegalArgumentException("주차 API 응답 형식이 명세와 다릅니다.");
            return root;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("주차 API JSON 응답을 해석할 수 없습니다.");
        }
    }
    /**
     * 포털 XML 오류의 사유 코드만 꺼낸다(SERVICE_KEY_IS_NOT_REGISTERED_ERROR 등). 본문은 내보내지 않는다 — 사유 코드는
     * 대문자 · 숫자 · 밑줄만 받으므로 키나 URL 이 섞여 나갈 수 없다. 코드가 없으면 빈 문자열이다.
     */
    static String reasonCode(String xml) {
        var m = java.util.regex.Pattern
                .compile("<(returnAuthMsg|errMsg|returnReasonCode|resultCode|resultMsg)>\\s*([A-Z0-9_]{2,60})\\s*</\\1>").matcher(xml);
        List<String> codes = new ArrayList<>();
        while (m.find()) if (!codes.contains(m.group(2))) codes.add(m.group(2));
        return codes.isEmpty() ? "" : " (" + String.join(" · ", codes) + ")";
    }

    public static int integer(JsonNode node, String field) {
        String text = node.path(field).asText("").trim();
        if (!text.matches("[0-9]{1,9}")) throw new IllegalArgumentException("API 숫자 필드가 없거나 잘못됐습니다: " + field);
        return Integer.parseInt(text);
    }
}
