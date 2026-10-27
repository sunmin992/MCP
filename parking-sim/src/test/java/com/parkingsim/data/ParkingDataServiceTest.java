package com.parkingsim.data;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ParkingDataServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ParkingDataService service() {
        return new ParkingDataService(new ParkingApiClient("dummy", mapper) {
            public List<JsonNode> fetch(String dataset, String region) {
                if (dataset.equals("PrkRealtimeInfo")) return List.of(
                        mapper.createObjectNode().put("prk_center_id", "a").put("pkfc_ParkingLots_total", "30")
                                .put("pkfc_Available_ParkingLots_total", "0"));
                return List.of(
                        mapper.createObjectNode().put("prk_center_id", "a").put("prk_manage_agent", "1").put("prk_cmprt_co", "25"),
                        mapper.createObjectNode().put("prk_center_id", "b").put("prk_manage_agent", "1").put("prk_cmprt_co", "40"),
                        mapper.createObjectNode().put("prk_center_id", "c").put("prk_manage_agent", "2").put("prk_cmprt_co", "40"));
            }
        });
    }
    @Test void joinsByIdFiltersPrivateAndDistinguishesMissingFromFull() {
        var data = service();
        var result = mapper.valueToTree(data.fetch("47113", List.of()));
        assertThat(result.path("candidates")).hasSize(2);
        assertThat(result.path("candidates").get(0).path("usable").asBoolean()).isTrue();
        assertThat(result.path("candidates").get(0).path("capacityMismatch").asBoolean()).isTrue();
        assertThat(result.path("candidates").get(1).path("usable").asBoolean()).isFalse();
        assertThatThrownBy(() -> data.fetch("47113", List.of("b"))).hasMessageContaining("유효하지");
        assertThatThrownBy(() -> data.fetch("47113", List.of("c"))).hasMessageContaining("유효하지");
        var snapshot = (ParkingSnapshot) data.fetch("47113", List.of("a")).get("snapshot");
        assertThat(data.require(snapshot.snapshotId()).lots().get(0).occupied()).isEqualTo(30);
    }
    @Test void expiresInputButKeepsSnapshotImmutable() {
        var data = service();
        data.save(new ParkingSnapshot("old", ParkingApiClient.SOURCE, Instant.now().minusSeconds(601).toString(), "47113", List.of()));
        assertThatThrownBy(() -> data.require("old")).hasMessageContaining("10분");
    }
}
