package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구조를 바꾸는 답변이 들어왔을 때 이미 받은 값들의 유효성을 다시 따지는가.
 * 이것이 이 계획의 본체다 — 지금 코드에는 이 자리가 없다.
 */
class LedgerRecalculatorTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");

    private static ParameterDecision confirmed(String id, String param, Object value) {
        return new ParameterDecision(id, param, DecisionState.CONFIRMED,
                value, null, value, null,
                new ValueSource("user_explicit", "ST-01", null, T),
                null, List.of(), null, null, T);
    }

    private LedgerRecalculator recalculator() {
        return new LedgerRecalculator(
                JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of("sim::trafficMode", List.of("sim::trafficProfileId")));
    }

    @Test
    void 상위_값이_바뀌면_종속_결정이_낡는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.STALE, now.state());
        assertEquals("sim::trafficMode", now.supersededBy());
        assertEquals("upstream_value_changed", now.blockingReason());
    }

    @Test
    void 낡은_값은_지워지지_않고_이력에_남는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        assertEquals("P1", ledger.history("sim::trafficProfileId").get(0).normalizedValue());
        assertEquals("P1", ledger.current("sim::trafficProfileId").normalizedValue());
    }

    @Test
    void 가지가_살아나면_새_필수값이_미해결로_드러난다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.UNRESOLVED, now.state());
        assertEquals("required_value_unresolved", now.blockingReason());
    }

    @Test
    void 가지가_죽으면_해당없음으로_확정되고_근거가_남는다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "IGNORE"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.DEFAULTED, now.state());
        assertEquals("not_applicable_by_rule", now.source().type());
        assertEquals(JangnyangRules.TRAFFIC_APPLY, now.source().reference());
        assertTrue(now.state().executable());
    }

    @Test
    void 조건이_모름이면_필수값은_미해결로_남는다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode", Map.of());

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.UNRESOLVED, now.state());
        assertEquals("activation_unknown", now.blockingReason());
    }

    @Test
    void 이미_같은_상태면_같은_레코드를_거듭_쌓지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        LedgerRecalculator r = recalculator();

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));
        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));

        assertEquals(1, ledger.history("sim::trafficProfileId").size());
    }

    @Test
    void 바뀐_결과들을_돌려준다() {
        ParameterLedger ledger = new ParameterLedger();
        List<ParameterDecision> changed = recalculator()
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "APPLY"));

        assertEquals(1, changed.size());
        assertEquals("sim::trafficProfileId", changed.get(0).parameterId());
    }
}
