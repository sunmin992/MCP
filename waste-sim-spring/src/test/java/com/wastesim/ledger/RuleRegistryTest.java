package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미확정을 비활성으로 접지 않는가. 접으면 아무 답도 하지 않은 실행이 조용히
 * "그 가지는 필요 없다"는 가정을 쓴다.
 */
class RuleRegistryTest {

    @Test
    void 조건이_맞으면_활성이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertEquals(Activation.ACTIVE,
                rules.evaluate("traffic-apply", Map.of("trafficMode", "APPLY")));
    }

    @Test
    void 조건이_틀리면_비활성이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertEquals(Activation.INACTIVE,
                rules.evaluate("traffic-apply", Map.of("trafficMode", "IGNORE")));
    }

    @Test
    void 답이_아직_없으면_모름이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertEquals(Activation.UNKNOWN, rules.evaluate("traffic-apply", Map.of()));
    }

    @Test
    void 답이_null이어도_모름이다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        Map<String, Object> answers = new HashMap<>();
        answers.put("trafficMode", null);
        assertEquals(Activation.UNKNOWN, rules.evaluate("traffic-apply", answers));
    }

    @Test
    void 등록하지_않은_규칙은_평가하지_않고_던진다() {
        RuleRegistry rules = new RuleRegistry();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> rules.evaluate("없는규칙", Map.of()));
        assertTrue(e.getMessage().contains("없는규칙"), e.getMessage());
    }

    @Test
    void 아는_규칙인지_물어볼_수_있다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertTrue(rules.knows("traffic-apply"));
        assertFalse(rules.knows("없는규칙"));
    }

    @Test
    void 같은_규칙_ID를_두_번_등록할_수_없다() {
        RuleRegistry rules = new RuleRegistry()
                .register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "APPLY"));
        assertThrows(IllegalArgumentException.class, () ->
                rules.register("traffic-apply", RuleRegistry.fieldEquals("trafficMode", "X")));
    }

    @Test
    void 장량동_규칙집은_교통_활성_규칙을_안다() {
        assertTrue(JangnyangRules.registry().knows(JangnyangRules.TRAFFIC_APPLY));
        assertEquals(Activation.ACTIVE, JangnyangRules.registry()
                .evaluate(JangnyangRules.TRAFFIC_APPLY, Map.of("trafficMode", "APPLY")));
    }
}
