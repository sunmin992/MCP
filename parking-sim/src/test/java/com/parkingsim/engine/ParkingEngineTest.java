package com.parkingsim.engine;

import com.parkingsim.model.ParkingConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParkingEngineTest {

    private final ParkingEngine engine = new ParkingEngine(new CongestionProfiles());
    private final ParkingExperiment experiment = new ParkingExperiment(engine);

    private static ParkingConfig busy() {
        ParkingConfig c = new ParkingConfig();
        c.setLotCount(4);
        c.setSpacesPerLot(80);
        c.setDailyArrivals(900);
        c.setMaxSearchMinutes(15);
        c.setDays(7);
        c.setSeeds(10);
        return c;
    }

    @Test
    void 차량_수지가_맞는다() {
        SeedResult r = engine.run(busy(), 1);
        assertThat(r.vehicleBalanceError()).isZero();
        assertThat(r.arrivals()).isEqualTo(900 * 7);
        assertThat(r.gaveUp()).isPositive();
    }

    @Test
    void 같은_시드는_같은_결과를_낸다() {
        SeedResult a = engine.run(busy(), 7);
        SeedResult b = engine.run(busy(), 7);
        assertThat(a.parked()).isEqualTo(b.parked());
        assertThat(a.gaveUp()).isEqualTo(b.gaveUp());
        assertThat(a.searchMinutesSum()).isEqualTo(b.searchMinutesSum());
    }

    @Test
    void 실시간_앱은_안내_없음보다_탐색_시간을_줄인다() {
        ParkingConfig none = busy();
        ParkingConfig app = busy();
        app.setGuidanceMode("REALTIME_APP");
        double searchNone = experiment.run(none).meanSearchMinutesAll();
        double searchApp = experiment.run(app).meanSearchMinutesAll();
        assertThat(searchApp).isLessThan(searchNone);
    }

    @Test
    void 무료면_요금_회피가_없고_비싸면_생긴다() {
        ParkingConfig free = busy();
        free.setPricingPolicy("FREE");
        ParkingConfig pricey = busy();
        pricey.setBaseFeePer30Min(1500);
        assertThat(experiment.run(free).priceAvoidedPerDay()).isZero();
        assertThat(experiment.run(pricey).priceAvoidedPerDay()).isPositive();
    }

    @Test
    void 주차장이_한_곳이면_안내_방식이_포기_수를_바꾸지_않는다() {
        ParkingConfig a = busy();
        a.setLotCount(1);
        a.setSpacesPerLot(200);
        ParkingConfig b = busy();
        b.setLotCount(1);
        b.setSpacesPerLot(200);
        b.setGuidanceMode("STATIC_SIGN");
        assertThat(experiment.run(a).giveUpsPerDay()).isEqualTo(experiment.run(b).giveUpsPerDay());
    }

    @Test
    void 혼잡을_반영하면_탐색_시간이_늘어난다() {
        ParkingConfig plain = busy();
        ParkingConfig congested = busy();
        congested.setCongestionEnabled(true);
        assertThat(experiment.run(congested).meanSearchMinutesAll())
                .isGreaterThan(experiment.run(plain).meanSearchMinutesAll());
    }
}
