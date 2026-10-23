package com.districtsim.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubtaskPlanTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog);

    private SubtaskStatus status(Map<String, Object> answers, String key) {
        return planner.plan(answers).instances().stream()
                .filter(i -> i.answerKey().equals(key)).findFirst().orElseThrow().status();
    }

    @Test
    void 템플릿은_17개이고_SES_식별자가_있다() {
        assertThat(catalog.all()).hasSize(17);
        assertThat(catalog.sesId()).isEqualTo("bukgu-district-ses");
    }

    @Test
    void 공휴일_정책은_공휴일_반영과_일정에_연쇄된다() {
        assertThat(status(Map.of(), "holidayPolicy")).isEqualTo(SubtaskStatus.DEFERRED);
        // 공휴일을 반영하지 않으면 일정을 몰라도 "영영 없음" 이다.
        assertThat(status(Map.of("holidayMode", "IGNORE"), "holidayPolicy")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("holidayMode", "APPLY"), "holidayPolicy")).isEqualTo(SubtaskStatus.DEFERRED);
        assertThat(status(Map.of("holidayMode", "APPLY", "scheduleMode", "DAILY"), "holidayPolicy"))
                .isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("holidayMode", "APPLY", "scheduleMode", "POHANG_BUKGU"), "holidayPolicy"))
                .isEqualTo(SubtaskStatus.UNFILLED);
    }

    @Test
    void 요일_지정일_때만_요일을_묻는다() {
        assertThat(status(Map.of("scheduleMode", "POHANG_BUKGU"), "collectionDays")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("scheduleMode", "WEEKDAYS"), "collectionDays")).isEqualTo(SubtaskStatus.UNFILLED);
    }

    @Test
    void 실제로_나가는_차가_두_대_이상일_때만_구역_배정을_묻는다() {
        assertThat(status(Map.of("truckCount", 3), "zoneAllocation")).isEqualTo(SubtaskStatus.DEFERRED);
        assertThat(status(Map.of("truckCount", 3, "zoneCount", 1), "zoneAllocation")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("truckCount", 3, "zoneCount", 8), "zoneAllocation")).isEqualTo(SubtaskStatus.UNFILLED);
    }

    @Test
    void 상가가_있을_때만_상가_처리를_묻고_적환장일_때만_왕복시간을_묻는다() {
        assertThat(status(Map.of("housingMix", List.of("Apartment")), "commercialHandling")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("housingMix", List.of("Apartment", "Commercial")), "commercialHandling")).isEqualTo(SubtaskStatus.UNFILLED);
        assertThat(status(Map.of("transferMode", "APPLY"), "transferRoundTripMinutes")).isEqualTo(SubtaskStatus.UNFILLED);
        assertThat(status(Map.of("transferMode", "IGNORE"), "transferRoundTripMinutes")).isEqualTo(SubtaskStatus.NOT_GENERATED);
    }

    @Test
    void 필요한_값을_다_채우면_완결이다() {
        Map<String, Object> a = Map.ofEntries(
                Map.entry("zoneCount", 8), Map.entry("householdsPerZone", 600),
                Map.entry("housingMix", List.of("Apartment", "Detached")), Map.entry("truckType", "LARGE_5TON"),
                Map.entry("truckCount", 1), Map.entry("scheduleMode", "DAILY"), Map.entry("holidayMode", "APPLY"),
                Map.entry("transferMode", "IGNORE"), Map.entry("collectionStartMinutes", 360), Map.entry("shiftMinutes", 480),
                Map.entry("days", 28), Map.entry("seeds", 5));
        SubtaskPlan plan = planner.plan(a);
        assertThat(plan.complete()).isTrue();
        assertThat(plan.counts().get(SubtaskStatus.NOT_GENERATED)).isEqualTo(5);
    }
}
