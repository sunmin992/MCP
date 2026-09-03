package com.wastesim.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.model.ZoneAssignmentRule;
import com.wastesim.traffic.TrafficZoneRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 수거 지점 id → 실제 좌표. <b>선택적 바인딩</b>이다.
 *
 * <p>등록되지 않은 지점은 오류가 아니라 “좌표 없음”이고, 호출부는 기존 이동시간 모델로
 * 돌아간다. 레지스트리가 비어 있어도 시스템은 지금과 똑같이 동작한다 — 이 계층은 무언가를
 * 대체하는 것이 아니라 <b>실제 도로 기반 계산을 쓸 수 있는 지점을 늘려 가는</b> 것이다.
 *
 * <p>이렇게 만든 이유는 코드상 수거 지점이 고정된 실제 집합이 아니기 때문이다. 사용자는
 * 실험마다 건물 수(1~26)와 지점 이름을 직접 정한다(ST-004·ST-005). 그러므로 “장량동의 수거
 * 지점은 이 N곳이다”라고 못박는 카탈로그는 이 시스템의 사용 방식과 맞지 않는다. 대신
 * <b>좌표를 아는 지점만 사전에 올려 두고</b>, 모르는 지점은 모른다고 답한다.
 *
 * <h2>기동 시 검사</h2>
 * 잘못된 좌표는 <b>기동을 막는다</b>. 런타임에 조용히 무시하면 “왜 이 지점만 OSRM이 안 붙지”를
 * 나중에 디버깅하게 되고, 그때는 어느 값이 문제였는지 알 수 없다. 검사 항목은 리소스 파일의
 * {@code admissionCriteria}와 같다.
 *
 * <p><b>같거나 가까운 좌표는 막지 않는다.</b> 서로 다른 지점 id가 같은 좌표를 가질 수 있다.
 * 원본 모델에서 수거 지점은 건물과 1:1이므로({@link CollectionSite}) 한 지점이 배출구를
 * 여럿 갖지는 않지만, <b>서로 다른 건물이 사실상 같은 지점에 배출</b>하는 것은 흔하다 —
 * 붙어 있는 원룸 두 동이 같은 골목 어귀를 쓰는 경우다. 금지되는 것은 같은 id의 중복뿐이고,
 * 그건 JSON 객체 키가 막는다.
 */
@Component
public class CollectionSiteRegistry {

    private static final Logger log = LoggerFactory.getLogger(CollectionSiteRegistry.class);

    static final String RESOURCE = "/collection/jangnyang-collection-sites.json";

    /** 장량동과 그 인접 구역을 넉넉히 감싸는 경계 — 좌표가 다른 도시로 튀는 것을 잡는다. */
    static final double MIN_LON = 129.34, MAX_LON = 129.42;
    static final double MIN_LAT = 36.04, MAX_LAT = 36.11;

    private final String resourcePath;
    private final TrafficZoneRegistry zones;
    private Map<String, CollectionSite> sites = Map.of();
    private double snapThresholdMeters = 300.0;

    @org.springframework.beans.factory.annotation.Autowired
    public CollectionSiteRegistry(TrafficZoneRegistry zones) {
        this(RESOURCE, zones);
    }

    /** 지점이 하나도 없는 레지스트리 — 좌표를 쓰지 않는 호출부를 위한 것이다. */
    public static CollectionSiteRegistry empty() {
        CollectionSiteRegistry r = new CollectionSiteRegistry("/collection/empty-sites.json");
        r.load();
        return r;
    }

    /** 테스트 또는 별도 구성에서 다른 지점 목록을 물릴 때 쓰는 생성자. */
    public CollectionSiteRegistry(String resourcePath) {
        this(resourcePath, TrafficZoneRegistry.ofDefault());
    }

    public CollectionSiteRegistry(String resourcePath, TrafficZoneRegistry zones) {
        this.resourcePath = resourcePath;
        this.zones = zones;
    }

    /** 리소스를 읽어 지점을 올린다. Spring이 기동 시 부르고, 직접 만든 경우 호출부가 부른다. */
    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("수거 지점 파일이 클래스패스에 없습니다: " + resourcePath);
            }
            JsonNode doc = new ObjectMapper().readTree(in);
            this.snapThresholdMeters = doc.path("snapThresholdMeters").asDouble(300.0);
            this.sites = parse(doc);
            log.info("수거 지점 좌표 {}곳 등록 ({})", sites.size(), resourcePath);
        } catch (IOException e) {
            throw new IllegalStateException("수거 지점 파일을 읽을 수 없습니다: " + resourcePath, e);
        }
    }

    private Map<String, CollectionSite> parse(JsonNode doc) {
        JsonNode node = doc.path("sites");
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) {
            throw new IllegalStateException("sites는 'id → 지점' 객체여야 합니다.");
        }
        Map<String, CollectionSite> parsed = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String id = e.getKey();
            CollectionSite site = readSite(id, e.getValue());
            parsed.put(id, site);
        });
        return Collections.unmodifiableMap(parsed);
    }

    private CollectionSite readSite(String id, JsonNode n) {
        if (SimulationEngine.nodeIndex(id) < 0) {
            throw new IllegalStateException(
                    "수거 지점 id는 Node_A~Node_Z여야 합니다(엔진이 건물에 붙이는 라벨과 같은 체계). 받은 값: " + id);
        }
        double lon = requireFinite(n, "longitude", id);
        double lat = requireFinite(n, "latitude", id);
        if (lon < MIN_LON || lon > MAX_LON || lat < MIN_LAT || lat > MAX_LAT) {
            throw new IllegalStateException(id + "의 좌표가 장량동 인근을 벗어납니다: " + lon + ", " + lat);
        }
        String admin = n.path("adminDivision").asText("");
        if (!admin.contains("장량동")) {
            throw new IllegalStateException(
                    id + "의 행정동이 장량동이 아닙니다(이 프로젝트는 장량동만 다룹니다): " + admin);
        }
        String source = n.path("source").asText("");
        if (source.isBlank()) {
            // 출처 없는 좌표는 다음 사람이 검증할 수 없다 — 값이 맞는지 틀린지 물을 곳이 없어진다.
            throw new IllegalStateException(id + "에 좌표의 출처(source)가 없습니다.");
        }
        double snap = requireFinite(n, "snapMeters", id);
        if (snap < 0 || snap > snapThresholdMeters) {
            throw new IllegalStateException(id + "의 스냅 거리 " + snap + "m가 허용 "
                    + snapThresholdMeters + "m를 벗어납니다. 이 좌표의 이동시간은 요청한 위치의 값이 아닙니다.");
        }
        String zone = n.path("trafficZone").asText("");
        if (!zone.isBlank() && !zones.isKnownZone(zone)) {
            // 없는 구역을 가리키면 조용히 전역 가중치로 떨어진다 — 설정 오류가 정상 동작처럼
            // 보이는 자리라서 여기서 막는다.
            throw new IllegalStateException(id + "가 등록되지 않은 교통 구역을 가리킵니다: " + zone
                    + " (등록된 구역: " + zones.zoneIds() + ")");
        }
        JsonNode allowed = n.get("largeTruckAllowed");
        if (allowed == null || !allowed.isBoolean()) {
            // 기본값으로 얼버무리면 둘 중 하나가 조용히 틀린다 — 참으로 두면 골목에 5톤을
            // 들여보내고, 거짓으로 두면 멀쩡한 지점을 막는다. 어느 쪽도 나중에 알아채기 어렵다.
            throw new IllegalStateException(id + "에 largeTruckAllowed(대형 차량 진입 가능 여부)가 없습니다.");
        }
        return new CollectionSite(id, lon, lat, n.path("name").asText(""), admin, source, snap,
                zone, allowed.booleanValue());
    }

    private static double requireFinite(JsonNode n, String field, String id) {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber() || !Double.isFinite(v.doubleValue())) {
            throw new IllegalStateException(id + "." + field + "가 유한한 숫자가 아닙니다.");
        }
        return v.doubleValue();
    }

    /**
     * 이 지점의 좌표. <b>모르면 비어 있다</b> — 호출부는 기존 이동시간 모델로 돌아간다.
     * 좌표를 지어내지 않는 것이 이 메서드의 유일한 계약이다.
     */
    public Optional<CollectionSite> find(String siteId) {
        return Optional.ofNullable(siteId == null ? null : sites.get(siteId));
    }

    public boolean isRegistered(String siteId) {
        return siteId != null && sites.containsKey(siteId);
    }

    /** 등록된 지점 전체(등록 순서 유지, 수정 불가). */
    public Map<String, CollectionSite> all() {
        return sites;
    }

    public int size() {
        return sites.size();
    }

    /**
     * 이 수거 지점의 혼잡을 대표하는 교통 구역. <b>매핑이 없으면 비어 있다</b> — 그때 호출부는
     * 구역별 가중치 대신 전역 시간대 가중치를 쓴다.
     *
     * <p>아직 이 값을 읽는 계산 경로는 없다. 지금 혼잡 가중치를 찾는 두 자리
     * ({@code SimulationEngine}·{@code RouteDurationEstimator})는 <b>수거 지점 id를 그대로
     * 구역 id로 넘긴다</b> — 두 이름공간이 겹쳐 있던 시절의 잔재이며, 매핑이 비어 있는 지금은
     * 결과가 같다. 그 두 곳을 이 메서드로 바꾸는 것이 이동시간 작업의 첫 단계다.
     */
    /**
     * 등록된 구역 목록(정렬). 배정 규칙이 이 순서로 구역을 고른다 — 순서가 흔들리면 같은
     * 설정이 다른 배정을 내고 재현성(NFR-02)이 깨지므로 정렬해서 돌려준다.
     */
    public java.util.List<String> sortedZoneIds() {
        java.util.List<String> out = new java.util.ArrayList<>(zones.zoneIds());
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * 이 지점이 속한 교통 구역 — <b>엔진과 검증기가 함께 쓰는 단 하나의 해석</b>이다.
     *
     * <p>세 단계로 찾는다. 앞선 단계가 이긴다.
     *
     * <ol>
     *   <li><b>등록된 지점의 {@code trafficZone}</b> — 조사한 사실.</li>
     *   <li><b>배정 규칙</b> — 가정({@link ZoneAssignmentRule}). 결과에
     *       {@code ZONE_ASSIGNMENT_ASSUMED}로 표시된다.</li>
     *   <li><b>지점 id 그대로</b> — 두 이름공간이 겹쳐 있던 시절의 폴백. 이름이 겹치는
     *       {@code Node_A~D}까지만 우연히 성립한다.</li>
     * </ol>
     *
     * <p>이 순서가 뒤집히면 규칙이 조사한 사실을 덮는다. 가정이 데이터를 밀어내는 방향은
     * 있을 수 없다.
     *
     * <p><b>한 곳에 두는 이유</b>: 엔진과 검증기가 각자 해석하면 검증을 통과한 설정이
     * 실행에서 죽는다 — 트럭 배정에서 이미 한 번 겪었다.
     *
     * @param rule           배정 규칙. {@code null}이면 {@link ZoneAssignmentRule#NONE}.
     * @param totalBuildings 전체 건물 수. 연속 블록 배정이 블록 크기를 정하는 데 쓴다.
     */
    public String resolveZone(String siteId, ZoneAssignmentRule rule, int totalBuildings) {
        java.util.Optional<String> registered = trafficZoneOf(siteId);
        if (registered.isPresent()) return registered.get();

        if (rule != null && rule.assigns()) {
            int index = SimulationEngine.nodeIndex(siteId);
            java.util.Optional<String> assigned =
                    rule.assign(index, totalBuildings, sortedZoneIds());
            if (assigned.isPresent()) return assigned.get();
        }
        return siteId;
    }

    public java.util.Optional<String> trafficZoneOf(String siteId) {
        CollectionSite s = siteId == null ? null : sites.get(siteId);
        return s != null && s.hasTrafficZone() ? java.util.Optional.of(s.trafficZone())
                                               : java.util.Optional.empty();
    }

    /**
     * 이 지점들 중 대형 차량이 들어갈 수 없는 곳. 등록되지 않은 지점은 <b>포함하지 않는다</b> —
     * 접근성을 모르는 것과 못 들어간다는 것은 다르고, 모르는 것을 이유로 실행을 막지 않는다.
     */
    public java.util.List<String> largeTruckBlockedAmong(Iterable<String> siteIds) {
        java.util.List<String> blocked = new java.util.ArrayList<>();
        if (siteIds == null) return blocked;
        for (String id : siteIds) {
            CollectionSite s = sites.get(id);
            if (s != null && !s.largeTruckAllowed()) blocked.add(id);
        }
        return blocked;
    }

    /**
     * 이 지점들 전부에 좌표가 있는가 — 실제 도로 기반 계산을 쓸 수 있는지 판단하는 자리다.
     * 하나라도 없으면 경로 전체가 기존 모델로 간다. 일부만 실제 거리로 재면 구간마다 다른
     * 축의 값이 섞여 합계가 무엇을 뜻하는지 알 수 없게 된다.
     */
    public boolean coversAll(Iterable<String> siteIds) {
        if (siteIds == null) return false;
        boolean any = false;
        for (String id : siteIds) {
            any = true;
            if (!isRegistered(id)) return false;
        }
        return any;
    }
}
