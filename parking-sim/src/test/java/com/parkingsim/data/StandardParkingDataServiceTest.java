package com.parkingsim.data;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StandardParkingDataServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ParkingDataService snapshots = new ParkingDataService(new ParkingApiClient("", mapper));
    int requests;
    @Test void prefersNewerAgencyVersionAndRejectsAmbiguousSameDate() {
        var a = (com.fasterxml.jackson.databind.node.ObjectNode) row("a", "공영", "노외", "30");
        var b = a.deepCopy().put("referenceDate", "2026-10-01").put("prkcmprt", "40");
        var selected = StandardParkingDataService.latestVersions(List.of(a, b));
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).path("prkcmprt").asText()).isEqualTo("40");
        assertThat(selected.get(0).path("_versionNote").asText()).contains("최신");
        assertThatThrownBy(() -> StandardParkingDataService.latestVersions(List.of(b, b.deepCopy().put("prkcmprt", "50"))))
                .hasMessageContaining("충돌");
    }
    StandardParkingDataService service() {
        return new StandardParkingDataService(new StandardParkingApiClient("test", mapper) {
            public List<JsonNode> fetch(String institution) {
                requests++;
                return List.of(row("a", "공영", "노외", "30"), row("b", "공영", "노상", "30"),
                        row("c", "민영", "노외", "40"), row("d", "공영", "노외", ""));
            }
        }, snapshots);
    }
    JsonNode row(String id, String category, String type, String spaces) {
        return mapper.createObjectNode().put("prkplceNo", id).put("prkplceSe", category).put("prkplceType", type)
                .put("prkcmprt", spaces).put("referenceDate", "2026-09-01").put("basicCharge", "500")
                .put("weekdayOperColseHhmm", "18:00").put("rdnmadr", "").put("lnmadr", "포항시 지번주소");
    }
    @Test void preservesUnknownAvailabilityAndFiltersNonOffStreetAndPrivate() {
        var data = service();
        var json = mapper.valueToTree(data.fetch("포항시", List.of(), Map.of(), null));
        assertThat(json.path("candidates")).hasSize(2);
        assertThat(json.path("candidates").get(0).path("availableSpaces").isNull()).isTrue();
        assertThat(json.path("candidates").get(0).path("address").asText()).isEqualTo("포항시 지번주소");
        assertThat(json.path("candidates").get(1).path("usable").asBoolean()).isFalse();
        assertThat(json.path("realtimeAvailable").asBoolean()).isFalse();
        assertThatThrownBy(() -> data.fetch("포항시", List.of("a"), Map.of(), "18:30")).hasMessageContaining("초기 빈자리");
        assertThatThrownBy(() -> data.fetch("포항시", List.of("a"), Map.of("a", 31), "18:30")).hasMessageContaining("범위");
        assertThatThrownBy(() -> data.fetch("포항시", List.of("b"), Map.of("b", 0), "18:30")).hasMessageContaining("유효한");
    }
    @Test void selectedCapacityAndAssumptionsAreFrozenWithReferenceMetadata() {
        var data = service();
        var selection = new HashMap<>(Map.of("a", 10));
        var snapshot = (ParkingSnapshot) data.fetch("포항시", List.of("a"), selection, "18:30").get("snapshot");
        selection.put("a", 30);
        assertThat(snapshot.lots().get(0).occupied()).isEqualTo(20);
        assertThat(snapshot.occupancySource()).isEqualTo("USER_ASSUMED");
        assertThat(snapshot.facilityDetails().get("a")).containsEntry("referenceDate", "2026-09-01")
                .containsEntry("weekdayOperColseHhmm", "18:00");
        assertThatThrownBy(() -> snapshot.facilityDetails().get("a").put("basicCharge", "0"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshots.require(snapshot.snapshotId())).isEqualTo(snapshot);
        var second = (ParkingSnapshot) data.fetch("포항시", List.of("a"), Map.of("a", 30), "18:30").get("snapshot");
        assertThat(second.fetchedAt()).isEqualTo(snapshot.fetchedAt());
        assertThat(requests).isEqualTo(1);
    }
    @Test void startsAtTheUserAssumedTimeAndDoesNotExpire() {
        var data = service();
        assertThatThrownBy(() -> data.fetch("포항시", List.of("a"), Map.of("a", 10), null)).hasMessageContaining("initialStateTime");
        assertThatThrownBy(() -> data.fetch("포항시", List.of("a"), Map.of("a", 10), "25:00")).hasMessageContaining("HH:mm");
        var snapshot = (ParkingSnapshot) data.fetch("포항시", List.of("a"), Map.of("a", 10), "18:30").get("snapshot");
        assertThat(snapshot.startMinute()).as("조회 시각이 아니라 사용자가 정한 시각").isEqualTo(18 * 60 + 30);
        var old = new ParkingSnapshot("standard-old", StandardParkingApiClient.SOURCE, Instant.now().minusSeconds(3600).toString(),
                "", snapshot.lots(), "USER_ASSUMED", Map.of(), 600);
        snapshots.save(old);
        assertThat(snapshots.require("standard-old")).as("정적 시설정보와 사용자 가정은 10분 만료를 받지 않는다").isEqualTo(old);
    }
    @Test void unknownInstitutionSaysTheNameMustMatchExactly() {
        var data = new StandardParkingDataService(new StandardParkingApiClient("test", mapper) {
            public List<JsonNode> fetch(String institution) { return List.of(); }
        }, snapshots);
        var json = mapper.valueToTree(data.fetch("포항 북구", List.of(), Map.of(), null));
        assertThat(json.path("emptyNote").asText()).contains("포항 북구", "정확히");
    }
}
