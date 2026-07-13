package com.wastesim.tool;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.ScenarioService;
import com.wastesim.service.SimulationService;
import com.wastesim.simulation.SimulationEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationToolTest {

    private SimulationTool tool() {
        SimulationEngine engine = new SimulationEngine();
        SimulationService sim = new SimulationService(engine);
        ScenarioService sc = new ScenarioService(sim);
        return new SimulationTool(new SimulationConfigValidator(), sim, sc, new SimpleMeterRegistry());
    }

    /** 테스트 속도를 위해 작은 설정 */
    private SimulationConfig small() {
        SimulationConfig c = new SimulationConfig();
        c.setDays(2);
        c.setSeeds(2);
        return c;
    }

    @Test
    void runsValidSimulation() {             // UT-24
        ToolResult r = tool().runSimulation(small());
        assertTrue(r.ready());
        assertTrue(r.result() instanceof SimulationResult);
    }

    @Test
    void rejectsInvalidSimulation() {        // UT-25 (fail-closed)
        SimulationConfig c = small();
        c.setDays(0);
        ToolResult r = tool().runSimulation(c);
        assertFalse(r.ready());
        assertNull(r.result());
    }

    @Test
    void runsScenario() {
        ToolResult r = tool().runScenario("multi-truck", small());
        assertTrue(r.ready());
    }

    @Test
    void rejectsUnknownScenario() {          // UT-26
        ToolResult r = tool().runScenario("nope", small());
        assertFalse(r.ready());
        assertEquals(ErrorCode.INVALID_ENUM, r.errors().get(0).code());
    }
}
