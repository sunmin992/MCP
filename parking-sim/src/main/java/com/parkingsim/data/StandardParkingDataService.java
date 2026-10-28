package com.parkingsim.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class StandardParkingDataService {
    private final StandardParkingApiClient api;
    private final ParkingDataService snapshots;
    private final Clock clock;
    private record Fetched(Instant at, List<JsonNode> rows) {}
    private final Map<String, Fetched> cache = new LinkedHashMap<>();
    private static final List<String> FIELDS = List.of("prkplceNo", "prkplceNm", "prkplceSe", "prkplceType",
            "rdnmadr", "lnmadr", "prkcmprt", "latitude", "longitude", "institutionNm", "referenceDate",
            "operDay", "weekdayOperOpenHhmm", "weekdayOperColseHhmm", "satOperOperOpenHhmm", "satOperCloseHhmm",
            "holidayOperOpenHhmm", "holidayCloseOpenHhmm", "parkingchrgeInfo", "basicTime", "basicCharge",
            "addUnitTime", "addUnitCharge", "dayCmmtktAdjTime", "dayCmmtkt", "monthCmmtkt", "spcmnt", "insttCode", "insttNm", "_versionNote");
    @org.springframework.beans.factory.annotation.Autowired
    public StandardParkingDataService(StandardParkingApiClient api, ParkingDataService snapshots) {
        this(api, snapshots, Clock.systemUTC());
    }
    StandardParkingDataService(StandardParkingApiClient api, ParkingDataService snapshots, Clock clock) {
        this.api = api; this.snapshots = snapshots; this.clock = clock;
    }
    private synchronized Fetched load(String institution) {
        var hit = cache.get(institution);
        if (hit != null && Duration.between(hit.at(), clock.instant()).compareTo(Duration.ofMinutes(5)) < 0) return hit;
        var rows = latestVersions(api.fetch(institution));
        var fetched = new Fetched(clock.instant(), rows);
        cache.put(institution, fetched);
        while (cache.size() > 30) cache.remove(cache.keySet().iterator().next());
        return fetched;
    }
    /** Aggregated public data can contain agency versions of the same management number. */
    static List<JsonNode> latestVersions(List<JsonNode> rows) {
        var chosen = new LinkedHashMap<String, JsonNode>();
        for (var row : rows) {
            String id = row.path("prkplceNo").asText();
            var previous = chosen.get(id);
            if (previous == null) { chosen.put(id, row.deepCopy()); continue; }
            java.time.LocalDate before, after;
            try {
                before = java.time.LocalDate.parse(previous.path("referenceDate").asText());
                after = java.time.LocalDate.parse(row.path("referenceDate").asText());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("중복 관리번호의 데이터 기준일을 비교할 수 없습니다: " + id);
            }
            var oldClean = previous.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) oldClean).remove("_versionNote");
            if (before.equals(after) && !oldClean.equals(row))
                throw new IllegalArgumentException("같은 기준일의 중복 관리번호 정보가 충돌합니다: " + id);
            var picked = (com.fasterxml.jackson.databind.node.ObjectNode) (after.isAfter(before) ? row : previous).deepCopy();
            picked.put("_versionNote", "같은 관리번호의 제공기관별 중복 자료 중 데이터 기준일이 최신인 자료를 적용했습니다.");
            chosen.put(id, picked);
        }
        return List.copyOf(chosen.values());
    }
    public Map<String, Object> fetch(String institution, List<String> selected, Map<String, Integer> initialAvailable,
                                     String initialStateTime) {
        if (selected.size() > 12 || new HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("중복 없이 주차장 1~12곳을 선택하십시오.");
        if (!initialAvailable.keySet().equals(new HashSet<>(selected)))
            throw new IllegalArgumentException("선택한 모든 관리번호에 initialAvailableSpaces(초기 빈자리 가정)를 지정하십시오. 표준데이터에는 잔여면수가 없습니다.");
        Integer startMinute = null;
        if (!selected.isEmpty()) {
            try {
                var time = java.time.LocalTime.parse(initialStateTime == null ? "" : initialStateTime,
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                startMinute = time.getHour() * 60 + time.getMinute();
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("initialStateTime(초기 빈자리를 가정한 시각, 한국 시간 HH:mm)을 사용자에게 물어 지정하십시오. 시뮬레이션은 그 시각에서 시작합니다.");
            }
        }
        var fetched = load(institution);
        var valid = new LinkedHashMap<String, JsonNode>();
        var candidates = new ArrayList<Map<String, Object>>();
        for (var row : fetched.rows()) {
            if (!row.path("prkplceSe").asText().equals("공영") || !row.path("prkplceType").asText().equals("노외")) continue;
            var item = new LinkedHashMap<String, Object>();
            var details = details(row);
            item.put("facilityDetails", details);
            String id = row.path("prkplceNo").asText();
            item.put("id", id); item.put("name", row.path("prkplceNm").asText());
            item.put("address", address(row)); item.put("availableSpaces", null);
            item.put("occupancySource", "NOT_PROVIDED");
            try {
                int total = ParkingApiClient.integer(row, "prkcmprt");
                if (total < 1 || total > 10000) throw new IllegalArgumentException("면수는 1~10000이어야 합니다.");
                if (id.isBlank() || valid.containsKey(id)) throw new IllegalArgumentException("관리번호 누락·중복");
                item.put("totalSpaces", total); item.put("usable", true);
                valid.put(id, row);
            } catch (IllegalArgumentException e) { item.put("usable", false); item.put("reason", e.getMessage()); }
            candidates.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("source", StandardParkingApiClient.SOURCE); result.put("fetchedAt", fetched.at().toString());
        result.put("candidates", candidates); result.put("realtimeAvailable", false);
        result.put("note", "공영·노외만 제공합니다. 면수는 표준데이터, 초기 빈자리는 사용자 가정입니다. 요금·운영시간·좌표는 참고용이며 엔진에 자동 적용하지 않습니다. Parking_v1 관리번호와 자동 결합하지 않습니다.");
        if (candidates.isEmpty()) result.put("emptyNote", "이 관리기관명(" + institution + ")으로 등록된 공영·노외 주차장이 없습니다. "
                + "관리기관명은 정확히 일치해야 합니다(예: 포항시시설관리공단. 제공기관명 insttNm 과 다를 수 있습니다). 사용자에게 확인하거나 다른 이름으로 다시 조회하십시오.");
        if (!selected.isEmpty()) {
            var lots = new ArrayList<ParkingSnapshot.Lot>();
            var metadata = new TreeMap<String, Map<String, String>>();
            for (String id : selected) {
                JsonNode row = valid.get(id);
                if (row == null) throw new IllegalArgumentException("선택한 공영·노외 주차장의 유효한 시설정보가 없습니다: " + id);
                int total = ParkingApiClient.integer(row, "prkcmprt");
                Integer available = initialAvailable.get(id);
                if (available == null || available < 0 || available > total) throw new IllegalArgumentException("초기 빈자리 가정이 총면수 범위를 벗어났습니다: " + id);
                lots.add(new ParkingSnapshot.Lot(id, row.path("prkplceNm").asText(), address(row),
                        row.path("latitude").asText(), row.path("longitude").asText(), total, total, available));
                metadata.put(id, details(row));
            }
            var snapshot = new ParkingSnapshot("standard-" + UUID.randomUUID(), StandardParkingApiClient.SOURCE,
                    fetched.at().toString(), "", lots, "USER_ASSUMED", metadata, startMinute);
            snapshots.save(snapshot);
            result.put("snapshot", snapshot);
            result.put("nextStep", "snapshotId를 plan_subtasks·build_scenario의 parkingSnapshotId로 전달하십시오. initialVehicleRemainingMinutes는 별도 가정으로 지정하고 lotCount·spacesPerLot는 생략하십시오.");
        }
        return result;
    }
    private static String address(JsonNode row) {
        String road = row.path("rdnmadr").asText("");
        return road.isBlank() ? row.path("lnmadr").asText("") : road;
    }
    private static Map<String, String> details(JsonNode row) {
        var result = new TreeMap<String, String>();
        FIELDS.forEach(field -> result.put(field, row.path(field).asText("")));
        return result;
    }
}
