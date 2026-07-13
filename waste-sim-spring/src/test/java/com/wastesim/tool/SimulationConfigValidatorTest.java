package com.wastesim.tool;

import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulationConfigValidatorTest {

    private final SimulationConfigValidator v = new SimulationConfigValidator();

    @Test
    void validConfigPasses() {              // UT-20
        assertTrue(v.validate(new SimulationConfig()).ready());
    }

    @Test
    void outOfRangeFails() {                 // UT-21
        SimulationConfig c = new SimulationConfig();
        c.setDays(0);
        c.setSeeds(999);
        c.setThreshold(2.0);
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.field().equals("days")));
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.OUT_OF_RANGE));
    }

    @Test
    void unknownOccupationFails() {          // UT-22
        SimulationConfig c = new SimulationConfig();
        c.setOccupationMix(List.of("Ghost"));
        ValidationResult r = v.validate(c);
        assertFalse(r.ready());
        assertEquals(ErrorCode.INVALID_ENUM, r.errors().get(0).code());
    }
}
