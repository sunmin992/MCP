package com.parkingsim.data;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.*;

class ParkingDataServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    /** 데이터 종류마다 받은 순서를 적는다 — 실시간을 먼저 보는지, 시설을 다 받지 않고 멈추는지 시험한다. */
    private final List<String> calls = new ArrayList<>();

    /** 시험이 앞으로 돌리는 시계. */
    private Instant now = Instant.parse("2026-10-27T10:00:00Z");
    private final java.time.Clock clock = new java.time.Clock() {
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    };

    private ParkingDataService service(List<JsonNode> realtime, List<JsonNode> facilities) {
        return new ParkingDataService(new ParkingApiClient("dummy", mapper) {
            public List<JsonNode> fetch(String dataset, String region, Predicate<List<JsonNode>> enough) {
                calls.add(dataset);
                if (dataset.equals("PrkRealtimeInfo")) return realtime;
                // 한 쪽에 한 행씩 주며, 충분하면 멈추는 것까지 흉내 낸다.
                List<JsonNode> got = new ArrayList<>();
                for (JsonNode f : facilities) {
                    got.add(f);
                    calls.add("page");
                    if (enough.test(got)) break;
                }
                return got;
            }
        }, clock);
    }

    private JsonNode rt(String id, int total, int available) {
        return mapper.createObjectNode().put("prk_center_id", id).put("pkfc_ParkingLots_total", String.valueOf(total))
                .put("pkfc_Available_ParkingLots_total", String.valueOf(available));
    }

    private JsonNode facility(String id, String agent, int spaces) {
        return mapper.createObjectNode().put("prk_center_id", id).put("prk_manage_agent", agent)
                .put("prk_cmprt_co", String.valueOf(spaces));
    }

    private ParkingDataService standard() {
        return service(List.of(rt("a", 30, 0)),
                List.of(facility("a", "1", 25), facility("b", "1", 40), facility("c", "2", 40)));
    }

    @Test void joinsByIdFiltersPrivateAndListsOnlyLotsWithRealtime() {
        var data = standard();
        var result = mapper.valueToTree(data.fetch("47113", List.of()));
        assertThat(result.path("realtimeAvailable").asBoolean()).isTrue();
        assertThat(result.path("candidates")).hasSize(1);
        assertThat(result.path("candidates").get(0).path("id").asText()).isEqualTo("a");
        assertThat(result.path("candidates").get(0).path("usable").asBoolean()).isTrue();
        assertThat(result.path("candidates").get(0).path("capacityMismatch").asBoolean()).isTrue();
        assertThatThrownBy(() -> data.fetch("47113", List.of("b"))).hasMessageContaining("유효하지");
        assertThatThrownBy(() -> data.fetch("47113", List.of("c"))).hasMessageContaining("유효하지");
        var snapshot = (ParkingSnapshot) data.fetch("47113", List.of("a")).get("snapshot");
        assertThat(data.require(snapshot.snapshotId()).lots().get(0).occupied()).isEqualTo(30);
    }

    @Test void readsRealtimeFirstAndStopsScanningFacilitiesOnceAllRealtimeLotsAreFound() {
        standard().fetch("47113", List.of());
        assertThat(calls.get(0)).isEqualTo("PrkRealtimeInfo");
        assertThat(calls.stream().filter("page"::equals).count()).as("a 를 찾은 첫 쪽에서 멈춘다").isEqualTo(1);
    }

    @Test void regionWithoutRealtimeSaysSoWithoutFetchingFacilities() {
        var data = service(List.of(), List.of(facility("a", "1", 25)));
        var result = mapper.valueToTree(data.fetch("47113", List.of()));
        assertThat(result.path("realtimeAvailable").asBoolean()).isFalse();
        assertThat(result.path("candidates")).isEmpty();
        assertThat(result.path("note").asText()).contains("실시간", "47113");
        assertThat(calls).containsExactly("PrkRealtimeInfo");
        assertThatThrownBy(() -> data.fetch("47113", List.of("a"))).hasMessageContaining("실시간");
    }

    @Test void listThenSelectInTheSameRegionFetchesOnceAndKeepsTheFetchTime() {
        var data = standard();
        data.fetch("47113", List.of());
        now = now.plusSeconds(120);
        var snapshot = (ParkingSnapshot) data.fetch("47113", List.of("a")).get("snapshot");
        assertThat(calls.stream().filter("PrkRealtimeInfo"::equals).count()).as("고르는 호출은 다시 받지 않는다").isEqualTo(1);
        assertThat(snapshot.fetchedAt()).as("캐시에서 꺼낸 값을 새것처럼 적지 않는다").isEqualTo("2026-10-27T10:00:00Z");

        now = now.plus(ParkingDataService.CACHE_TTL);
        data.fetch("47113", List.of());
        assertThat(calls.stream().filter("PrkRealtimeInfo"::equals).count()).as("5분이 지나면 다시 받는다").isEqualTo(2);
        data.fetch("11110", List.of());
        assertThat(calls.stream().filter("PrkRealtimeInfo"::equals).count()).as("다른 지역은 따로 받는다").isEqualTo(3);
    }

    @Test void expiresInputButKeepsSnapshotImmutable() {
        var data = standard();
        data.save(new ParkingSnapshot("old", ParkingApiClient.SOURCE, now.minusSeconds(601).toString(), "47113", List.of()));
        assertThatThrownBy(() -> data.require("old")).hasMessageContaining("10분");
    }
}
