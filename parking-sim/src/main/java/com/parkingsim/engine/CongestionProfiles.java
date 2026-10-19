package com.parkingsim.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 도로 혼잡 프로파일 — 시(0–23)마다 주차장 사이 이동시간에 곱할 배수.
 *
 * <p>기동 시 한 번 읽는다. 프로파일이 없으면 기동을 실패시킨다 — 혼잡을 반영하라고 했는데 조용히
 * 배수 1 로 돌면 반영하지 않은 결과가 반영한 결과로 읽힌다.
 */
@Component
public class CongestionProfiles {

    static final List<String> IDS = List.of("weekday-downtown", "weekend-downtown");

    private final Map<String, double[]> byId = new LinkedHashMap<>();
    private final Map<String, Boolean> measured = new LinkedHashMap<>();

    public CongestionProfiles() {
        ObjectMapper mapper = new ObjectMapper();
        for (String id : IDS) {
            String path = "/traffic/" + id + ".json";
            try (InputStream in = CongestionProfiles.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("혼잡 프로파일이 없습니다: " + path);
                JsonNode root = mapper.readTree(in);
                JsonNode hours = root.path("hourlyMultiplier");
                if (hours.size() != 24) {
                    throw new IllegalStateException("혼잡 프로파일은 24시간 값이어야 합니다: " + path);
                }
                double[] m = new double[24];
                for (int h = 0; h < 24; h++) m[h] = hours.get(h).asDouble();
                byId.put(id, m);
                measured.put(id, root.path("measured").asBoolean(false));
            } catch (IOException e) {
                throw new UncheckedIOException("혼잡 프로파일을 읽지 못했습니다: " + path, e);
            }
        }
    }

    public Optional<double[]> multipliers(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean isMeasured(String id) {
        return measured.getOrDefault(id, false);
    }

    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }
}
