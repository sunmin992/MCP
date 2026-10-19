package com.districtsim.engine;

import com.districtsim.model.DistrictConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DistrictEngineTest {

    private final DistrictEngine engine = new DistrictEngine(new HolidayCalendar());
    private final DistrictExperiment experiment = new DistrictExperiment(engine);

    private static DistrictConfig base() {
        DistrictConfig c = new DistrictConfig();
        c.setSeeds(10);
        return c;
    }

    @Test
    void 질량_수지가_맞는다() {
        DistrictConfig c = base();
        c.setTruckCount(1);
        SeedResult r = engine.run(c, 3);
        assertThat(r.massBalanceErrorKg()).isCloseTo(0.0, within(1e-6));
        assertThat(r.leftoverKgSum()).isPositive();
    }

    @Test
    void 같은_시드는_같은_결과를_낸다() {
        SeedResult a = engine.run(base(), 5);
        SeedResult b = engine.run(base(), 5);
        assertThat(a.collectedKg()).isEqualTo(b.collectedKg());
        assertThat(a.leftoverKgSum()).isEqualTo(b.leftoverKgSum());
    }

    @Test
    void 추석_연휴를_반영하면_남는_양이_늘고_다음_날_수거가_건너뛰기보다_낫다() {
        DistrictConfig ignore = base();
        DistrictConfig skip = base();
        skip.setHolidayEnabled(true);
        skip.setHolidayPolicy("SKIP");
        DistrictConfig next = base();
        next.setHolidayEnabled(true);
        next.setHolidayPolicy("NEXT_DAY");
        double li = experiment.run(ignore).leftoverKgPerCollectionDay();
        double ls = experiment.run(skip).leftoverKgPerCollectionDay();
        double ln = experiment.run(next).leftoverKgPerCollectionDay();
        assertThat(ls).isGreaterThan(ln);
        assertThat(ln).isGreaterThan(li);
        assertThat(experiment.run(skip).skippedForHoliday()).isPositive();
    }

    @Test
    void 작업_시간이_같게_나누면_한쪽에_몰린_구역을_덜_남긴다() {
        List<String> mix = List.of("Apartment", "Apartment", "Apartment", "Detached", "Detached", "Commercial", "Apartment", "Apartment");
        DistrictConfig equal = base();
        equal.setHousingMix(mix);
        DistrictConfig balanced = base();
        balanced.setHousingMix(mix);
        balanced.setZoneAllocation("BALANCED_LOAD");
        assertThat(experiment.run(balanced).leftoverKgPerCollectionDay())
                .isLessThan(experiment.run(equal).leftoverKgPerCollectionDay());
    }

    @Test
    void 배출_마감_전에_시작하면_남는_양이_생긴다() {
        DistrictConfig early = base();
        early.setCollectionStartMinutes(240);
        assertThat(experiment.run(early).leftoverKgPerCollectionDay()).isPositive();
        assertThat(experiment.run(base()).leftoverKgPerCollectionDay()).isZero();
    }

    @Test
    void 상가를_빼면_그_양은_수거_대상이_아니다() {
        DistrictConfig c = base();
        c.setHousingMix(List.of("Apartment", "Commercial"));
        c.setCommercialHandling("EXCLUDED");
        DistrictExperiment.Summary s = experiment.run(c);
        assertThat(s.excludedKgPerDay()).isPositive();
        assertThat(s.massBalanceErrorKgMax()).isCloseTo(0.0, within(1e-6));
    }
}
