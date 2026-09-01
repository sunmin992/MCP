package com.wastesim.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 교통 구역(traffic zone)의 이름과 위치.
 *
 * <p>구역은 <b>교통을 관측한 자리</b>다 — 실측 교통량 CSV의 링크를 키워드로 귀속시킨 지점이고,
 * {@code jangryang-weekday.json}의 {@code nodeHourlyWeight}가 이 구역들의 시간대별 혼잡
 * 가중치다. 학교·사거리·아파트가 여기 오는 이유는 그것이 관측 지점이기 때문이다.
 *
 * <p><b>수거 지점이 아니다.</b> 쓰레기가 나오는 곳은
 * {@link com.wastesim.site.CollectionSiteRegistry}에 따로 있고, 각 수거 지점은 자신이 속한
 * 구역을 {@code trafficZone}으로 가리킨다. 여러 수거 지점이 한 구역을 공유할 수 있다 —
 * 같은 골목의 원룸 여러 동은 같은 혼잡을 겪는다.
 *
 * <p>v1.12까지 둘은 {@code Node_A} 하나로 겹쳐 있었다. 라벨 체계가 같아 보이지만 지금은
 * <b>별개의 이름공간</b>이며, 수거 지점 {@code Node_A}와 교통 구역 {@code Node_A}는 서로 다른
 * 것을 가리킬 수 있다.
 *
 * <p>이 레지스트리가 하는 일은 하나다 — <b>어떤 구역 이름이 실재하는가</b>. 수거 지점이 없는
 * 구역을 가리키면 그 매핑은 조용히 전역 가중치로 떨어져 버리는데, 그건 설정 오류이지 정상
 * 동작이 아니다.
 */
@Component
public class TrafficZoneRegistry {

    private static final Logger log = LoggerFactory.getLogger(TrafficZoneRegistry.class);

    public static final String RESOURCE = "/traffic/jangryang-traffic-zones.json";

    private final String resourcePath;
    private Set<String> zoneIds = Set.of();

    public TrafficZoneRegistry() {
        this(RESOURCE);
    }

    public TrafficZoneRegistry(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** 기본 구역 목록을 즉시 읽어 돌려준다 — Spring 밖에서 만들 때 쓴다. */
    public static TrafficZoneRegistry ofDefault() {
        TrafficZoneRegistry r = new TrafficZoneRegistry();
        r.load();
        return r;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("교통 구역 파일이 클래스패스에 없습니다: " + resourcePath);
            }
            JsonNode doc = new ObjectMapper().readTree(in);
            JsonNode zones = doc.path("zones");
            if (!zones.isObject() || zones.isEmpty()) {
                throw new IllegalStateException("교통 구역이 하나도 없습니다: " + resourcePath);
            }
            Set<String> ids = new LinkedHashSet<>();
            zones.fieldNames().forEachRemaining(ids::add);
            this.zoneIds = Collections.unmodifiableSet(ids);
            log.info("교통 구역 {}개 등록 ({})", zoneIds.size(), resourcePath);
        } catch (IOException e) {
            throw new IllegalStateException("교통 구역 파일을 읽을 수 없습니다: " + resourcePath, e);
        }
    }

    public boolean isKnownZone(String zoneId) {
        return zoneId != null && zoneIds.contains(zoneId);
    }

    /** 등록된 구역 id 전체(파일 순서 유지, 수정 불가). */
    public Set<String> zoneIds() {
        return zoneIds;
    }

    public int size() {
        return zoneIds.size();
    }
}
