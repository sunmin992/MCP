package com.wastesim.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigArgsTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void mapsCollectionTimeToMinutes() throws Exception {   // UT-23
        SimulationConfig c = ConfigArgs.fromJson(om.readTree("{\"collectionTime\":\"08:30\",\"days\":10}"));
        assertEquals(510, c.getCollectionTimeMinutes());
        assertEquals(10, c.getDays());
    }

    @Test
    void emptyArgsUsesDefaults() throws Exception {
        SimulationConfig c = ConfigArgs.fromJson(om.readTree("{}"));
        assertEquals(720, c.getCollectionTimeMinutes()); // 12:00 기본값
    }

    @Test
    void mapsTrafficFields() throws Exception {
        SimulationConfig c = ConfigArgs.fromJson(om.readTree(
                "{\"trafficEnabled\":true,\"trafficProfileId\":\"jangryang-weekday\"," +
                "\"truckType\":\"SMALL_1TON\",\"truckCount\":3,\"dispatchIntervalMinutes\":45," +
                "\"routeAvailableCapacityKg\":800,\"initialTruckLoadKg\":200," +
                "\"routeSequence\":[\"Node_A\",\"Node_C\",\"Node_B\"]}"));
        assertTrue(c.isTrafficEnabled());
        assertEquals("jangryang-weekday", c.getTrafficProfileId());
        assertEquals("SMALL_1TON", c.getTruckType());
        assertEquals(3, c.getTruckCount());
        assertEquals(45, c.getDispatchIntervalMinutes());
        assertEquals(800.0, c.getRouteAvailableCapacityKg());
        assertEquals(200.0, c.getInitialTruckLoadKg());
        assertEquals(List.of("Node_A", "Node_C", "Node_B"), c.getRouteSequence());
    }

    @Test
    void mapsScheduleDischargeAndTravelModesExposedByMcp() throws Exception {
        SimulationConfig c = ConfigArgs.fromJson(om.readTree("""
                {
                  "collectionDaysOfWeek": [0, 1, 3, 4],
                  "dischargeTimeMode": "POHANG_ACTUAL",
                  "dischargeWindowStartMinutes": 1200,
                  "dischargeWindowEndMinutes": 360,
                  "travelTimeMode": "ZONE_PROXY_HYBRID",
                  "serviceMinutesPerSite": 5,
                  "intraZoneTravelMinutes": 3
                }
                """));

        assertEquals(List.of(0, 1, 3, 4), c.getCollectionDaysOfWeek());
        assertEquals("POHANG_ACTUAL", c.getDischargeTimeMode());
        assertEquals(1200, c.getDischargeWindowStartMinutes());
        assertEquals(360, c.getDischargeWindowEndMinutes());
        assertEquals("ZONE_PROXY_HYBRID", c.getTravelTimeMode());
        assertEquals(5, c.getServiceMinutesPerSite());
        assertEquals(3, c.getIntraZoneTravelMinutes());
    }
}
