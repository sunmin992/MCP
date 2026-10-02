package com.wastesim.controller;

import com.wastesim.service.ScenarioService;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import com.wastesim.web.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

/** WS-04·05·07·08·10·18 — 스윕이 실행되기 전 축 자체를 fail-closed로 검증한다. */
class ScenarioSweepValidationTest {

    private final ScenarioController controller =
            new ScenarioController(mock(ScenarioService.class), mock(SimulationTool.class));

    @Test
    void rejectsZeroAndNegativeStepBeforeExecution() {
        assertBad("stepMinutes", Map.of("start", "06:00", "end", "18:00", "stepMinutes", 0));
        assertBad("stepMinutes", Map.of("start", "06:00", "end", "18:00", "stepMinutes", -30));
    }

    @Test
    void rejectsDescendingRangeAndInvalidClockText() {
        assertBad("start/end", Map.of("start", "18:00", "end", "06:00", "stepMinutes", 60));
        assertBad("start", Map.of("start", "25:00", "end", "18:00", "stepMinutes", 60));
    }

    @Test
    void rejectsExcessivePointCount() {
        assertBad("stepMinutes", Map.of("start", "00:00", "end", "23:59", "stepMinutes", 1));
    }

    @Test
    void rejectsWrongScalarTypesInsteadOfSilentlyUsingDefaultsOrTruncating() {
        assertBad("stepMinutes", Map.of("stepMinutes", 30.5));
        assertBad("stepMinutes", Map.of("stepMinutes", "30"));
        assertBad("days", Map.of("days", 7.5));
        assertBad("days", Map.of("days", "7"));
    }

    @Test
    void sameStartAndEndIsAValidSinglePointSweep() {
        ScenarioService scenario = mock(ScenarioService.class);
        SimulationTool tool = mock(SimulationTool.class);
        ScenarioController validController = new ScenarioController(scenario, tool);
        when(tool.runScenarioCustom(any(), any())).thenReturn(ToolResult.ok(
                new ScenarioResponse("COLLECTION_SWEEP", "단일 후보", "수거 시각")));

        ResponseEntity<?> response = validController.collectionSweep(Map.of(
                "start", "12:00", "end", "12:00", "stepMinutes", 60));
        assertEquals(200, response.getStatusCode().value());
    }

    private void assertBad(String field, Map<String, Object> body) {
        ScenarioController.ScenarioArgException error = assertThrows(
                ScenarioController.ScenarioArgException.class,
                () -> controller.collectionSweep(body));
        ResponseEntity<?> response = controller.badScenarioArg(error);
        assertEquals(400, response.getStatusCode().value());
        ApiError api = (ApiError) response.getBody();
        assertNotNull(api);
        assertEquals(field, api.errors().getFirst().field());
    }
}
