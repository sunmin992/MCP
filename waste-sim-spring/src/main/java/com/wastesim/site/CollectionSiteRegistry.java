package com.wastesim.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.simulation.SimulationEngine;
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
 * <p><b>같거나 가까운 좌표는 막지 않는다.</b> 서로 다른 지점 id가 같은 좌표를 가질 수 있다 —
 * 한 건물에 배출구가 둘일 수 있고, 그건 정상이다. 금지되는 것은 같은 id의 중복뿐이다.
 */
@Component
public class CollectionSiteRegistry {

    private static final Logger log = LoggerFactory.getLogger(CollectionSiteRegistry.class);

    static final String RESOURCE = "/collection/jangnyang-collection-sites.json";

    /** 장량동과 그 인접 구역을 넉넉히 감싸는 경계 — 좌표가 다른 도시로 튀는 것을 잡는다. */
    static final double MIN_LON = 129.34, MAX_LON = 129.42;
    static final double MIN_LAT = 36.04, MAX_LAT = 36.11;

    private final String resourcePath;
    private Map<String, CollectionSite> sites = Map.of();
    private double snapThresholdMeters = 300.0;

    public CollectionSiteRegistry() {
        this(RESOURCE);
    }

    /** 테스트가 다른 지점 목록을 물릴 때 쓰는 생성자. */
    CollectionSiteRegistry(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    @PostConstruct
    void load() {
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
        return new CollectionSite(id, lon, lat, n.path("name").asText(""), admin, source, snap);
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
