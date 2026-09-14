package com.wastesim.subtask;

import com.wastesim.ledger.ValueSource;
import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.mcp.ParameterExpectation;
import com.wastesim.ledger.mcp.ToolCandidate;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalValueSessionTest {
    private final SubtaskSessionService sessions = SubtaskTestSupport.service();
    private static final String ID = JangnyangLedgerWiring.parameterIdOf("days");
    private final ParameterExpectation expectation = new ParameterExpectation(
            ID, "duration", Integer.class, "day", "requested_period", Duration.ofHours(1));

    private ToolCandidate candidate(String unit, int value) {
        return new ToolCandidate("days", value, unit, "duration", "requested_period", Instant.now(),
                new ValueSource("mcp_result", "registered-adapter:call-1", "v1", Instant.now()));
    }

    @Test void admittedToolValueReachesRealBuildAndKeepsSource() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.resolveToolValue("k", "days", expectation, () -> candidate("day", 7));
        var session = sessions.activeSession("k");
        assertEquals("mcp_result", session.ledger().current(ID).source().type());
        var result = sessions.build("k");
        assertTrue(result.ok(), result.message());
        assertEquals(7, sessions.approveRun("k").toSimulationConfig().getDays());
    }

    @Test void badUnitAndOutOfRangeValuesCannotReachBuild() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.resolveToolValue("k", "days", expectation, () -> candidate("hour", 7));
        assertFalse(sessions.build("k").ok());
        assertEquals("days", sessions.currentStep("k").question().answerField());
        sessions.resolveToolValue("k", "days", expectation, () -> candidate("day", -7));
        assertEquals(DecisionState.INVALID, sessions.activeSession("k").ledger().current(ID).state());
        assertFalse(sessions.build("k").ok());
    }

    @Test void timeoutIsRecordedAndNeverRetried() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        AtomicInteger calls = new AtomicInteger();
        sessions.resolveToolValue("k", "days", expectation, () -> {
            calls.incrementAndGet(); throw new TimeoutException();
        });
        assertEquals(1, calls.get());
        assertEquals("tool_timeout", sessions.activeSession("k").ledger().current(ID).blockingReason());
        assertFalse(sessions.build("k").ok());
    }

    @Test void resultFromBeforeAnEditIsRejected() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        var result = sessions.resolveToolValue("k", "days", expectation, () -> {
            sessions.submit("k", SubtaskTestSupport.idOfField(sessions, "k", "days"), 8, null);
            return candidate("day", 7);
        });
        assertNotNull(result.rejection());
        assertEquals(8, sessions.activeSession("k").ledger().current(ID).normalizedValue());
    }

    @Test void expiredExternalValueBlocksPreviouslyBuiltConfiguration() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.resolveToolValue("k", "days", expectation, () -> candidate("day", 7));
        assertTrue(sessions.build("k").ok());
        sessions.activeSession("k").trackToolExpiry(ID, Instant.now().minusSeconds(1));
        assertFalse(sessions.approveRunChecked("k").approved());
        assertEquals("source_expired", sessions.activeSession("k").ledger().current(ID).blockingReason());
    }
}
