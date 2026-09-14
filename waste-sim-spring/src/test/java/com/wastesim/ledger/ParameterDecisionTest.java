package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 레코드가 자기 불변식을 지키는가. 생성 시점에 막지 않으면 근거 없는 결정이
 * 원장에 들어앉고, 원장에 들어앉은 뒤에는 실행 직전 검사가 그것을 근거 있는
 * 것으로 읽는다.
 */
class ParameterDecisionTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final ValueSource USER = new ValueSource("user_explicit", "ST-01", "v4", T);

    @Test
    void 실행_허용_상태는_셋뿐이다() {
        assertTrue(DecisionState.CONFIRMED.executable());
        assertTrue(DecisionState.DERIVED.executable());
        assertTrue(DecisionState.DEFAULTED.executable());
        assertFalse(DecisionState.UNRESOLVED.executable());
        assertFalse(DecisionState.CONFLICTED.executable());
        assertFalse(DecisionState.INVALID.executable());
        assertFalse(DecisionState.STALE.executable());
    }

    @Test
    void derived는_변환_규칙_없이_만들_수_없다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.DERIVED,
                        "7일", null, 7, "day", USER, null, List.of(), null, null, T));
        assertTrue(e.getMessage().contains("변환 규칙"), e.getMessage());
    }

    @Test
    void 실행_불가_상태는_차단_사유_없이_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.UNRESOLVED,
                        null, null, null, null, null, null, List.of(), null, null, T));
    }

    @Test
    void stale은_무엇_때문에_낡았는지_적어야_한다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.STALE,
                        null, null, 7, "day", USER, null, List.of(),
                        "upstream_value_changed", null, T));
    }

    @Test
    void 실행_허용_상태는_출처_없이_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.CONFIRMED,
                        "7", null, 7, "day", null, null, List.of(), null, null, T));
    }

    @Test
    void 근거를_갖춘_확정값은_만들어진다() {
        ParameterDecision d = new ParameterDecision("d1", "sim::days", DecisionState.CONFIRMED,
                "7", null, 7, "day", USER, null, List.of("ST-01"), null, null, T);
        assertEquals("sim::days", d.parameterId());
        assertTrue(d.state().executable());
    }

    @Test
    void 기록_시각_없이는_만들_수_없다() {
        // 원장의 "현재"는 쌓인 순서의 마지막이다. 시각이 없으면 이력은 남아도
        // 무엇이 앞섰는지 말할 수 없고, 원장이 존재할 이유가 사라진다.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new ParameterDecision("d1", "sim::days", DecisionState.CONFIRMED,
                        "7", null, 7, "day", USER, null, List.of(), null, null, null));
        assertTrue(e.getMessage().contains("언제"), e.getMessage());
    }
}
