package com.countersim.engine;

import com.countersim.model.CounterConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CounterEngineTest {

    private final CounterEngine engine = new CounterEngine();
    private final CounterExperiment experiment = new CounterExperiment(engine);

    private static CounterConfig base() {
        CounterConfig c = new CounterConfig();
        c.setMaxWaitMinutes(30);
        c.setSeeds(10);
        return c;
    }

    @Test
    void 방문객은_처리되거나_돌아가거나_둘_중_하나다() {
        for (String q : new String[]{"SINGLE_LINE", "PER_COUNTER"}) {
            CounterConfig c = base();
            c.setQueueDiscipline(q);
            c.setDailyVisitors(260);
            for (long seed = 1; seed <= 5; seed++) {
                SeedResult r = engine.run(c, seed);
                assertEquals(0, r.visitorBalanceError(), q + " seed " + seed);
                assertEquals(260 * c.getDays(), r.visitors());
            }
        }
    }

    @Test
    void 붐벼서_초과_근무를_해도_가동률은_1을_넘지_않는다() {
        CounterConfig c = base();
        c.setCounterCount(2);
        c.setDailyVisitors(200);
        double u = experiment.run(c).utilization();
        assertTrue(u > 0.9 && u <= 1.0, "가동률 " + u);
    }

    @Test
    void 같은_시드는_같은_결과를_낸다() {
        CounterConfig c = base();
        assertEquals(engine.run(c, 3).waitSumServed(), engine.run(c, 3).waitSumServed());
    }

    @Test
    void 창구를_늘리면_대기가_준다() {
        CounterConfig two = base();
        two.setCounterCount(2);
        CounterConfig four = base();
        four.setCounterCount(4);
        assertTrue(experiment.run(four).meanWaitMinutesAll() < experiment.run(two).meanWaitMinutesAll());
    }

    @Test
    void 번호표_한_줄이_창구마다_줄보다_덜_돌아가게_한다() {
        // 평균 대기로 비교하지 않는다. 창구마다 줄이면 노는 창구가 남의 줄을 받지 못해 돌아가는 사람이 늘고,
        // 그만큼 줄이 짧아져 평균 대기는 오히려 낮게 나올 수 있다 — 이탈과 함께 읽어야 한다.
        CounterConfig single = base();
        CounterConfig per = base();
        per.setQueueDiscipline("PER_COUNTER");
        var s = experiment.run(single);
        var p = experiment.run(per);
        assertTrue(s.renegesPerDay() < p.renegesPerDay());
        assertTrue(s.utilization() >= p.utilization());
    }

    @Test
    void 예약한_사람은_먼저_불려_덜_기다린다() {
        CounterConfig c = base();
        c.setDailyVisitors(200);
        c.setReservationEnabled(true);
        c.setReservationShare(0.3);
        var byType = experiment.run(c).byVisitorType();
        assertTrue(byType.get("RESERVED").get("meanWaitMinutes") < byType.get("WALK_IN").get("meanWaitMinutes"));
    }

    @Test
    void 아무도_한도보다_오래_기다리지_않는다() {
        CounterConfig c = base();
        c.setCounterCount(1);
        c.setDailyVisitors(120);
        SeedResult r = engine.run(c, 1);
        assertTrue(r.reneged() > 0);
        assertTrue(r.meanWaitAll() <= c.getMaxWaitMinutes());
    }
}
