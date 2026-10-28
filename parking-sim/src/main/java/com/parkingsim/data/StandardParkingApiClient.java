package com.parkingsim.data;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 전국주차장정보표준데이터. Independent contract and authorization from Parking_v1. */
@Component
public class StandardParkingApiClient {
    public static final String SOURCE = "https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api";
    private final String key;
    private final ObjectMapper mapper;
    public StandardParkingApiClient(@Value("${parking.standard-api.service-key:}") String key, ObjectMapper mapper) {
        this.key = key; this.mapper = mapper;
    }
    public List<JsonNode> fetch(String institutionName) {
        if (institutionName == null || institutionName.isBlank() || institutionName.length() > 100)
            throw new IllegalArgumentException("institutionName에 관리기관명을 1~100자로 지정하십시오.");
        if (key.isBlank()) throw new IllegalArgumentException("PARKING_STANDARD_API_SERVICE_KEY를 설정하십시오. 이 API의 활용승인이 필요합니다.");
        var all = new ArrayList<JsonNode>();
        var pages = new HashSet<String>();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        for (int page = 1; page <= 50; page++) {
            if (System.nanoTime() > deadline) throw new IllegalArgumentException("표준데이터 조회 시간이 초과되었습니다. 다시 조회하십시오.");
            try {
                var uri = URI.create(SOURCE + "?serviceKey=" + ParkingApiClient.encodedKey(key)
                        + "&type=json&numOfRows=100&pageNo=" + page + "&institutionNm=" + encode(institutionName)
                        + "&prkplceSe=" + encode("공영") + "&prkplceType=" + encode("노외"));
                JsonNode body = parse(request(uri), mapper);
                if (body == null) {
                    if (!all.isEmpty()) throw new IllegalArgumentException("표준데이터 중간 페이지가 누락됐습니다.");
                    return List.of();
                }
                int total = ParkingApiClient.integer(body, "totalCount");
                JsonNode items = body.path("items");
                if (items.isObject() && items.has("item")) {
                    items = items.path("item");
                    if (items.isObject()) items = mapper.createArrayNode().add(items);
                }
                if (!items.isArray()) {
                    if (total == 0 && (items.isMissingNode() || items.isNull() || items.asText().isBlank())) return List.of();
                    throw new IllegalArgumentException("표준데이터 items가 목록이 아닙니다.");
                }
                if (items.isEmpty() && all.size() < total) throw new IllegalArgumentException("표준데이터 페이지가 누락됐습니다.");
                if (!items.isEmpty() && !pages.add(items.toString())) throw new IllegalArgumentException("표준데이터 중복 페이지가 반환됐습니다.");
                for (JsonNode row : items) {
                    String id = row.path("prkplceNo").asText("");
                    if (id.isBlank()) throw new IllegalArgumentException("표준 주차장 관리번호가 없습니다.");
                    all.add(row.deepCopy());
                }
                if (all.size() >= total) return List.copyOf(all);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("표준데이터 조회가 중단됐습니다.");
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("표준데이터 연결에 실패했습니다. 인증키·네트워크를 확인하십시오.");
            }
        }
        throw new IllegalArgumentException("표준데이터가 50페이지를 넘었습니다. 부분 결과는 사용하지 않습니다.");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    protected String request(URI uri) throws java.io.IOException, InterruptedException {
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalArgumentException("표준데이터 HTTP 오류: " + response.statusCode());
            return response.body();
        }
    }
    static JsonNode parse(String text, ObjectMapper mapper) {
        try {
            if (text.stripLeading().startsWith("<")) {
                var match = java.util.regex.Pattern.compile("<(?:returnReasonCode|resultCode)>\\s*([0-9]{1,3})\\s*</(?:returnReasonCode|resultCode)>").matcher(text);
                throw new IllegalArgumentException(match.find() ? apiError(match.group(1))
                        : "표준데이터 XML 응답 형식 오류: 숫자 오류 코드가 없습니다. 인증 문제로 단정할 수 없습니다.");
            }
            JsonNode root = mapper.readTree(text);
            if (root == null) throw new IllegalArgumentException("표준데이터 응답이 비어 있습니다.");
            // Live standard API returns {header, body}; also accept the documented legacy wrapper.
            var response = root.has("header") ? root : root.path("response");
            String code = response.path("header").path("resultCode").asText().trim();
            if (code.isBlank()) throw new IllegalArgumentException("표준데이터 응답 형식 오류: header.resultCode가 없습니다(최상위 및 response 내부 확인). 인증 문제로 단정할 수 없습니다.");
            if (code.equals("03")) return null; // Official NODATA_ERROR
            if (!code.equals("00")) throw new IllegalArgumentException(apiError(code));
            if (!response.path("body").isObject()) throw new IllegalArgumentException("표준데이터 body가 없습니다.");
            return response.path("body");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("표준데이터 JSON 형식이 잘못됐습니다.");
        }
    }
    /** Only bounded numeric codes and our own explanations are exposed, never upstream text or URLs. */
    static String apiError(String code) {
        if (!code.matches("[0-9]{1,3}")) return "표준데이터 API 오류: 숫자가 아닌 결과 코드입니다. 응답 계약을 확인해야 합니다.";
        String reason = switch (code) {
            case "10" -> "요청 파라미터 오류";
            case "11" -> "필수 요청 파라미터 누락";
            case "12" -> "서비스가 없거나 폐기됨";
            case "20" -> "서비스 접근 거부 — 전국주차장정보표준데이터 활용승인 확인";
            case "21" -> "인증키 일시 사용 중지";
            case "22" -> "호출 한도 초과";
            case "30" -> "등록되지 않은 인증키";
            case "31" -> "인증키 사용 기한 만료";
            case "32" -> "등록되지 않은 IP";
            case "01", "02", "04", "05", "99" -> "제공기관 또는 게이트웨이 처리 오류";
            default -> "명세의 오류 코드와 대조가 필요합니다";
        };
        return "표준데이터 API 오류 [resultCode=" + code + "]: " + reason;
    }
}
