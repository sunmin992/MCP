package com.parkingsim.template;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
    void 템플릿은_16개이고_SES_식별자가_있다() {
        assertThat(catalog.all()).hasSize(16);
        assertThat(catalog.sesId()).isEqualTo("pohang-parking-ses");
    }

    @Test
    void 주차장_수를_모르면_안내와_갱신주기는_보류다() {
        Map<String, Object> a = Map.of();
        assertThat(status(a, "guidanceMode")).isEqualTo(SubtaskStatus.DEFERRED);
        assertThat(status(a, "guidanceRefreshMinutes")).isEqualTo(SubtaskStatus.DEFERRED);
    }

    @Test
    void 주차장이_한_곳이면_안내와_갱신주기가_연쇄로_생기지_않는다() {
        Map<String, Object> a = Map.of("lotCount", 1);
        assertThat(status(a, "guidanceMode")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(a, "guidanceRefreshMinutes")).isEqualTo(SubtaskStatus.NOT_GENERATED);
    }

    @Test
    void 여러_곳이고_실시간_앱이면_갱신주기를_묻는다() {
        assertThat(status(Map.of("lotCount", 4), "guidanceRefreshMinutes")).isEqualTo(SubtaskStatus.DEFERRED);
        assertThat(status(Map.of("lotCount", 4, "guidanceMode", "REALTIME_APP"), "guidanceRefreshMinutes"))
                .isEqualTo(SubtaskStatus.UNFILLED);
        assertThat(status(Map.of("lotCount", 4, "guidanceMode", "STATIC_SIGN"), "guidanceRefreshMinutes"))
                .isEqualTo(SubtaskStatus.NOT_GENERATED);
    }

    @Test
    void 요금_정책이_기본요금과_할증률을_정한다() {
        assertThat(status(Map.of(), "baseFeePer30Min")).isEqualTo(SubtaskStatus.DEFERRED);
        assertThat(status(Map.of("pricingPolicy", "FREE"), "baseFeePer30Min")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("pricingPolicy", "FREE"), "peakSurchargeRate")).isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("pricingPolicy", "TIME_OF_DAY"), "peakSurchargeRate")).isEqualTo(SubtaskStatus.UNFILLED);
    }

    @Test
    void 거주자가_있을_때만_우선구획을_묻는다() {
        assertThat(status(Map.of("userMix", List.of("Commuter", "Shopper")), "residentPriorityShare"))
                .isEqualTo(SubtaskStatus.NOT_GENERATED);
        assertThat(status(Map.of("userMix", List.of("Commuter", "Resident")), "residentPriorityShare"))
                .isEqualTo(SubtaskStatus.UNFILLED);
    }

    @Test
    void 필요한_값을_다_채우면_완결이다() {
        Map<String, Object> a = new HashMap<>(Map.of(
                "lotCount", 1, "spacesPerLot", 80, "pricingPolicy", "FREE",
                "userMix", List.of("Commuter", "Shopper"), "dailyArrivals", 300,
                "congestionMode", "IGNORE", "maxSearchMinutes", 15, "days", 7, "seeds", 5));
        SubtaskPlan plan = planner.plan(a);
        assertThat(plan.complete()).isTrue();
        assertThat(plan.counts().get(SubtaskStatus.NOT_GENERATED)).isEqualTo(7);
    }

    @Test
    void 모든_템플릿에_확인_화면에_보일_이름이_있다() {
        assertThat(catalog.all()).allSatisfy(t -> assertThat(t.label()).as(t.templateId()).isNotBlank());
    }
}
