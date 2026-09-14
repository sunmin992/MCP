package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원장이 덮어쓰지 않는가. 덮어쓰면 "왜 이 값으로 바뀌었는가"가 사라지고,
 * 그 질문은 결과가 이상할 때만 나오므로 그때는 이미 늦다.
 */
class ParameterLedgerTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");

    private static ParameterDecision confirmed(String id, String param, Object value) {
        return new ParameterDecision(id, param, DecisionState.CONFIRMED,
                value, null, value, null,
                new ValueSource("user_explicit", "ST-01", null, T),
                null, List.of(), null, null, T);
    }

    @Test
    void 새_결정은_이전_것을_지우지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        ledger.append(confirmed("d2", "sim::days", 14));

        assertEquals(2, ledger.history("sim::days").size());
        assertEquals(7, ledger.history("sim::days").get(0).normalizedValue());
    }

    @Test
    void 현재_값은_마지막_결정이다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        ledger.append(confirmed("d2", "sim::days", 14));

        assertEquals(14, ledger.current("sim::days").normalizedValue());
    }

    @Test
    void 결정이_없는_매개변수의_현재는_null이다() {
        assertNull(new ParameterLedger().current("sim::days"));
    }

    @Test
    void 같은_ID를_두_번_쌓을_수_없다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.append(confirmed("d1", "sim::seeds", 1)));
    }

    @Test
    void 실행을_막는_것들만_따로_모은다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("d1", "sim::days", 7));
        ledger.append(new ParameterDecision("d2", "sim::seeds", DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(),
                "required_value_unresolved", null, T));

        List<ParameterDecision> blocking = ledger.blocking();
        assertEquals(1, blocking.size());
        assertEquals("sim::seeds", blocking.get(0).parameterId());
    }

    @Test
    void 막힌_것이_나중에_풀리면_더는_막지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("d1", "sim::seeds", DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(),
                "required_value_unresolved", null, T));
        ledger.append(confirmed("d2", "sim::seeds", 1));

        assertEquals(List.of(), ledger.blocking());
        assertEquals(2, ledger.history("sim::seeds").size());
    }

    @Test
    void 결정_ID는_매개변수마다_이어서_붙는다() {
        ParameterLedger ledger = new ParameterLedger();
        assertEquals("sim::days#1", ledger.nextDecisionId("sim::days"));
        ledger.append(confirmed(ledger.nextDecisionId("sim::days"), "sim::days", 7));
        assertEquals("sim::days#2", ledger.nextDecisionId("sim::days"));
    }
}
