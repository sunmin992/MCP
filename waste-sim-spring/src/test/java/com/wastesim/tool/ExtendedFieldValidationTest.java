package com.wastesim.tool;

import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 확장 설정 필드의 검증 (DEBUGGING_ISSUES.md W-06).
 *
 * <p>여기 나오는 필드들은 지금까지 검증기를 그냥 통과했다. 일부는 setter나 사용 시점에서
 * 조용히 보정됐고(예: {@code Math.max(0, v)}, {@code clamp01}), 나머지는 보정도 없이
 * 그대로 시뮬레이션에 들어갔다. 둘 다 결과는 같다 — <b>요청한 것과 다른 실험이 돌아가고
 * 아무도 모른다</b>. 이 프로젝트의 fail-closed 원칙대로 실행 전에 오류로 돌려줘야 한다.
 */
class ExtendedFieldValidationTest {

    private final SimulationConfigValidator validator =
            new SimulationConfigValidator(new TrafficDataService());

    /** 기본값은 전부 유효하므로, 검사할 필드 하나만 바꿔 그 필드의 규칙만 시험한다. */
    private ValidationResult check(Consumer<SimulationConfig> mutate) {
        SimulationConfig c = new SimulationConfig();
        mutate.accept(c);
        return validator.validate(c);
    }

    private void expectError(String field, Consumer<SimulationConfig> mutate, String why) {
        ValidationResult r = check(mutate);
        assertFalse(r.ready(), why);
        assertTrue(r.errors().stream().anyMatch(e -> e.field().contains(field)),
                field + " 오류가 나와야 한다. 실제: " + r.errors());
    }

    @Test
    @DisplayName("기본 설정은 통과한다 — 기준선")
    void defaultsPass() {
        assertTrue(check(c -> {}).ready());
    }

    // ── 수거 주기 ────────────────────────────────────────────────────────

    /**
     * setter가 {@code Math.max(1, v)}로 보정하면 0을 요청해도 매일 수거로 돌아간다.
     * 격일 수거 실험을 하려던 사람이 주기 0을 잘못 넣었을 때 알 방법이 없다.
     */
    @Test
    @DisplayName("수거 주기 0·음수는 1로 보정하지 말고 거부한다")
    void collectionIntervalRejected() {
        expectError("collectionIntervalDays", c -> c.setCollectionIntervalDays(0),
                "주기 0은 물리적으로 성립하지 않는다");
        expectError("collectionIntervalDays", c -> c.setCollectionIntervalDays(-3), "음수 주기");
        expectError("collectionIntervalDays", c -> c.setCollectionIntervalDays(500),
                "기간 상한(365일)보다 긴 주기는 수거가 한 번도 일어나지 않는다");
    }

    @Test
    @DisplayName("정상 수거 주기는 통과한다")
    void collectionIntervalAccepted() {
        // 격일 수거는 수거 용량을 절반으로 낮추므로, 기본 인원(4동×25명)에서는 적재율이
        // 한계를 넘어 기존 교통 검증에 걸린다. 주기 필드만 격리해 시험하려고 인원을 줄인다.
        assertTrue(check(c -> { c.setResidentsPerBuilding(15); c.setCollectionIntervalDays(2); }).ready());
    }

    // ── 공휴일 ──────────────────────────────────────────────────────────

    /**
     * 엔진의 날짜 인덱스는 0-based({@code for (int d = 0; d < days; d++)})다.
     * 기간 밖 인덱스는 아무 효과 없이 무시되므로, 사용자는 공휴일을 지정했다고 믿지만
     * 실제로는 그 날 수거가 그대로 일어난다.
     */
    @Test
    @DisplayName("기간 밖 공휴일은 조용히 무시하지 말고 거부한다")
    void holidaysOutOfRangeRejected() {
        expectError("holidays", c -> { c.setDays(30); c.setHolidays(List.of(45)); },
                "30일 실험에 45일차 공휴일은 효과가 없다");
        expectError("holidays", c -> { c.setDays(30); c.setHolidays(List.of(-1)); }, "음수 날짜");
        expectError("holidays", c -> { c.setDays(30); c.setHolidays(Arrays.asList(7, 7)); }, "중복 날짜");
        expectError("holidays", c -> { c.setDays(30); c.setHolidays(Arrays.asList(7, null)); }, "null 항목");
    }

    @Test
    @DisplayName("기간 안의 공휴일은 통과한다")
    void holidaysAccepted() {
        assertTrue(check(c -> { c.setDays(30); c.setHolidays(List.of(7, 14, 21)); }).ready());
    }

    // ── 비율·가중치 ─────────────────────────────────────────────────────

    /**
     * {@code SimulationEngine}이 사용 시점에 {@code clamp01}로 잘라내므로 1.5를 넣으면
     * 1.0으로, -0.2를 넣으면 0.0으로 돌아간다 — 오류 없이.
     */
    @Test
    @DisplayName("귀가 배출 비율은 0~1을 벗어나면 거부한다")
    void returnFractionRejected() {
        expectError("returnFraction", c -> c.setReturnFraction(1.5), "clamp01로 조용히 1.0이 된다");
        expectError("returnFraction", c -> c.setReturnFraction(-0.2), "음수 비율");
        expectError("returnFraction", c -> c.setReturnFraction(Double.NaN), "NaN");
    }

    @Test
    @DisplayName("임대인 점검 임계는 0~1을 벗어나면 거부한다")
    void landlordThresholdRejected() {
        expectError("landlordThreshold", c -> c.setLandlordThreshold(1.4), "1 초과면 점검이 영영 발동하지 않는다");
        expectError("landlordThreshold", c -> c.setLandlordThreshold(-1), "음수면 항상 발동한다");
    }

    @Test
    @DisplayName("교통 민원 가중치는 유한한 0 이상이어야 한다")
    void trafficWeightRejected() {
        expectError("trafficComplaintWeight", c -> c.setTrafficComplaintWeight(-1), "음수 가중치는 민원을 상쇄한다");
        expectError("trafficComplaintWeight", c -> c.setTrafficComplaintWeight(Double.POSITIVE_INFINITY), "무한대");
    }

    // ── 하루 중 시각 ────────────────────────────────────────────────────

    /**
     * {@code d * DAY + landlordInspectMinutes}로 이벤트 시각을 만들므로 1440 이상이면
     * 다음 날로 넘어가고 음수면 전날로 간다 — 점검일과 집계일이 어긋난다.
     */
    @Test
    @DisplayName("임대인 점검 시각도 수거 시각과 같은 0~1439 범위를 지켜야 한다")
    void landlordInspectMinutesRejected() {
        expectError("landlordInspectMinutes", c -> c.setLandlordInspectMinutes(1500), "다음 날로 넘어간다");
        expectError("landlordInspectMinutes", c -> c.setLandlordInspectMinutes(-60), "전날로 간다");
    }

    @Test
    @DisplayName("배차 간격은 0~1439 범위를 지켜야 한다")
    void dispatchIntervalRejected() {
        expectError("dispatchIntervalMinutes", c -> c.setDispatchIntervalMinutes(-30),
                "음수 배차는 2호차가 1호차보다 먼저 출발한다");
        expectError("dispatchIntervalMinutes", c -> c.setDispatchIntervalMinutes(2000), "하루를 넘는 배차 간격");
    }

    @Test
    @DisplayName("건물 간 이동시간은 음수일 수 없고 현실적인 상한이 있다")
    void routeTravelMinutesRejected() {
        expectError("routeTravelMinutes", c -> c.setRouteTravelMinutes(-5), "음수 이동시간");
        expectError("routeTravelMinutes", c -> c.setRouteTravelMinutes(5000), "건물 간 이동에 3일이 걸릴 수는 없다");
    }

    // ── 월별 가중치 ─────────────────────────────────────────────────────

    /**
     * {@code resolveMonthlyFactor}가 {@code monthIndex % length}로 접근하므로 길이가
     * 12가 아니면 월과 가중치의 대응이 어긋난다 — 길이 3이면 1·4·7·10월이 같은 값이 된다.
     */
    @Test
    @DisplayName("월별 가중치는 12개여야 하고 각 값이 유한한 양수여야 한다")
    void monthlyFactorRejected() {
        expectError("monthlyWasteFactor", c -> c.setMonthlyWasteFactor(new double[]{1.0, 1.1, 0.9}),
                "길이가 12가 아니면 월 대응이 어긋난다");
        double[] withZero = new double[12];
        Arrays.fill(withZero, 1.0);
        withZero[5] = 0.0;
        expectError("monthlyWasteFactor", c -> c.setMonthlyWasteFactor(withZero), "가중치 0이면 그 달 배출이 사라진다");
        double[] withNan = new double[12];
        Arrays.fill(withNan, 1.0);
        withNan[2] = Double.NaN;
        expectError("monthlyWasteFactor", c -> c.setMonthlyWasteFactor(withNan), "NaN");
    }

    @Test
    @DisplayName("12개짜리 정상 가중치는 통과한다")
    void monthlyFactorAccepted() {
        double[] ok = new double[12];
        Arrays.fill(ok, 1.0);
        assertTrue(check(c -> c.setMonthlyWasteFactor(ok)).ready());
    }

    @Test
    @DisplayName("경로 배정용량은 양수이고 차종 정격용량 이하여야 한다")
    void routeAvailableCapacityRejected() {
        expectError("routeAvailableCapacityKg", c -> c.setRouteAvailableCapacityKg(0.0), "배정용량 0");
        expectError("routeAvailableCapacityKg", c -> c.setRouteAvailableCapacityKg(Double.NaN), "NaN");
        expectError("routeAvailableCapacityKg", c -> {
            c.setTruckType("SMALL_1TON");
            c.setRouteAvailableCapacityKg(1001.0);
        }, "정격용량 초과");
    }

    @Test
    @DisplayName("초기 적재량은 0 이상 배정용량 이하여야 한다")
    void initialTruckLoadRejected() {
        expectError("initialTruckLoadKg", c -> c.setInitialTruckLoadKg(-1), "음수 초기 적재");
        expectError("initialTruckLoadKg", c -> {
            c.setRouteAvailableCapacityKg(800.0);
            c.setInitialTruckLoadKg(801.0);
        }, "배정용량 초과");
    }

    @Test
    @DisplayName("정격 내 배정용량과 초기 적재량은 통과한다")
    void routeCapacityAndInitialLoadAccepted() {
        assertTrue(check(c -> {
            c.setRouteAvailableCapacityKg(800.0);
            c.setInitialTruckLoadKg(200.0);
        }).ready());
    }
}
