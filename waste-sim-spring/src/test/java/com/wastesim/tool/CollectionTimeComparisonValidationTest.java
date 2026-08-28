package com.wastesim.tool;

import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.ScenarioService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** WC-05·17·18 — 비교 축이 잘못되면 시뮬레이션을 시작하지 않는다. */
class CollectionTimeComparisonValidationTest {

    private final SimulationTool tool = new SimulationTool(
            mock(SimulationConfigValidator.class), mock(SimulationModelRegistry.class),
            mock(ScenarioService.class), new SimpleMeterRegistry());

    @Test
    void rejectsMissingOrSingleCandidate() {
        assertRejected(tool.compareCollectionTimes(new SimulationConfig(), null), "times");
        assertRejected(tool.compareCollectionTimes(new SimulationConfig(), List.of()), "times");
        assertRejected(tool.compareCollectionTimes(new SimulationConfig(), List.of(600)), "times");
    }

    @Test
    void rejectsDuplicateAndOutOfRangeCandidate() {
        assertRejected(tool.compareCollectionTimes(new SimulationConfig(), List.of(600, 600)), "times");
        assertRejected(tool.compareCollectionTimes(new SimulationConfig(), List.of(600, 1440)), "times");
    }

    @Test
    void rejectsTooManyCandidates() {
        List<Integer> times = new ArrayList<>();
        for (int i = 0; i <= SimulationTool.MAX_COLLECTION_COMPARISON_TIMES; i++) times.add(i);
        assertRejected(tool.compareCollectionTimes(new SimulationConfig(), times), "times");
    }

    private static void assertRejected(ToolResult result, String field) {
        assertFalse(result.ready());
        assertEquals(field, result.errors().getFirst().field());
    }
}
