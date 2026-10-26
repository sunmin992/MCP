package com.countersim.validation;

import com.countersim.model.CounterConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CounterConfigValidatorTest {

    private final CounterConfigValidator validator = new CounterConfigValidator();

    private static CounterConfig ok() {
        CounterConfig c = new CounterConfig();
        c.setMaxWaitMinutes(30);
        return c;
    }

    private static String fields(ValidationResult r) {
        return r.errors().stream().map(e -> e.field()).toList().toString();
    }

    @Test
    void 기본_설정에_대기_한도만_정하면_통과한다() {
        ValidationResult r = validator.validate(ok());
        assertTrue(r.ready(), fields(r));
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void 대기_한도가_없으면_막는다() {
        CounterConfig c = ok();
        c.setMaxWaitMinutes(null);
        assertTrue(fields(validator.validate(c)).contains("maxWaitMinutes"));
    }

    @Test
    void 예약제인데_비율이_없으면_막고_예약제가_아닌데_비율이_있으면_알리기만_한다() {
        CounterConfig on = ok();
        on.setReservationEnabled(true);
        assertTrue(fields(validator.validate(on)).contains("reservationShare"));

        CounterConfig off = ok();
        off.setReservationShare(0.3);
        ValidationResult r = validator.validate(off);
        assertTrue(r.ready());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void 수요가_공급의_세_배를_넘으면_막고_한_배를_넘으면_경고한다() {
        CounterConfig heavy = ok();
        heavy.setDailyVisitors(700);   // 700 × 8 / 1620 ≈ 346%
        assertTrue(fields(validator.validate(heavy)).contains("dailyVisitors"));

        CounterConfig busy = ok();
        busy.setDailyVisitors(250);    // 250 × 8 / 1620 ≈ 123%
        ValidationResult r = validator.validate(busy);
        assertTrue(r.ready());
        assertFalse(r.warnings().isEmpty());
    }
}
