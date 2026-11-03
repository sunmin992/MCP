package com.wastesim.region;

import com.fasterxml.jackson.databind.*;
import com.wastesim.model.SimulationConfig;
import com.wastesim.readiness.ValidationReceipt;
import java.nio.file.*;
import java.util.*;

/** Provider-owned region bundles. Synthetic inputs never borrow Jangnyang traffic. */
public final class RegionCatalog {
    public static final String RESOURCE = "/regions/catalog.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, JsonNode> regions = new LinkedHashMap<>();
    private JsonNode genericProfile;
    public RegionCatalog() {
        try (var in = RegionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalArgumentException("지역 데이터 묶음이 없습니다");
            load(JSON.readTree(in));
        } catch (java.io.IOException e) { throw new IllegalArgumentException("지역 데이터 읽기 실패", e); }
    }
    public RegionCatalog(JsonNode data) { load(data); }
    private void load(JsonNode data) {
        com.wastesim.contract.RequirementContract.version(data);
        if (!data.path("regions").isArray() || data.path("regions").isEmpty())
            throw new IllegalArgumentException("지역 목록이 없습니다");
        for (JsonNode r : data.get("regions")) {
            for (String key : List.of("id", "version", "label", "source"))
                if (!r.path(key).isTextual() || r.path(key).asText().isBlank())
                    throw new IllegalArgumentException("지역 필수 정보 누락: " + key);
            if (!r.path("defaults").isObject() || !r.path("synthetic").isBoolean()
                    || !r.path("allowProviderTraffic").isBoolean() || !r.path("supportedTravelModes").isArray()
                    || r.path("supportedTravelModes").isEmpty()) throw new IllegalArgumentException("불완전한 지역 설정");
            r.get("defaults").fields().forEachRemaining(e -> {
                if (!Set.of("numBuildings", "residentsPerBuilding", "capacity").contains(e.getKey())
                        || !e.getValue().canConvertToInt() || !e.getValue().isIntegralNumber()
                        || e.getValue().asInt() < 1 || e.getKey().equals("numBuildings") && e.getValue().asInt() > 26)
                    throw new IllegalArgumentException("지원하지 않거나 잘못된 지역 기본값: " + e.getKey());
            });
            if (r.has("modelOverrides")) {
                var o = r.get("modelOverrides");
                if (!o.isObject() || o.size() != 1 || !o.path("routeTravelMinutes").isIntegralNumber()
                        || o.get("routeTravelMinutes").asInt() < 1 || o.get("routeTravelMinutes").asInt() > 600)
                    throw new IllegalArgumentException("지원하지 않거나 잘못된 지역 모델 값");
            }
            if (r.path("basis").asText().equals("PUBLIC_MAP_DATA") && (r.path("synthetic").asBoolean()
                    || r.path("allowProviderTraffic").asBoolean() || r.path("supportedTravelModes").size() != 1
                    || !r.path("supportedTravelModes").get(0).asText().equals("LEGACY_CONSTANT")))
                throw new IllegalArgumentException("공개 지도 블록은 상수 이동 모델만 쓰고 장량동 교통을 빌리지 않습니다");
            if (regions.putIfAbsent(r.get("id").asText(), r.deepCopy()) != null)
                throw new IllegalArgumentException("중복 지역 ID");
        }
        if (data.has("genericProfile")) {
            var profile = data.get("genericProfile");
            var wrapper = JSON.createObjectNode();
            wrapper.putArray("regions").add(profile);
            new RegionCatalog(wrapper); // Apply the same admission rules to the fallback.
            if (!profile.path("synthetic").asBoolean() || profile.path("allowProviderTraffic").asBoolean()
                    || !profile.path("basis").asText().equals("COMMON_MODEL_ASSUMPTIONS")
                    || profile.path("supportedTravelModes").size() != 1
                    || !profile.path("supportedTravelModes").get(0).asText().equals("LEGACY_CONSTANT"))
                throw new IllegalArgumentException("공통 지역은 가정 기반 상수 이동 모델이어야 합니다");
            genericProfile = profile.deepCopy();
        }
    }
    public JsonNode region(String id) {
        if (id == null || id.isBlank() || id.length() > 120 || id.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("지역 이름은 제어 문자가 없는 1~120자여야 합니다");
        JsonNode r = regions.get(id);
        if (r == null) r = cached(cacheDir(), id);
        if (r == null && genericProfile != null) {
            var assumed = (com.fasterxml.jackson.databind.node.ObjectNode) genericProfile.deepCopy();
            assumed.put("id", id);
            assumed.put("label", id + " — 공통 기본값으로 구성한 가상 블록");
            assumed.put("identityStatus", "USER_LABEL_NOT_GEOCODED");
            return assumed;
        }
        if (r == null) throw new IllegalArgumentException("지역 데이터가 없습니다: " + id + "; 지원 지역: " + regions.keySet());
        return r.deepCopy();
    }
    /** 공개 지도에서 수집한 지역 묶음의 보관 위치. 수집 원문도 함께 둔다. */
    public static Path cacheDir() {
        String dir = System.getProperty("wastesim.region-cache", System.getenv().getOrDefault("REGION_CACHE_DIR", "data/regions"));
        return Path.of(dir);
    }
    private static String key(String id) {
        return ValidationReceipt.fingerprint(JSON.getNodeFactory().textNode(id.strip())).replaceAll("[^A-Za-z0-9]", "").substring(0, 24);
    }
    /** 캐시된 묶음을 같은 등록 규칙으로 검사해 읽는다. 없거나 다른 이름의 묶음이면 null. */
    public static JsonNode cached(Path dir, String id) {
        if (dir == null || id == null || id.isBlank()) return null;
        Path file = dir.resolve(key(id) + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode r = JSON.readTree(file.toFile());
            if (!id.strip().equals(r.path("id").asText())) return null;
            var wrapper = JSON.createObjectNode().put("schemaVersion", "1.0.0");
            wrapper.putArray("regions").add(r);
            new RegionCatalog(wrapper);
            return r;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("지역 캐시 읽기 실패: " + id, e); }
    }
    public static void store(Path dir, JsonNode region, JsonNode raw) {
        try {
            Files.createDirectories(dir);
            String k = key(region.path("id").asText());
            JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(k + ".raw.json").toFile(), raw);
            JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(k + ".json").toFile(), region);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("지역 캐시 저장 실패", e); }
    }
    /** 템플릿으로 들어가지 않는 지역 모델 값(구간 이동시간)을 실행 설정에 옮긴다. */
    public static void applyOverrides(JsonNode region, SimulationConfig cfg) {
        var o = region.path("modelOverrides");
        if (o.has("routeTravelMinutes")) cfg.setRouteTravelMinutes(o.get("routeTravelMinutes").asInt());
    }
    public List<JsonNode> all() { return regions.values().stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
    public JsonNode genericProfile() { return genericProfile == null ? JSON.nullNode() : genericProfile.deepCopy(); }
    public static String digest(JsonNode region) { return ValidationReceipt.fingerprint(region); }
    public List<String> check(SimulationConfig cfg) {
        try {
            JsonNode r = region(cfg.getRegionId());
            List<String> errors = new ArrayList<>();
            if (!java.util.Objects.equals(cfg.getRegionDataDigest(), digest(r))) errors.add("지역 데이터 식별값 불일치");
            boolean mode = false;
            for (JsonNode m : r.get("supportedTravelModes")) if (m.asText().equals(cfg.resolveTravelTimeMode().name())) mode = true;
            if (!mode) errors.add("선택한 지역에서 이동 모드가 지원되지 않습니다");
            var o = r.path("modelOverrides");
            if (o.has("routeTravelMinutes") && cfg.getRouteTravelMinutes() != o.get("routeTravelMinutes").asInt())
                errors.add("지역 자료의 구간 이동시간과 실행 설정이 다릅니다");
            if (!r.get("allowProviderTraffic").asBoolean() && cfg.isTrafficEnabled()) errors.add("합성 지역에 장량동 교통을 적용할 수 없습니다");
            if (!r.get("allowProviderTraffic").asBoolean()
                    && cfg.resolveDischargeTimeMode() == com.wastesim.model.DischargeTimeMode.POHANG_ACTUAL)
                errors.add("가상 지역에 포항 배출 시간대를 적용할 수 없습니다");
            return errors;
        } catch (IllegalArgumentException e) { return List.of(e.getMessage()); }
    }
}
