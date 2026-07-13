package com.wastesim.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
