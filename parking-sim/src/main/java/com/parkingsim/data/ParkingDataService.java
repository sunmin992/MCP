package com.parkingsim.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class ParkingDataService {
    private final ParkingApiClient api;
    private final Map<String, ParkingSnapshot> snapshots = new LinkedHashMap<>();
    private final Clock clock;

    /**
     * 같은 지역을 이 시간 안에 다시 부르면 다시 받지 않는다. 목록을 본 뒤 고르는 두 번째 호출이 같은 수십 쪽을 또
     * 받으면 포털이 429 로 막는다(2026-10-27 AP-A1 · A2 · A3). 고른 주차장도 사용자가 본 목록과 같은 값이어야 한다.
     */
    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    /** {@code at} 은 실시간을 받은 시각 — 스냅샷의 조회 시각이 이것이다. 캐시에서 꺼냈다고 새것처럼 보이면 안 된다. */
    private record Fetched(Instant at, List<JsonNode> realtime, List<JsonNode> facilities) {}
    private final Map<String, Fetched> cache = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ParkingDataService(ParkingApiClient api) { this(api, Clock.systemUTC()); }
    ParkingDataService(ParkingApiClient api, Clock clock) { this.api = api; this.clock = clock; }

    // ponytail: 전역 잠금 — 한 지역을 받는 동안 다른 지역 조회도 기다린다. 동시 사용자가 늘면 지역별 잠금으로.
    private synchronized Fetched load(String region) {
        Fetched hit = cache.get(region);
        if (hit != null && Duration.between(hit.at(), clock.instant()).compareTo(CACHE_TTL) < 0) return hit;
        // 실시간을 먼저 본다. 실시간이 없는 지역(포항 북구는 0건, 2026-10-27 실측)이면 수천 건의 시설 목록을
        // 받아도 고를 주차장이 없다.
        var realtime = api.fetch("PrkRealtimeInfo", region);
        Instant at = clock.instant();
        List<JsonNode> facilities = List.of();
        if (!realtime.isEmpty()) {
            Set<String> ids = new HashSet<>();
            realtime.forEach(r -> ids.add(r.path("prk_center_id").asText()));
            // 시설 목록은 민영 · 부설까지 들어 있어 길다. 실시간 관리번호를 모두 찾으면 남은 쪽은 받지 않는다.
            facilities = api.fetch("PrkSttusInfo", region, rows ->
                    rows.stream().filter(f -> ids.contains(f.path("prk_center_id").asText())).count() >= ids.size());
        }
        Fetched fetched = new Fetched(at, realtime, facilities);
        cache.put(region, fetched);
        return fetched;
    }

    /** An empty selection only lists candidates. No lot is silently selected. */
    public Map<String, Object> fetch(String region, List<String> selected) {
        if (selected.size() > 12 || new HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("중복 없이 주차장 1~12곳을 선택하십시오.");
        Fetched fetched = load(region);
        var realtime = fetched.realtime();
        if (realtime.isEmpty()) {
            String why = "이 지역(" + region + ")은 실시간 주차 정보를 제공하지 않아 API 모드를 쓸 수 없습니다. "
                    + "가정값 모드로 실험하거나 실시간을 제공하는 다른 지역을 고르십시오.";
            if (!selected.isEmpty()) throw new IllegalArgumentException(why);
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("candidates", List.of());
            none.put("realtimeAvailable", false);
            none.put("source", ParkingApiClient.SOURCE);
            none.put("note", why);
            return none;
        }
        Map<String, JsonNode> byId = new HashMap<>();
        realtime.forEach(r -> byId.put(r.path("prk_center_id").asText(), r));
        var facilities = fetched.facilities();
        Map<String, ParkingSnapshot.Lot> valid = new LinkedHashMap<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode facility : facilities) {
            String id = facility.path("prk_center_id").asText();
            // 실시간이 없는 공영 주차장은 싣지 않는다 — 시설 목록을 끝까지 받지 않으므로 실어도 일부뿐이다.
            if (!"1".equals(facility.path("prk_manage_agent").asText()) || !byId.containsKey(id)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id); item.put("name", facility.path("prk_plce_nm").asText());
            item.put("address", facility.path("prk_plce_adres").asText());
            try {
                JsonNode rt = byId.get(id);
                if (rt == null) throw new IllegalArgumentException("실시간 정보 미제공");
                int total = ParkingApiClient.integer(rt, "pkfc_ParkingLots_total");
                int available = ParkingApiClient.integer(rt, "pkfc_Available_ParkingLots_total");
                int facilityTotal = ParkingApiClient.integer(facility, "prk_cmprt_co");
                if (total < 1 || total > 10000 || available > total)
                    throw new IllegalArgumentException("총면수·잔여면수가 실행 범위를 벗어났습니다");
                var lot = new ParkingSnapshot.Lot(id, facility.path("prk_plce_nm").asText(),
                        facility.path("prk_plce_adres").asText(), facility.path("prk_plce_entrc_la").asText(),
                        facility.path("prk_plce_entrc_lo").asText(), facilityTotal, total, available);
                valid.put(id, lot);
                item.put("totalSpaces", total); item.put("availableSpaces", available);
                item.put("facilitySpaces", facilityTotal);
                item.put("capacityMismatch", total != facilityTotal);
                item.put("usable", true);
            } catch (IllegalArgumentException e) {
                item.put("usable", false); item.put("reason", e.getMessage());
            }
            candidates.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", candidates);
        result.put("realtimeAvailable", true);
        result.put("realtimeLots", byId.size());
        result.put("listedNote", "실시간 정보가 있는 공영 주차장만 싣습니다. 실시간이 없는 공영 주차장과 민영 주차장은 고를 수 없어 뺐습니다.");
        result.put("source", ParkingApiClient.SOURCE);
        result.put("fetchedAt", fetched.at().toString());
        result.put("note", "관측 시각은 API에 없습니다. 노외 여부는 시설명·주소와 별도로 확인하십시오. 좌표는 참고용이며 실제 도로망·요금·운영시간은 적용하지 않습니다.");
        if (!selected.isEmpty()) {
            List<ParkingSnapshot.Lot> lots = new ArrayList<>();
            for (String id : selected) {
                if (!valid.containsKey(id)) throw new IllegalArgumentException("선택 주차장의 공영 시설·실시간 정보가 유효하지 않습니다: " + id);
                lots.add(valid.get(id));
            }
            var snapshot = new ParkingSnapshot("parking-" + UUID.randomUUID(), ParkingApiClient.SOURCE,
                    fetched.at().toString(), region, lots);
            save(snapshot);
            result.put("snapshot", snapshot);
            result.put("nextStep", "build_scenario 에 parkingSnapshotId와 initialVehicleRemainingMinutes(기존 차량의 잔여 체류시간 가정)를 넣으십시오. lotCount·spacesPerLot는 넣지 마십시오. 요금·수요·주차장 간 이동은 별도 실험 설정입니다.");
        }
        return result;
    }
    synchronized void save(ParkingSnapshot snapshot) {
        snapshots.put(snapshot.snapshotId(), snapshot);
        while (snapshots.size() > 100) snapshots.remove(snapshots.keySet().iterator().next());
    }
    public synchronized ParkingSnapshot require(String id) {
        var snapshot = snapshots.get(id);
        if (snapshot == null) throw new IllegalArgumentException("주차 스냅샷이 없습니다. fetch_parking_snapshot 으로 다시 조회하십시오.");
        if (Duration.between(Instant.parse(snapshot.fetchedAt()), clock.instant()).toMinutes() >= 10)
            throw new IllegalArgumentException("조회한 지 10분이 지난 스냅샷입니다. 다시 조회한 뒤 시나리오를 만드십시오.");
        return snapshot;
    }
}
