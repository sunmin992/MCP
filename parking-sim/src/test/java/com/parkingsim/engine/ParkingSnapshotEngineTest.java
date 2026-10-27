package com.parkingsim.engine;

import com.parkingsim.data.ParkingSnapshot;
import com.parkingsim.model.ParkingConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ParkingSnapshotEngineTest {
    @Test void usesPerLotCapacityAndInitialOccupancyWithoutBreakingBalance() {
        var c = new ParkingConfig();
        c.setLotCount(2); c.setDays(1); c.setSeeds(1); c.setMaxSearchMinutes(10);
        c.setDailyArrivals(1); c.setPricingPolicy("FREE"); c.setInitialVehicleRemainingMinutes(1440);
        c.setParkingSnapshot(new ParkingSnapshot("test", "fixture", "2026-10-27T03:00:00Z", "47113", List.of(
                new ParkingSnapshot.Lot("a", "A", "", "", "", 10, 10, 0),
                new ParkingSnapshot.Lot("b", "B", "", "", "", 30, 30, 30))));
        var result = new ParkingEngine(new CongestionProfiles()).run(c, 1);
        assertThat(c.totalSpaces()).isEqualTo(40);
        assertThat(c.startMinute()).isEqualTo(720);
        assertThat(result.lotPeakOccupancy()[0]).isEqualTo(1.0);
        assertThat(result.hourlyOccupancy()[12]).isGreaterThanOrEqualTo(.25);
        assertThat(result.vehicleBalanceError()).isZero();
        assertThat(result.arrivals()).isLessThanOrEqualTo(2);
    }
    @Test void initialVehiclesDepartAtExplicitAssumedTime() {
        var c = new ParkingConfig();
        c.setLotCount(1); c.setDays(1); c.setMaxSearchMinutes(10); c.setDailyArrivals(1);
        c.setInitialVehicleRemainingMinutes(60);
        c.setParkingSnapshot(new ParkingSnapshot("test", "fixture", "2026-10-26T15:00:00Z", "47113", List.of(
                new ParkingSnapshot.Lot("a", "A", "", "", "", 10, 10, 0))));
        var result = new ParkingEngine(new CongestionProfiles()).run(c, 1);
        assertThat(result.hourlyOccupancy()[0]).isEqualTo(1);
        assertThat(result.hourlyOccupancy()[1]).isZero();
        assertThat(result.vehicleBalanceError()).isZero();
    }
}
