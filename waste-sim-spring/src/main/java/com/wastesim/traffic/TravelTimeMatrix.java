package com.wastesim.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 수거 지점 사이의 <b>자유주행시간</b>(정차·상차를 뺀 순수 주행시간, 초).
 *
 * <p>{@code OSRM_HYBRID} 모드가 읽는 값이며 <b>미리 계산해 둔 것</b>이다. 시뮬레이션 도중
 * OSRM을 부르지 않는 이유는 재현성이다(NFR-02) — 같은 시드·같은 파라미터가 같은 결과를 내야
 * 하는데, 외부 서비스 호출은 그 보장을 깨고 결과를 네트워크 가용성에 묶는다. 엔진은 하루에도
 * 수백 번 구간 시간을 묻는다.
 *
 * <p><b>방향이 있다.</b> OSRM은 일방통행과 중앙분리대를 반영하므로 A→B와 B→A가 다를 수 있다.
 * 실측에서 장량동 근처 두 지점이 거리 기준 2.8배까지 벌어진 사례가 있었다.
 *
 * <p>덮지 못한 구간이 있으면 호출부는 <b>실행을 막아야 한다.</b> 조용히 상수 모드로 되돌리면
 * 두 모드의 결과가 구별되지 않아, 나온 숫자가 무엇으로 계산된 것인지 알 수 없게 된다.
 * 그래서 이 클래스는 없는 값을 0이나 추정치로 채우지 않고 "없다"고 답한다.
 */
@Component
@org.springframework.context.annotation.Primary
public class TravelTimeMatrix {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeMatrix.class);

    public static final String RESOURCE = "/traffic/jangryang-travel-times.json";

    /**
     * <b>교통 구역</b> 간 행렬. {@code ZONE_PROXY_HYBRID}가 읽는다.
     *
     * <p>같은 파서를 쓰지만 키가 가리키는 대상이 다르다 — 이쪽 {@code Node_A~D}는 교통
     * 구역이고, {@link #RESOURCE}의 {@code Node_A~D}는 수거 지점이다. 라벨 형태가 같아서
     * 헷갈리기 쉬우므로 파일을 갈라 뒀다.
     */
    public static final String ZONE_RESOURCE = "/traffic/jangryang-zone-travel-times.json";

    private final String resourcePath;
    private Map<String, Double> freeFlowSeconds = Map.of();

    public TravelTimeMatrix() {
        this(RESOURCE);
    }

    public TravelTimeMatrix(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** 값이 하나도 없는 행렬 — 상수 모드로만 도는 호출부를 위한 것이다. */
    public static TravelTimeMatrix empty() {
        TravelTimeMatrix m = new TravelTimeMatrix("/traffic/empty-travel-times.json");
        m.load();
        return m;
    }

    /** 구역 간 행렬을 즉시 읽어 돌려준다 — Spring 밖에서 만들 때 쓴다. */
    public static TravelTimeMatrix ofZones() {
        TravelTimeMatrix m = new TravelTimeMatrix(ZONE_RESOURCE);
        m.load();
        return m;
    }

    /** 기본 행렬을 즉시 읽어 돌려준다 — Spring 밖에서 만들 때 쓴다. */
    public static TravelTimeMatrix ofDefault() {
        TravelTimeMatrix m = new TravelTimeMatrix();
        m.load();
        return m;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("이동시간 행렬 파일이 클래스패스에 없습니다: " + resourcePath);
            }
            JsonNode doc = new ObjectMapper().readTree(in);
            this.freeFlowSeconds = parse(doc.path("pairs"));
            log.info("자유주행시간 {}쌍 등록 ({})", freeFlowSeconds.size(), resourcePath);
        } catch (IOException e) {
            throw new IllegalStateException("이동시간 행렬 파일을 읽을 수 없습니다: " + resourcePath, e);
        }
    }

    private static Map<String, Double> parse(JsonNode pairs) {
        if (pairs.isMissingNode() || pairs.isNull()) return Map.of();
        if (!pairs.isObject()) throw new IllegalStateException("pairs는 '구간 → 초' 객체여야 합니다.");
        Map<String, Double> parsed = new LinkedHashMap<>();
        pairs.fields().forEachRemaining(e -> {
            String key = e.getKey();
            if (!key.matches("Node_[A-Z]->Node_[A-Z]")) {
                throw new IllegalStateException("구간 키는 \"Node_A->Node_B\" 형식이어야 합니다: " + key);
            }
            String[] ends = key.split("->");
            if (ends[0].equals(ends[1])) {
                throw new IllegalStateException("같은 지점끼리의 구간은 있을 수 없습니다: " + key);
            }
            JsonNode v = e.getValue();
            if (!v.isNumber() || !Double.isFinite(v.doubleValue()) || v.doubleValue() < 0) {
                throw new IllegalStateException(key + "의 자유주행시간이 0 이상의 유한한 숫자가 아닙니다: " + v);
            }
            parsed.put(key, v.doubleValue());
        });
        return Collections.unmodifiableMap(parsed);
    }

    private static String key(String from, String to) {
        return from + "->" + to;
    }

    /** 이 구간의 자유주행시간(초). <b>모르면 비어 있다</b> — 0이나 추정치로 채우지 않는다. */
    public OptionalDouble freeFlowSeconds(String from, String to) {
        if (from == null || to == null) return OptionalDouble.empty();
        Double v = freeFlowSeconds.get(key(from, to));
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    /**
     * 이 방문 순서에서 값이 없는 구간들. 비어 있으면 {@code OSRM_HYBRID}로 계산할 수 있다.
     *
     * @param routeSequence 방문 순서(지점 id). 2개 미만이면 구간이 없으므로 빈 목록.
     */
    public List<String> missingHops(List<String> routeSequence) {
        List<String> missing = new ArrayList<>();
        if (routeSequence == null || routeSequence.size() < 2) return missing;
        for (int i = 1; i < routeSequence.size(); i++) {
            String from = routeSequence.get(i - 1);
            String to = routeSequence.get(i);
            if (freeFlowSeconds(from, to).isEmpty()) missing.add(key(from, to));
        }
        return missing;
    }

    public int size() {
        return freeFlowSeconds.size();
    }

    public boolean isEmpty() {
        return freeFlowSeconds.isEmpty();
    }
}
