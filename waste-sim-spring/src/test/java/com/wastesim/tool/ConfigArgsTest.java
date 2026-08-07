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
}
