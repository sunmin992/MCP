package com.wastesim.template;

import com.wastesim.readiness.NumericEvidence;
import com.wastesim.readiness.TaskCompletion;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessRegressionTest {
    @Test void countsCannotUseTimeOrPercentConversions() {
        assertFalse(NumericEvidence.explains(3, 180, "3회 반복", "seeds", null));
        assertFalse(NumericEvidence.explains(3, 180, "3시간", "truckCount", null));
        assertFalse(NumericEvidence.explains(3, 21, "3주", "seeds", null));
        assertFalse(NumericEvidence.explains(80, .8, "80%", "seeds", null));
        assertFalse(NumericEvidence.explains(80, .8, "80%", "arrivalRate", "people/hour"));
        assertTrue(NumericEvidence.explains(30, .3, "30%", "reservationShare", null));
        assertTrue(NumericEvidence.explains(3, 3, "3회 반복", "seeds", null));
        assertTrue(NumericEvidence.explains(3, 180, "3시간", "dispatchIntervalMinutes", "minute"));
        assertFalse(NumericEvidence.explains(3, 3, "3시간", "dispatchIntervalMinutes", "minute"));
        assertFalse(NumericEvidence.explains(6, 1080, "오후 6시 30분", "collectionTimeMinutes", "minute"));
        assertTrue(NumericEvidence.explains(6, 1110, "오후 6시 30분", "collectionTimeMinutes", "minute"));
        assertTrue(NumericEvidence.explains(6, 1080, "오후 6시", "collectionTimeMinutes", "minute"));
        assertTrue(NumericEvidence.explains(12, 720, "오후 12시", "collectionTimeMinutes", "minute"));
        assertTrue(NumericEvidence.explains(2, 14, "2주", "days", null));
        assertTrue(NumericEvidence.explains(80, .8, "80%", "complaintThreshold", null));
    }
    @Test void theOriginalSeeds180BugIsBlockedByTheRealGuard() {
        var r = EvidenceGuard.check(Map.of("seeds", List.of(180)), Map.of("seeds", "USER"),
                Map.of("seeds", "3회 반복"), List.of("3회 반복"), new TemplateCatalog());
        assertFalse(r.blocks().isEmpty());
    }
    @Test void filledFilePathDoesNotMeanValidatedArtifact() {
        var tasks = List.of(
                new TaskCompletion.Task("network", List.of(), List.of("file"), () -> List.of("연결성 검사 실패")),
                new TaskCompletion.Task("demand", List.of("network"), List.of("demand"),
                        () -> { fail("선행 작업 실패 시 실행되면 안 됨"); return List.of(); }));
        var report = TaskCompletion.evaluate(tasks, Map.of("file", "FILLED", "demand", "FILLED"));
        assertFalse(report.ready());
        assertEquals("INVALID", report.tasks().get(0).status());
        assertEquals("BLOCKED", report.tasks().get(1).status());
    }
    @Test void requiredMissingAndUnknownSubtasksBlockButInactiveOnesDoNot() {
        var task = new TaskCompletion.Task("task", List.of(), List.of("a", "b"), List::of);
        assertTrue(TaskCompletion.evaluate(List.of(task), Map.of("a", "FILLED", "b", "NOT_GENERATED")).ready());
        assertFalse(TaskCompletion.evaluate(List.of(task), Map.of("a", "FILLED", "b", "DEFERRED")).ready());
        assertFalse(TaskCompletion.evaluate(List.of(task), Map.of("a", "FILLED")).ready());
    }
    @Test void malformedTaskGraphAndValidatorFailureCannotPass() {
        var a = new TaskCompletion.Task("a", List.of("b"), List.of(), List::of);
        var b = new TaskCompletion.Task("b", List.of("a"), List.of(), List::of);
        assertThrows(IllegalArgumentException.class, () -> TaskCompletion.evaluate(List.of(a, b), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> TaskCompletion.evaluate(List.of(a), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> TaskCompletion.evaluate(List.of(a, a), Map.of()));
        var error = new TaskCompletion.Task("error", List.of(), List.of(), () -> { throw new IllegalStateException(); });
        assertFalse(TaskCompletion.evaluate(List.of(error), Map.of()).ready());
    }
}
