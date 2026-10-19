package com.parkingsim.validation;

import com.parkingsim.engine.CongestionProfiles;
import com.parkingsim.model.ParkingConfig;
import com.parkingsim.tool.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParkingConfigValidatorTest {

    private final ParkingConfigValidator validator = new ParkingConfigValidator(new CongestionProfiles());

    private static ParkingConfig base() {
        ParkingConfig c = new ParkingConfig();
        c.setMaxSearchMinutes(15);
        return c;
    }

    private List<String> blockedFields(ParkingConfig c) {
        return validator.validate(c).errors().stream().map(ValidationError::field).toList();
    }

    @Test
    void 기본_설정에_탐색_포기_시간만_정하면_통과한다() {
        assertThat(validator.validate(base()).ready()).isTrue();
    }

    @Test
    void 탐색_포기_시간이_없으면_막는다() {
        assertThat(blockedFields(new ParkingConfig())).contains("maxSearchMinutes");
    }

    @Test
    void 시간대_차등인데_할증률이_없으면_막는다() {
        ParkingConfig c = base();
        c.setPricingPolicy("TIME_OF_DAY");
        assertThat(blockedFields(c)).contains("peakSurchargeRate");
        c.setPeakSurchargeRate(0.5);
        assertThat(validator.validate(c).ready()).isTrue();
    }

    @Test
    void 거주자가_있는데_우선구획_비율이_없으면_막고_없는데_두면_막는다() {
        ParkingConfig c = base();
        c.setUserMix(List.of("Commuter", "Resident"));
        assertThat(blockedFields(c)).contains("residentPriorityShare");
        c.setResidentPriorityShare(0.2);
        assertThat(validator.validate(c).ready()).isTrue();

        ParkingConfig d = base();
        d.setResidentPriorityShare(0.2);
        assertThat(blockedFields(d)).contains("residentPriorityShare");
    }

    @Test
    void 갱신_주기가_탐색_포기_시간보다_길면_막는다() {
        ParkingConfig c = base();
        c.setGuidanceMode("REALTIME_APP");
        c.setGuidanceRefreshMinutes(20);
        assertThat(blockedFields(c)).contains("guidanceRefreshMinutes");
    }

    @Test
    void 수요가_공급의_세_배를_넘으면_막는다() {
        ParkingConfig c = base();
        c.setLotCount(1);
        c.setSpacesPerLot(10);
        c.setDailyArrivals(2000);
        assertThat(blockedFields(c)).contains("dailyArrivals");
    }

    @Test
    void 모르는_선택지는_보정하지_않고_막는다() {
        ParkingConfig c = base();
        c.setGuidanceMode("APP");
        c.setUserMix(List.of("Student"));
        assertThat(blockedFields(c)).contains("guidanceMode", "userMix");
    }
}
