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
 * {@code traffic/jangryang-traffic-zones.json}이 고정한 교통 구역 Node_A~D의 좌표를 잠근다.
 *
 * <p>이 파일이 담는 것은 <b>교통을 관측한 자리</b>이지 수거 지점이 아니다 — 그래서 학교·사거리·
 * 아파트가 여기 온다. 수거 지점은 {@code collection/}에 따로 있고 자신이 속한 구역을 가리킨다.
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
class JangryangTrafficZonesTest {

    private static final List<String> NODES = List.of("Node_A", "Node_B", "Node_C", "Node_D");

    /** 장량동과 그 인접 구역을 넉넉히 감싸는 경계 — 좌표가 다른 도시로 튀는 것만 잡는다. */
    private static final double MIN_LON = 129.34, MAX_LON = 129.42;
    private static final double MIN_LAT = 36.04, MAX_LAT = 36.11;

    private static JsonNode doc;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = JangryangTrafficZonesTest.class
                .getResourceAsStream("/traffic/jangryang-traffic-zones.json")) {
            assertNotNull(in, "traffic/jangryang-traffic-zones.json이 클래스패스에 없습니다.");
            doc = new ObjectMapper().readTree(in);
        }
    }

    @Test
    void definesExactlyTheFourTrafficZones() {
        JsonNode nodes = doc.path("zones");
        assertEquals(NODES.size(), nodes.size(), "교통 구역 개수");
        for (String n : NODES) {
            assertTrue(nodes.has(n), n + "이 없습니다.");
        }
        // 구역은 수거 지점과 같은 라벨 체계(Node_A~Z)를 쓰지만 별개의 이름공간이다.
        // 같은 이름이 서로 다른 것을 가리킬 수 있으므로, 여기서는 체계만 확인한다.
        for (String z : NODES) {
            assertTrue(com.wastesim.simulation.SimulationEngine.nodeIndex(z) >= 0,
                    "구역 id는 Node_A~Node_Z 체계여야 합니다: " + z);
        }
    }

    @Test
    void everyCoordinateIsFiniteAndInsideJangnyangArea() {
        for (String n : NODES) {
            JsonNode node = doc.path("zones").path(n);
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
            JsonNode node = doc.path("zones").path(n);
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
            JsonNode node = doc.path("zones").path(n);
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
            for (JsonNode l : doc.path("zones").path(n).path("links")) {
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
            double snap = doc.path("zones").path(n).path("snapMeters").asDouble(Double.NaN);
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

    /**
     * 확정 좌표가 드러낸 두 번째 사실 — {@code alleyNodeIds=[Node_C, Node_D]}는 5톤 진입
     * 불가를 뜻하는데, 그 두 노드의 실제 도로는 4차로 간선 교차로와 6차로 도로변이다.
     *
     * <p>값을 바꾸지 않은 이유는 V-T3 발동 조건과 5톤 차량의 실행 가능 경로가 함께
     * 바뀌기 때문이다. 대신 여기서 <b>어긋남이 기록돼 있다는 사실</b>을 고정한다 —
     * 기록이 사라지면 다음 사람은 이 값을 실측으로 읽는다.
     */
    @Test
    void documentsThatAlleyFlagsContradictTheConfirmedRoadClasses() {
        boolean documented = false;
        for (JsonNode issue : doc.path("knownIssues")) {
            String t = issue.asText();
            if (t.contains("alleyNodeIds") && t.contains("Node_C") && t.contains("Node_D")) documented = true;
        }
        assertTrue(documented, "alleyNodeIds 모순이 knownIssues에 적혀 있어야 합니다.");

        JsonNode roads = doc.path("roadClassAtCoordinate").path("nodes");
        assertEquals(NODES.size(), roads.size(), "네 노드의 도로 등급이 모두 기록돼야 합니다.");
        // 골목으로 표시된 두 노드가 실제로는 간선에 접한다는 것이 이 항목의 요점이다.
        assertTrue(roads.path("Node_C").toString().contains("primary"),
                "Node_C=" + roads.path("Node_C"));
        assertTrue(roads.path("Node_D").toString().contains("secondary"),
                "Node_D=" + roads.path("Node_D"));
    }

    /**
     * 수용한 한계가 기록에 남아 있는지 고정한다.
     *
     * <p>이 값들은 고칠 수 있지만 고치면 시뮬레이션 결과가 바뀌고, 어느 값이 옳은지 판단할
     * 근거(구역별 실측 통행속도)가 없어서 <b>한계로 수용</b>했다. 그런 결정은 기록이 사라지는
     * 순간 "아무도 몰랐던 것"으로 바뀐다 — 다음 사람은 가중치를 혼잡도로, `weekday`를 평일
     * 평균으로 읽게 된다. 그래서 기록의 존재 자체를 단언한다.
     *
     * <p>한계가 실제로 해소되면 해당 항목을 {@code [해소됨]}으로 바꾸고 이 단언도 함께 고친다
     * (alleyNodeIds가 그렇게 처리됐다).
     */
    @Test
    void acceptedDataLimitationsStayOnTheRecord() {
        List<String> issues = new ArrayList<>();
        for (JsonNode n : doc.path("knownIssues")) issues.add(n.asText());
        assertFalse(issues.isEmpty(), "knownIssues가 비었습니다.");

        // (2) Node_C가 편차 큰 링크를 단순 평균한다
        assertTrue(issues.stream().anyMatch(t -> t.contains("15.2배") && t.contains("Node_C")),
                "Node_C 링크 편차가 기록돼 있어야 합니다.");
        // (3) 창포 키워드가 장량동 밖을 끌어온다
        assertTrue(issues.stream().anyMatch(t -> t.contains("창포") && t.contains("장량동 밖")),
                "창포 키워드의 구역 이탈이 기록돼 있어야 합니다.");
        // (1) "통행량 지수이지 혼잡도가 아니다" — 2026-09-02 실측으로 확인되어 해소됐다.
        //     기록은 지우지 않는다: 보존된 jangryang-volume-weekday를 쓰는 사람에게는
        //     이 한계가 여전히 적용되고, 무엇이 왜 틀렸는지가 그 프로파일의 사용 조건이다.
        assertTrue(issues.stream().anyMatch(t -> t.startsWith("[해소됨") && t.contains("통행량 지수")),
                "통행량 지수 한계가 해소 기록으로 남아 있어야 합니다.");
        // (4) weekday에 근거가 없다 — 단어가 아니라 주장을 단언한다. 단어만 보면
        //     "std_dt가 채워진 추출본이 생기면"처럼 다른 문맥의 등장으로도 통과한다.
        assertTrue(issues.stream().anyMatch(t -> t.contains("weekday")
                        && t.contains("뒷받침하지 않는") && t.contains("단일 스냅샷")),
                "weekday가 데이터로 뒷받침되지 않고 단일 스냅샷이라는 점이 기록돼 있어야 합니다.");

        // 넷 다 "수용한 결정"으로 표시돼야 한다 — 미처리 TODO와 구별된다.
        long accepted = issues.stream().filter(t -> t.startsWith("[한계로 수용")).count();
        assertEquals(3, accepted,
                "수용한 한계 3건이 [한계로 수용] 표시를 달고 있어야 합니다. 실제: " + accepted);
        // 해소된 것과 수용한 것이 표시로 구별되어야 한다 — 섞이면 무엇이 아직 열려 있는지
        // 알 수 없다. alleyNodeIds와 통행량 지수, 두 건이 해소 상태다.
        long resolved = issues.stream().filter(t -> t.startsWith("[해소됨")).count();
        assertEquals(2, resolved,
                "해소된 항목 2건이 [해소됨] 표시를 달고 있어야 합니다. 실제: " + resolved);

        assertTrue(doc.path("howToReadResults").asText("").contains("절대값보다 경향"),
                "결과를 어떻게 읽어야 하는지가 기록돼 있어야 합니다.");
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
