package com.wastesim.subtask;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변이 원장에 남는가. 남지 않으면 나머지 모든 검사가 빈 원장 위에서 돌고,
 * 빈 원장은 아무것도 막지 않는다.
 */
class SubmitRecordsDecisionTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    @Test
    void 사용자가_답하면_확정_결정이_쌓인다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, 7, null);

        ParameterDecision d = sessions.activeSession("k").ledger()
                .current(JangnyangLedgerWiring.parameterIdOf("days"));

        assertNotNull(d, "답변이 원장에 남지 않았다");
        assertEquals(DecisionState.CONFIRMED, d.state());
        assertEquals(7, d.normalizedValue());
    }

    @Test
    void 출처가_원장에_남는다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, 7, null);

        ParameterDecision d = sessions.activeSession("k").ledger()
                .current(JangnyangLedgerWiring.parameterIdOf("days"));

        assertNotNull(d.source());
        assertEquals(subtaskId, d.source().reference(),
                "어느 질문의 답인지 남지 않으면 나중에 대조할 곳이 없다");
    }

    @Test
    void 검증에_실패한_답은_확정으로_쌓이지_않는다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, -5, null);

        ParameterDecision d = sessions.activeSession("k").ledger()
                .current(JangnyangLedgerWiring.parameterIdOf("days"));

        if (d != null) {
            assertNotEquals(DecisionState.CONFIRMED, d.state(),
                    "검증기가 거부한 값이 원장에서 확정값이 되면 fail-closed가 무너진다");
        }
    }

    @Test
    void 답을_고치면_이력이_쌓인다() {
        sessions.start("k");
        String subtaskId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", subtaskId, 7, null);
        sessions.submit("k", subtaskId, 14, null);

        var history = sessions.activeSession("k").ledger()
                .history(JangnyangLedgerWiring.parameterIdOf("days"));

        assertEquals(2, history.size(), "덮어쓰면 왜 바뀌었는지가 사라진다");
        assertEquals(7, history.get(0).normalizedValue());
        assertEquals(14, history.get(1).normalizedValue());
    }
}
