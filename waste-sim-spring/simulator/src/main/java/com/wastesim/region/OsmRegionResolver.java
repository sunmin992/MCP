package com.wastesim.region;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * 지역명 → 공개 지도(OpenStreetMap) 자료로 만든 실제 주거 블록 묶음.
 *
 * <p>Nominatim 으로 지역을 확정하고, Overpass 로 건물을, OSRM 으로 블록 안 도로 이동시간을 얻는다.
 * 엔진은 건물 좌표를 받지 않으므로 블록을 건물 수 · 동당 세대 수 · 구간 이동시간 세 값으로 줄인다.
 * 모든 값에 출처 등급(OBSERVED / ESTIMATED / ASSUMED)을 붙이고, 조회 원문을 함께 캐시해 재현할 수 있게 한다.
 * 같은 이름이 여러 곳이면 고르지 않고 후보를 돌려준다.
 */
public final class OsmRegionResolver {
    public enum Status { RESOLVED, AMBIGUOUS, NOT_FOUND, INSUFFICIENT_DATA, UNAVAILABLE }
    public record Result(Status status, JsonNode region, List<JsonNode> candidates, String reason) {}

    static final int MAX_BUILDINGS = 26, MIN_BUILDINGS = 2, NODE_RADIUS_M = 700, NEIGHBOR_M = 150, BLOCK_M = 300;
    static final double MIN_FOOTPRINT_M2 = 20;
    /** 장량동 기준선의 구간 상수 15분 − 장량동 OSRM 구간 약 3분. 정차·상차 몫으로 본다. */
    public static final int ASSUMED_STOP_MINUTES = 12;
    /** OSRM 을 못 쓸 때 직선거리 보정: 우회 계수 1.4, 주거지 주행 20km/h. */
    static final double DETOUR = 1.4, FALLBACK_KMH = 20;
    /**
     * 건물 유형별 (층수 기본값, 세대당 연면적 ㎡). 층수 태그가 있으면 태그를 쓴다. 세대 1 고정 유형은 면적 0.
     * 국내 OSM 의 house 는 다가구·다세대가 많아 1세대로 고정하지 않고 면적으로 센다.
     */
    private static final Map<String, double[]> TYPE_RULES = Map.of(
            "apartments", new double[]{15, 85}, "residential", new double[]{4, 60}, "dormitory", new double[]{5, 25},
            "house", new double[]{2, 60}, "detached", new double[]{2, 60}, "semidetached_house", new double[]{2, 60},
            "terrace", new double[]{2, 60}, "bungalow", new double[]{1, 0}, "yes", new double[]{3, 60});
    private static final Set<String> PLACE_TYPES = Set.of("quarter", "suburb", "neighbourhood", "village", "town", "hamlet", "city_block");

    private final Function<URI, String> http;
    private final Path cacheDir;
    private final String nominatim, overpass, osrm;
    private final ObjectMapper json = new ObjectMapper();

    public OsmRegionResolver(Function<URI, String> http, Path cacheDir, String nominatim, String overpass, String osrm) {
        this.http = http; this.cacheDir = cacheDir;
        this.nominatim = strip(nominatim); this.overpass = strip(overpass); this.osrm = strip(osrm);
    }
    private static String strip(String url) { return url.endsWith("/") ? url.substring(0, url.length() - 1) : url; }

    public Result resolve(String query) {
        var cached = RegionCatalog.cached(cacheDir, query);
        if (cached != null) return new Result(Status.RESOLVED, cached, List.of(), "cache");
        var raw = json.createObjectNode();
        try {
            var places = json.readTree(http.apply(URI.create(nominatim + "/search?format=jsonv2&addressdetails=1&limit=15&countrycodes=kr&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8))));
            raw.set("nominatim", places);
            Map<String, JsonNode> areas = new LinkedHashMap<>();
            for (JsonNode p : places) {
                boolean admin = p.path("category").asText().equals("boundary") && p.path("type").asText().equals("administrative");
                if (!admin && !(p.path("category").asText().equals("place") && PLACE_TYPES.contains(p.path("type").asText()))) continue;
                JsonNode prev = areas.get(areaKey(p));
                if (prev == null || !prev.path("osm_type").asText().equals("relation") && p.path("osm_type").asText().equals("relation"))
                    areas.put(areaKey(p), p);
            }
            if (areas.isEmpty()) return new Result(Status.NOT_FOUND, null, List.of(), "지도에서 행정구역을 찾지 못했습니다: " + query);
            if (areas.size() > 1) {
                List<JsonNode> candidates = new ArrayList<>();
                for (JsonNode p : areas.values()) candidates.add(json.createObjectNode()
                        .put("regionName", suggestedName(p)).put("displayName", p.path("display_name").asText()));
                return new Result(Status.AMBIGUOUS, null, candidates, "같은 이름의 지역이 " + areas.size() + "곳입니다. 시·구를 함께 알려 주십시오");
            }
            JsonNode place = areas.values().iterator().next();
            var buildings = buildings(place, raw);
            var block = block(buildings);
            if (block.size() < MIN_BUILDINGS)
                return new Result(Status.INSUFFICIENT_DATA, null, List.of(), "지도에 주거 건물이 " + block.size() + "동뿐이라 블록을 만들 수 없습니다");
            var region = bundle(query, place, order(block), raw);
            RegionCatalog.store(cacheDir, region, raw);
            return new Result(Status.RESOLVED, RegionCatalog.cached(cacheDir, query), List.of(), "fetched");
        } catch (RuntimeException | java.io.IOException e) {
            return new Result(Status.UNAVAILABLE, null, List.of(), "지도 자료 조회 실패: " + e.getMessage());
        }
    }

    private static String first(JsonNode a, String... keys) {
        for (String k : keys) if (a.hasNonNull(k)) return a.get(k).asText();
        return "";
    }
    /** 같은 동이 경계(relation)와 지점(node)으로 두 번 나오므로 이름 · 시군구 · 시도로 묶는다. */
    private static String areaKey(JsonNode p) {
        var a = p.path("address");
        return p.path("name").asText() + "|" + first(a, "borough", "county", "city_district", "town")
                + "|" + first(a, "city", "province", "state");
    }
    private static String suggestedName(JsonNode p) {
        var a = p.path("address");
        return String.join(" ", new LinkedHashSet<>(List.of(p.path("name").asText(),
                first(a, "borough", "county", "city_district", "town"), first(a, "city", "province", "state")))).replaceAll("\\s+", " ").trim();
    }

    public record Building(long id, String type, double lat, double lon, double footprint, Integer levels) {}

    private List<Building> buildings(JsonNode place, ObjectNode raw) throws java.io.IOException {
        String type = place.path("osm_type").asText();
        long id = place.path("osm_id").asLong();
        String filter = switch (type) {
            case "relation" -> "area(id:" + (3600000000L + id) + ")->.a;way[\"building\"](area.a);";
            case "way" -> "area(id:" + (2400000000L + id) + ")->.a;way[\"building\"](area.a);";
            default -> "way[\"building\"](around:" + NODE_RADIUS_M + "," + place.path("lat").asText() + "," + place.path("lon").asText() + ");";
        };
        var data = json.readTree(http.apply(URI.create(overpass + "/interpreter?data="
                + URLEncoder.encode("[out:json][timeout:25];" + filter + "out tags geom;", StandardCharsets.UTF_8))));
        raw.set("overpass", data);
        List<Building> all = new ArrayList<>();
        for (JsonNode w : data.path("elements")) {
            String kind = w.path("tags").path("building").asText();
            if (!TYPE_RULES.containsKey(kind) || w.path("geometry").size() < 3) continue;
            double area = footprint(w.path("geometry"));
            if (area < MIN_FOOTPRINT_M2) continue;
            double lat = 0, lon = 0;
            for (JsonNode g : w.path("geometry")) { lat += g.path("lat").asDouble(); lon += g.path("lon").asDouble(); }
            Integer levels = null;
            try { levels = (int) Math.round(Double.parseDouble(w.path("tags").path("building:levels").asText())); }
            catch (NumberFormatException ignored) { }
            if (levels != null && levels < 1) levels = null;
            all.add(new Building(w.path("id").asLong(), kind, lat / w.path("geometry").size(), lon / w.path("geometry").size(), area, levels));
        }
        // 용도가 적힌 주거 건물이 모자랄 때만 용도 미상(building=yes)을 주거로 가정해 쓴다.
        var residential = all.stream().filter(b -> !b.type().equals("yes")).toList();
        return residential.size() >= 4 ? residential : all;
    }

    static double footprint(JsonNode ring) {
        double lat0 = Math.toRadians(ring.get(0).path("lat").asDouble()), sum = 0;
        for (int i = 0; i < ring.size(); i++) {
            JsonNode a = ring.get(i), b = ring.get((i + 1) % ring.size());
            sum += x(a, lat0) * y(b) - x(b, lat0) * y(a);
        }
        return Math.abs(sum) / 2;
    }
    private static double x(JsonNode p, double lat0) { return Math.toRadians(p.path("lon").asDouble()) * Math.cos(lat0) * 6371000; }
    private static double y(JsonNode p) { return Math.toRadians(p.path("lat").asDouble()) * 6371000; }
    static double meters(Building a, Building b) {
        double dLat = Math.toRadians(b.lat() - a.lat()), dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(h));
    }

    /** 주거 건물이 가장 촘촘한 지점을 중심으로 가까운 건물을 최대 26동 고른다. 동률은 OSM id 가 작은 쪽. */
    public static List<Building> block(List<Building> buildings) {
        Building seed = null; long best = -1;
        for (Building b : buildings) {
            long n = buildings.stream().filter(o -> meters(b, o) <= NEIGHBOR_M).count();
            if (n > best || n == best && b.id() < seed.id()) { best = n; seed = b; }
        }
        if (seed == null) return List.of();
        final Building s = seed;
        return buildings.stream().filter(b -> meters(s, b) <= BLOCK_M)
                .sorted(Comparator.comparingDouble((Building b) -> meters(s, b)).thenComparingLong(Building::id))
                .limit(MAX_BUILDINGS).toList();
    }
    /** 중심 건물에서 시작하는 최근접 순회. 엔진의 Node_A, Node_B … 순서가 된다. */
    static List<Building> order(List<Building> block) {
        List<Building> left = new ArrayList<>(block.subList(1, block.size())), out = new ArrayList<>(List.of(block.get(0)));
        while (!left.isEmpty()) {
            Building last = out.get(out.size() - 1);
            Building next = Collections.min(left, Comparator.comparingDouble((Building b) -> meters(last, b)).thenComparingLong(Building::id));
            left.remove(next); out.add(next);
        }
        return out;
    }

    private ObjectNode bundle(String query, JsonNode place, List<Building> block, ObjectNode raw) {
        var region = json.createObjectNode();
        region.put("id", query).put("version", "1.0.0")
                .put("label", query + " — 공개 지도 자료로 구성한 실제 주거 블록 (" + block.size() + "동)")
                .put("source", "OpenStreetMap 기여자(ODbL). Nominatim · Overpass · OSRM 조회")
                .put("synthetic", false).put("basis", "PUBLIC_MAP_DATA").put("allowProviderTraffic", false)
                .put("retrievedAt", Instant.now().toString());
        region.putArray("supportedTravelModes").add("LEGACY_CONSTANT");
        region.putObject("osm").put("type", place.path("osm_type").asText()).put("id", place.path("osm_id").asLong())
                .put("displayName", place.path("display_name").asText())
                .put("searchArea", place.path("osm_type").asText().equals("node") ? "반경 " + NODE_RADIUS_M + "m" : "행정 경계");

        var rows = json.createArrayNode();
        int observedLevels = 0, assumedUse = 0; double households = 0;
        for (int i = 0; i < block.size(); i++) {
            Building b = block.get(i);
            double[] rule = TYPE_RULES.get(b.type());
            int levels = b.levels() != null ? b.levels() : (int) rule[0];
            int units = rule[1] == 0 ? 1 : (int) Math.max(1, Math.round(b.footprint() * levels / rule[1]));
            if (b.levels() != null) observedLevels++;
            if (b.type().equals("yes")) assumedUse++;
            households += units;
            rows.addObject().put("node", com.wastesim.simulation.RoutePlanner.nodeId(i)).put("osmWayId", b.id())
                    .put("building", b.type()).put("lat", round(b.lat(), 6)).put("lon", round(b.lon(), 6))
                    .put("footprintM2", Math.round(b.footprint())).put("levels", levels)
                    .put("levelsSource", b.levels() != null ? "OBSERVED" : "ASSUMED").put("households", units);
        }
        double hopSeconds; String roadSource;
        try {
            var coords = new StringJoiner(";");
            block.forEach(b -> coords.add(b.lon() + "," + b.lat()));
            var route = json.readTree(http.apply(URI.create(osrm + "/route/v1/driving/" + coords + "?overview=false")));
            raw.set("osrm", route);
            if (!route.path("code").asText().equals("Ok")) throw new IllegalStateException(route.path("code").asText());
            double sum = 0;
            for (JsonNode leg : route.path("routes").get(0).path("legs")) sum += leg.path("duration").asDouble();
            hopSeconds = sum / (block.size() - 1); roadSource = "OBSERVED";
        } catch (Exception e) {
            double sum = 0;
            for (int i = 1; i < block.size(); i++) sum += meters(block.get(i - 1), block.get(i));
            hopSeconds = sum / (block.size() - 1) * DETOUR / (FALLBACK_KMH * 1000 / 3600); roadSource = "ESTIMATED";
            region.put("roadFallbackReason", String.valueOf(e.getMessage()));
        }
        int hop = Math.max(1, (int) Math.round(hopSeconds / 60.0 + ASSUMED_STOP_MINUTES));
        int perBuilding = (int) Math.max(1, Math.round(households / block.size()));
        region.putObject("defaults").put("numBuildings", block.size()).put("residentsPerBuilding", perBuilding);
        region.putObject("modelOverrides").put("routeTravelMinutes", hop);

        var prov = region.putObject("provenance");
        prov.putObject("numBuildings").put("grade", "OBSERVED").put("basis", "블록 안 OSM 주거 건물 수");
        prov.putObject("residentsPerBuilding").put("grade", "ESTIMATED")
                .put("basis", "건물 바닥면적 × 층수 ÷ 유형별 세대당 면적의 평균. 층수 태그 " + observedLevels + "/" + block.size() + "동 실측");
        prov.putObject("routeTravelMinutes").put("grade", roadSource)
                .put("basis", "블록 순회 구간 평균 도로 이동 " + round(hopSeconds / 60, 2) + "분(" + ("OBSERVED".equals(roadSource) ? "OSRM" : "직선거리×1.4, 20km/h")
                        + ") + 정차·상차 가정 " + ASSUMED_STOP_MINUTES + "분(ASSUMED)");
        prov.putObject("dischargeWindow").put("grade", "ASSUMED").put("basis", "지역 배출 시간대는 자동 수집하지 않아 공통 기본값");
        prov.putObject("buildingUse").put("grade", assumedUse == 0 ? "OBSERVED" : "ASSUMED")
                .put("basis", assumedUse == 0 ? "OSM building 태그" : assumedUse + "동은 용도 미상(building=yes)을 주거로 가정");
        boolean partial = !"OBSERVED".equals(roadSource) || assumedUse > 0;
        region.put("dataGrade", partial ? "PARTIAL_BLOCK" : "REAL_BLOCK");
        region.putObject("block").put("roadHopMinutes", round(hopSeconds / 60, 2)).put("assumedStopMinutes", ASSUMED_STOP_MINUTES)
                .put("observedLevelShare", round((double) observedLevels / block.size(), 2)).set("buildings", rows);
        return region;
    }
    private static double round(double v, int digits) { double f = Math.pow(10, digits); return Math.round(v * f) / f; }

    /** 기본 HTTP: Nominatim 이용 정책에 따라 식별 가능한 User-Agent 를 보내고 호출 간 1초를 둔다. */
    public static Function<URI, String> httpClient(String userAgent) {
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        return new Function<>() {
            private long last;
            @Override public synchronized String apply(URI uri) {
                try {
                    // 공개 서버는 과부하 때 429·5xx 를 낸다. 간격을 늘려 세 번까지 다시 묻는다.
                    for (int attempt = 0; ; attempt++) {
                        long wait = last + (attempt == 0 ? 1000 : 2000L << attempt) - System.currentTimeMillis();
                        if (wait > 0) Thread.sleep(wait);
                        var res = client.send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(60))
                                .header("User-Agent", userAgent).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        last = System.currentTimeMillis();
                        if (res.statusCode() == 200) return res.body();
                        if (attempt >= 3 || res.statusCode() != 429 && res.statusCode() < 500)
                            throw new IllegalStateException(uri.getHost() + " HTTP " + res.statusCode());
                    }
                } catch (java.io.IOException e) { throw new IllegalStateException(uri.getHost() + " 연결 실패: " + e.getMessage(), e); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("중단됨", e); }
            }
        };
    }
}
