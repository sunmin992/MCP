package com.wastesim.traffic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TrafficZoneRegistry}는 "어떤 구역 이름이 실재하는가"에만 답한다.
 *
 * <p>그 하나가 필요한 이유는 수거 지점의 {@code trafficZone} 매핑 때문이다. 없는 구역을
 * 가리키는 매핑은 조용히 전역 가중치로 떨어져 설정 오류가 정상 동작처럼 보인다.
 */
class TrafficZoneRegistryTest {

    @Test
    void loadsTheFourMeasuredZones() {
        TrafficZoneRegistry reg = TrafficZoneRegistry.ofDefault();

        assertEquals(4, reg.size());
        assertEquals(List.of("Node_A", "Node_B", "Node_C", "Node_D"), List.copyOf(reg.zoneIds()));
    }

    @Test
    void answersWhetherAZoneExists() {
        TrafficZoneRegistry reg = TrafficZoneRegistry.ofDefault();

        assertTrue(reg.isKnownZone("Node_A"));
        assertFalse(reg.isKnownZone("Node_Q"));
        assertFalse(reg.isKnownZone(null));
        assertFalse(reg.isKnownZone(""));
    }

    /**
     * 구역 이름은 혼잡 가중치 표의 키와 같아야 한다. 어긋나면 그 구역을 가리킨 수거 지점이
     * 구역별 가중치 대신 전역 값을 받게 되는데, 그건 조용히 일어난다.
     */
    @Test
    void zoneIdsMatchTheCongestionTableKeys() throws Exception {
        TrafficZoneRegistry zones = TrafficZoneRegistry.ofDefault();
        com.wastesim.model.TrafficProfile profile;
        try (var in = getClass().getResourceAsStream("/traffic/jangryang-weekday.json")) {
            assertNotNull(in);
            profile = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(in, com.wastesim.model.TrafficProfile.class);
        }

        assertEquals(zones.zoneIds(), profile.getNodeHourlyWeight().keySet(),
                "구역 목록과 혼잡 가중치 표의 키가 같아야 합니다.");
    }

    @Test
    void rejectsMissingResource() {
        TrafficZoneRegistry reg = new TrafficZoneRegistry("/traffic/없는-파일.json");
        IllegalStateException e = assertThrows(IllegalStateException.class, reg::load);
        assertTrue(e.getMessage().contains("클래스패스에 없습니다"), e.getMessage());
    }
}
