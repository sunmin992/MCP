package com.wastesim.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code traffic/jangryang-nodes.json}이 고정한 Node_A~D 좌표를 잠근다.
 *
 * <p>이 파일은 아직 어떤 프로덕션 코드도 읽지 않는다 — 그래서 잘못되거나 낡아도 스스로
 * 드러나지 않는다. 이 테스트가 그 자리를 대신한다. 지키는 것은 세 가지다.
 *
 * <ol>
 *   <li><b>좌표와 근거가 함께 움직인다</b> — 좌표를 고치면 해시가 깨지고, 해시를 갱신하려면
 *       스냅 거리를 다시 재게 된다. 근거 없이 좌표만 바뀌는 경로를 막는다.</li>
 *   <li><b>귀속 링크가 실측 CSV와 일치한다</b> — 링크 목록을 손으로 적어 둔 것이 아니라
 *       {@code response_filtered.csv}에 전처리 스크립트의 키워드 규칙을 다시 적용해 대조한다.
 *       혼잡 가중치와 좌표가 같은 링크 집합에서 나왔다는 것이 이 계층의 유일한 정당화 근거다.</li>
 *   <li><b>기록된 스냅 거리가 임계값 안이다</b> — 실제 재측정은 네트워크가 필요하므로
 *       ({@code OSRM /nearest}) 여기서는 기록값이 {@code osrm.max-snap-meters}와 모순되지
 *       않는지만 본다.</li>
 * </ol>
 */
class JangryangNodeCoordinatesTest {

    private static final List<String> NODES = List.of("Node_A", "Node_B", "Node_C", "Node_D");

    /** 장량동과 그 인접 구역을 넉넉히 감싸는 경계 — 좌표가 다른 도시로 튀는 것만 잡는다. */
    private static final double MIN_LON = 129.34, MAX_LON = 129.42;
    private static final double MIN_LAT = 36.04, MAX_LAT = 36.11;

    private static JsonNode doc;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = JangryangNodeCoordinatesTest.class
                .getResourceAsStream("/traffic/jangryang-nodes.json")) {
            assertNotNull(in, "traffic/jangryang-nodes.json이 클래스패스에 없습니다.");
            doc = new ObjectMapper().readTree(in);
        }
    }

    @Test
    void definesExactlyTheFourEngineNodes() {
        JsonNode nodes = doc.path("nodes");
        assertEquals(NODES.size(), nodes.size(), "노드 개수");
        for (String n : NODES) {
            assertTrue(nodes.has(n), n + "이 없습니다.");
        }
        // SimulationEngine.nodeId(0..3)과 같은 라벨이어야 한다.
        for (int i = 0; i < NODES.size(); i++) {
            assertEquals(NODES.get(i), com.wastesim.simulation.SimulationEngine.nodeId(i));
        }
    }

    @Test
    void everyCoordinateIsFiniteAndInsideJangnyangArea() {
        for (String n : NODES) {
            JsonNode node = doc.path("nodes").path(n);
            double lon = node.path("longitude").asDouble(Double.NaN);
            double lat = node.path("latitude").asDouble(Double.NaN);
            assertTrue(Double.isFinite(lon) && Double.isFinite(lat), n + " 좌표가 수치가 아닙니다.");
            assertTrue(lon >= MIN_LON && lon <= MAX_LON, n + " 경도가 장량동 인근을 벗어남: " + lon);
            assertTrue(lat >= MIN_LAT && lat <= MAX_LAT, n + " 위도가 장량동 인근을 벗어남: " + lat);
        }
    }

    /**
     * 좌표를 고치면 반드시 깨진다. 해시를 다시 계산해 넣는 행위가 "스냅 거리와 측정
     * 행렬을 다시 재라"는 신호다 — 근거 없이 좌표만 바뀌는 것을 막는 유일한 장치다.
     */
    @Test
    void coordinateHashMatchesTheRecordedCoordinates() {
        StringBuilder sb = new StringBuilder();
        sb.append(doc.path("id").asText()).append('␟').append(doc.path("version").asInt());
        for (String n : NODES) {
            JsonNode node = doc.path("nodes").path(n);
            sb.append('␞').append(n).append('␟')
              .append(node.path("longitude").asText()).append('␟')
              .append(node.path("latitude").asText()).append('␟')
              .append(node.path("landmark").asText());
        }
        assertEquals(doc.path("coordinateHash").asText(), sha256(sb.toString()),
                "좌표가 바뀌었는데 coordinateHash가 갱신되지 않았습니다. "
                        + "좌표를 고쳤다면 스냅 거리와 measuredOsrm도 다시 재고 해시를 갱신하세요.");
    }

    /** 좌표를 정당화하는 근거가 비어 있으면, 다음 사람은 그 좌표를 신뢰할 수 없다. */
    @Test
    void everyNodeCarriesItsProvenance() {
        for (String n : NODES) {
            JsonNode node = doc.path("nodes").path(n);
            for (String field : List.of("landmark", "endpointRole", "geocodeSource", "adminDivision")) {
                assertFalse(node.path(field).asText("").isBlank(), n + "." + field + "가 비었습니다.");
            }
            assertTrue(node.path("links").isArray() && node.path("links").size() > 0,
                    n + ".links가 비었습니다.");
            assertEquals(node.path("links").size(), node.path("linkCount").asInt(),
                    n + ".linkCount가 links 개수와 다릅니다.");
            // 확정된 좌표는 장량동 안이어야 한다 — 이 프로젝트가 장량동만 다루기 때문이다.
            assertTrue(node.path("adminDivision").asText().contains("장량동"),
                    n + "의 행정동이 장량동이 아닙니다: " + node.path("adminDivision").asText());
        }
    }

    /**
     * 링크 귀속을 CSV에서 다시 유도해 대조한다. 좌표는 "그 노드에 귀속된 링크의 공통 종점"으로
     * 정의됐으므로, 귀속이 달라지면 좌표의 근거 자체가 무너진다.
     */
    @Test
    void linkAssignmentMatchesTheKeywordRuleAppliedToTheMeasuredCsv() throws IOException {
        File csv = new File("response_filtered.csv");
        assertTrue(csv.isFile(), "response_filtered.csv가 프로젝트 루트에 없습니다: " + csv.getAbsolutePath());

        Map<String, List<String>> derived = deriveFromCsv(csv);
        for (String n : NODES) {
            List<String> expected = derived.getOrDefault(n, List.of());
            List<String> recorded = new ArrayList<>();
            for (JsonNode l : doc.path("nodes").path(n).path("links")) {
                recorded.add(l.path("begin").asText() + "→" + l.path("end").asText());
            }
            assertEquals(expected, recorded, n + "의 귀속 링크가 CSV에서 유도한 것과 다릅니다.");
        }
        int total = derived.values().stream().mapToInt(List::size).sum();
        assertEquals(15, total, "CSV 15개 링크가 모두 귀속되어야 합니다(미매핑 0).");
    }

    /**
     * {@code scripts/preprocess_response_filtered.py}의 {@code NODE_KEYWORDS}·{@code classify()}와
     * 같은 규칙. 키워드 순서가 곧 우선순위이고 첫 일치가 이긴다 — 예를 들어 '장성초등학교→두산위브'는
     * 두 노드의 키워드에 모두 걸리지만 Node_A로 간다. 스크립트의 dict 순서를 그대로 옮겼다.
     */
    private static Map<String, List<String>> deriveFromCsv(File csv) throws IOException {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("Node_B", List.of("양덕"));
        keywords.put("Node_A", List.of("장성초등학교"));
        keywords.put("Node_C", List.of("장성초등사거리", "창포"));
        keywords.put("Node_D", List.of("두산위브", "포항온천"));

        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "CSV가 비었습니다.");
        String[] header = splitCsv(stripBom(lines.get(0)));
        int iBegin = indexOf(header, "begin_node_nm");
        int iEnd = indexOf(header, "end_node_nm");
        assertTrue(iBegin >= 0 && iEnd >= 0, "CSV에 begin_node_nm/end_node_nm 컬럼이 없습니다.");

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] cols = splitCsv(line);
            String begin = iBegin < cols.length ? cols[iBegin] : "";
            String end = iEnd < cols.length ? cols[iEnd] : "";
            String text = begin + " " + end;
            for (Map.Entry<String, List<String>> e : keywords.entrySet()) {
                if (e.getValue().stream().anyMatch(text::contains)) {
                    out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(begin + "→" + end);
                    break;
                }
            }
        }
        return out;
    }

    /** 기록된 스냅 거리는 임계값 안이어야 한다 — 넘는 좌표는 OsrmRouteService가 거부한다. */
    @Test
    void recordedSnapDistancesRespectTheGuardThreshold() {
        double threshold = doc.path("snapThresholdMeters").asDouble(Double.NaN);
        assertEquals(300.0, threshold, "application.properties의 osrm.max-snap-meters와 같아야 합니다.");
        for (String n : NODES) {
            double snap = doc.path("nodes").path(n).path("snapMeters").asDouble(Double.NaN);
            assertTrue(Double.isFinite(snap) && snap >= 0, n + ".snapMeters가 없거나 음수입니다.");
            assertTrue(snap <= threshold,
                    n + "의 기록된 스냅 거리 " + snap + "m가 임계값 " + threshold + "m를 넘습니다.");
        }
    }

    /** 측정 행렬은 12개 순서쌍이 모두 있어야 한다 — 빠진 쌍은 "재보정 안 됨"과 구별되지 않는다. */
    @Test
    void measuredMatrixCoversAllTwelveOrderedPairs() {
        JsonNode pairs = doc.path("measuredOsrm").path("pairs");
        assertEquals(12, pairs.size(), "순서쌍 개수");
        for (String from : NODES) {
            for (String to : NODES) {
                if (from.equals(to)) continue;
                JsonNode v = pairs.path(from + "->" + to);
                assertTrue(v.isArray() && v.size() == 2, from + "->" + to + " 측정값 형식");
                assertTrue(v.get(0).asDouble(-1) >= 0, from + "->" + to + " 거리");
                assertTrue(v.get(1).asDouble(-1) >= 0, from + "->" + to + " 시간");
            }
        }
    }

    /**
     * 확정된 좌표가 드러낸 사실을 명시적으로 붙잡아 둔다 — Node_A와 Node_C는 도로 거리
     * 60m 이내로 사실상 같은 장소다. 실측 CSV가 '장성초등학교'와 '장성초등사거리'를 별개
     * 키워드로 나눈 결과이며, 구간당 15분을 부과하는 기존 모델과 정면으로 어긋난다.
     * 이 단언이 깨지는 날은 그 문제가 해소된 날이므로, 그때 knownIssues도 함께 지운다.
     */
    @Test
    void documentsThatNodeAAndNodeCAreEffectivelyTheSamePlace() {
        double d = doc.path("measuredOsrm").path("pairs").path("Node_A->Node_C").get(0).asDouble();
        assertTrue(d < 100.0, "Node_A->Node_C 도로 거리가 " + d + "m입니다.");
        boolean documented = false;
        for (JsonNode issue : doc.path("knownIssues")) {
            if (issue.asText().contains("Node_A") && issue.asText().contains("Node_C")) documented = true;
        }
        assertTrue(documented, "Node_A/Node_C 근접 문제가 knownIssues에 적혀 있어야 합니다.");
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    /** 이 CSV는 인용부호를 쓰지 않는다 — 단순 분리로 충분하다. */
    private static String[] splitCsv(String line) {
        return line.split(",", -1);
    }
}
