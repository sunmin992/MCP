package com.parkingsim.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class ParkingDataService {
    private final ParkingApiClient api;
    private final Map<String, ParkingSnapshot> snapshots = new LinkedHashMap<>();
    public ParkingDataService(ParkingApiClient api) { this.api = api; }

    /** An empty selection only lists candidates. No lot is silently selected. */
    public Map<String, Object> fetch(String region, List<String> selected) {
        if (selected.size() > 12 || new HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("중복 없이 주차장 1~12곳을 선택하십시오.");
        var facilities = api.fetch("PrkSttusInfo", region);
        var realtime = api.fetch("PrkRealtimeInfo", region);
        Map<String, JsonNode> byId = new HashMap<>();
        realtime.forEach(r -> byId.put(r.path("prk_center_id").asText(), r));
        Map<String, ParkingSnapshot.Lot> valid = new LinkedHashMap<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode facility : facilities) {
            if (!"1".equals(facility.path("prk_manage_agent").asText())) continue;
            String id = facility.path("prk_center_id").asText();
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
        result.put("source", ParkingApiClient.SOURCE);
        result.put("note", "관측 시각은 API에 없습니다. 노외 여부는 시설명·주소와 별도로 확인하십시오. 좌표는 참고용이며 실제 도로망·요금·운영시간은 적용하지 않습니다.");
        if (!selected.isEmpty()) {
            List<ParkingSnapshot.Lot> lots = new ArrayList<>();
            for (String id : selected) {
                if (!valid.containsKey(id)) throw new IllegalArgumentException("선택 주차장의 공영 시설·실시간 정보가 유효하지 않습니다: " + id);
                lots.add(valid.get(id));
            }
            var snapshot = new ParkingSnapshot("parking-" + UUID.randomUUID(), ParkingApiClient.SOURCE,
                    Instant.now().toString(), region, lots);
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
        if (Duration.between(Instant.parse(snapshot.fetchedAt()), Instant.now()).toMinutes() >= 10)
            throw new IllegalArgumentException("조회한 지 10분이 지난 스냅샷입니다. 다시 조회한 뒤 시나리오를 만드십시오.");
        return snapshot;
    }
}
