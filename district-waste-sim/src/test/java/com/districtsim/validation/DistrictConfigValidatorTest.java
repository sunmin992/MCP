package com.districtsim.validation;

import com.districtsim.model.DistrictConfig;
import com.districtsim.tool.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistrictConfigValidatorTest {

    private final DistrictConfigValidator validator = new DistrictConfigValidator();

    private List<String> blocked(DistrictConfig c) {
        return validator.validate(c).errors().stream().map(ValidationError::field).toList();
    }

    @Test
    void 기본_설정은_통과한다() {
        assertThat(validator.validate(new DistrictConfig()).ready()).isTrue();
    }

    @Test
    void 요일_지정인데_요일이_없으면_막는다() {
        DistrictConfig c = new DistrictConfig();
        c.setScheduleMode("WEEKDAYS");
        assertThat(blocked(c)).contains("collectionDays");
        c.setCollectionDays(List.of("MON", "THU"));
        assertThat(validator.validate(c).ready()).isTrue();
    }

    @Test
    void 공휴일을_반영하는데_정책이_없으면_막고_매일_수거면_묻지_않는다() {
        DistrictConfig c = new DistrictConfig();
        c.setHolidayEnabled(true);
        assertThat(blocked(c)).contains("holidayPolicy");
        c.setScheduleMode("DAILY");
        assertThat(validator.validate(c).ready()).isTrue();
    }

    @Test
    void 적환장을_쓰는데_왕복시간이_없으면_막는다() {
        DistrictConfig c = new DistrictConfig();
        c.setTransferEnabled(true);
        assertThat(blocked(c)).contains("transferRoundTripMinutes");
        c.setTransferRoundTripMinutes(30);
        assertThat(validator.validate(c).ready()).isTrue();
    }

    @Test
    void 차량이_감당할_수_없는_부하면_막는다() {
        DistrictConfig c = new DistrictConfig();
        c.setZoneCount(40);
        c.setHouseholdsPerZone(2000);
        c.setTruckCount(1);
        c.setTruckType("SMALL_1TON");
        assertThat(blocked(c)).contains("truckCount");
    }

    @Test
    void 모르는_선택지는_보정하지_않고_막는다() {
        DistrictConfig c = new DistrictConfig();
        c.setTruckType("3TON");
        c.setHousingMix(List.of("Student"));
        c.setZoneAllocation("RANDOM");
        assertThat(blocked(c)).contains("truckType", "housingMix", "zoneAllocation");
    }
}
