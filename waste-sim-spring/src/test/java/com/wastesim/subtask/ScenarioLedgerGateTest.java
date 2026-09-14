package com.wastesim.subtask;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioLedgerGateTest {
    @Test void verifiesDirectAndSplitFieldsAgainstAnswers() {
        var sessions = SubtaskTestSupport.service();
        SubtaskTestSupport.answerEverything(sessions, "k");
        var build = sessions.build("k");
        assertTrue(build.ok(), build.message());
        var session = sessions.activeSession("k");
        var def = sessions.definitionOf(session);
        assertTrue(ScenarioLedgerGate.verify(def, session.answers(), build.spec()).isEmpty());
        build.spec().toSimulationConfig().setDays(99);
        build.spec().toSimulationConfig().setDischargeWindowStartMinutes(17);
        var errors = ScenarioLedgerGate.verify(def, session.answers(), build.spec());
        assertTrue(errors.stream().anyMatch(e -> e.contains("days")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("dischargeWindowStartMinutes")));
    }

    @Test void fieldNameSubmissionWritesCanonicalLedgerEntry() {
        var sessions = SubtaskTestSupport.service();
        sessions.start("k");
        assertTrue(sessions.submit("k", "days", 7, null).ok());
        assertEquals(7, sessions.activeSession("k").ledger().current(
                "jangnyang-simulator::days").normalizedValue());
        assertFalse(sessions.submit("k", "inventedField", 7, null).ok());
        assertFalse(sessions.submit("k", "days", 7, null, SubtaskAnswerSource.MCP_RESULT).ok());
    }
}
