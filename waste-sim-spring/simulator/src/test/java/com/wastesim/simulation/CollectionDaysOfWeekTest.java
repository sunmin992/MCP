package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.WasteType;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationResult;
import com.wastesim.traffic.TravelTimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요일 집합 수거 스케줄과 미수거일.
 *
 * <p>포항시 북구의 실제 생활쓰레기 수거는 <b>월·화·목·금</b>이다(공식 배출요일 일·월·수·목에
 * 하루를 더한 것 — 배출 창이 20:00~06:00으로 자정을 넘기므로). 일요일은 미수거일이고, 그것은
 * 요일 집합에서 빠지는 것으로 표현된다. 이런 스케줄은 {@code collectionIntervalDays}("N일마다")
 * 로는 쓸 방법이 없다.
 *
 * <p>가장 중요한 단언은 <b>지정하지 않으면 예전과 같다</b>는 것이다. 요일 축을 더하면서 기존
 * 결과가 움직이면 지금까지의 모든 비교가 무의미해진다.
 */
class CollectionDaysOfWeekTest {

    // 0=월 1=화 2=수 3=목 4=금 5=토 6=일
    private static final List<Integer> POHANG_GENERAL = List.of(0, 1, 3, 4);   // 월화목금

    private static SimulationEngine engine() {
        return new SimulationEngine(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfigValidator validator() {
        return new SimulationConfigValidator(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfig base() {
        SimulationConfig c = new SimulationConfig();
        c.setDays(28);          // 4주 — 요일 패턴이 네 번 반복된다
        c.setNumBuildings(4);
        return c;
    }

    // ── 요일 규약 ──────────────────────────────────────────────────────────

    /** 0일차가 월요일이라는 규약. 요일 집합 스케줄의 결과가 여기에 달려 있다. */
    @Test
    void dayZeroIsMonday() {
        assertEquals(0, SimulationEngine.dayOfWeek(0), "0일차 = 월요일");
        assertEquals(5, SimulationEngine.dayOfWeek(5), "5일차 = 토요일");
        assertEquals(6, SimulationEngine.dayOfWeek(6), "6일차 = 일요일");
        assertEquals(0, SimulationEngine.dayOfWeek(7), "7일차 = 다시 월요일");
        assertEquals(3, SimulationEngine.dayOfWeek(24));
    }

    // ── 기준선: 지정하지 않으면 예전과 같다 ────────────────────────────────

    @Test
    void unspecifiedScheduleLeavesResultsUnchanged() {
        SimulationConfig c = base();
        assertFalse(c.usesDaysOfWeek(), "기본값은 요일 집합을 쓰지 않는다");

        var a = engine().run(c, 1);
        var b = engine().run(base(), 1);
        assertEquals(a.getAvgCompletionMinutes(), b.getAvgCompletionMinutes());
        assertEquals(a.getTotalComplaints(), b.getTotalComplaints());
        assertEquals(a.getCollectedWasteKg(), b.getCollectedWasteKg());
    }

    // ── 요일 집합이 실제로 수거 날을 가른다 ────────────────────────────────

    /**
     * 월화목금(주 4일)은 매일 수거보다 쓰레기가 더 쌓인다 — 특히 금요일 수거 후 월요일까지
     * 이틀 반이 비는 구간이 생긴다. 민원이 늘지 않으면 스케줄이 반영되지 않은 것이다.
     */
    @Test
    void fourDayScheduleAccumulatesMoreThanDaily() {
        SimulationConfig daily = base();
        SimulationConfig fourDay = base();
        fourDay.setCollectionDaysOfWeek(POHANG_GENERAL);

        var d = engine().run(daily, 1);
        var f = engine().run(fourDay, 1);

        assertTrue(f.getTotalComplaints() > d.getTotalComplaints(),
                "주 4일(" + f.getTotalComplaints() + ")이 매일(" + d.getTotalComplaints()
                        + ")보다 민원이 많아야 한다");
        // 잔여량은 여기서 비교하지 않는다 — 아래 residualIsNotComparableAcrossSchedules 참고.
    }

    /** 미수거일이 실제로 지켜지는지 — 일요일만 수거하는 설정과 일요일을 뺀 설정의 대비. */
    @Test
    void nonCollectionDayIsHonoured() {
        SimulationConfig sundayOnly = base();
        sundayOnly.setCollectionDaysOfWeek(List.of(6));       // 일요일만
        SimulationConfig everyDayButSunday = base();
        everyDayButSunday.setCollectionDaysOfWeek(List.of(0, 1, 2, 3, 4, 5));

        var s = engine().run(sundayOnly, 1);
        var e = engine().run(everyDayButSunday, 1);

        assertTrue(s.getTotalComplaints() > e.getTotalComplaints(),
                "주 1회(" + s.getTotalComplaints() + ")가 주 6회("
                        + e.getTotalComplaints() + ")보다 민원이 많아야 한다");
    }

    /**
     * 요일 집합은 "N일마다"로 표현되지 않는다 — 이것이 이 기능의 존재 이유다.
     * 월화목금은 어떤 주기로도 재현할 수 없다(간격이 1,2,1,3일로 불규칙하다).
     */
    @Test
    void dayOfWeekScheduleIsNotExpressibleAsAnInterval() {
        SimulationConfig byDays = base();
        byDays.setCollectionDaysOfWeek(POHANG_GENERAL);
        double target = engine().run(byDays, 1).getTotalComplaints();

        for (int interval = 1; interval <= 7; interval++) {
            SimulationConfig byInterval = base();
            byInterval.setCollectionIntervalDays(interval);
            double got = engine().run(byInterval, 1).getTotalComplaints();
            assertNotEquals(target, got, 0.0001,
                    interval + "일 주기가 월화목금과 같은 결과를 내면 요일 집합이 불필요하다");
        }
    }

    /**
     * <b>잔여량은 스케줄끼리 비교할 수 없다.</b> {@code residualWasteKg}는 종료 시점의
     * 스냅샷이라, 마지막 날이 수거일인지에 따라 값이 크게 튄다.
     *
     * <p>처음 이 클래스를 쓸 때 "주 1회가 주 6회보다 잔여가 많다"를 단언했다가 실패했다.
     * {@code days=28}이면 마지막 날이 27일차 = 일요일이어서, <b>일요일에만 수거하는 설정이
     * 오히려 마지막 날에 비우고 끝난다</b>. 반대로 일요일을 뺀 설정은 하루치를 남긴 채
     * 끝난다. 즉 그 단언은 축적량이 아니라 "마지막 날 운이 좋았는지"를 재고 있었다.
     *
     * <p>같은 스케줄에서 기간만 하루 늘려도 값이 뒤집히는 것으로 그 성질을 고정한다.
     * 스케줄 간 축적 비교는 기간 전체에 누적되는 {@code totalComplaints}로 한다.
     */
    @Test
    void residualIsNotComparableAcrossSchedules() {
        // 월화목금: 27일차(일)에는 수거 없음, 28일차(월)에는 수거 있음.
        SimulationConfig endsOnSunday = base();          // days=28 -> 마지막 27일차 = 일
        endsOnSunday.setCollectionDaysOfWeek(POHANG_GENERAL);
        SimulationConfig endsOnMonday = base();
        endsOnMonday.setDays(29);                        // 마지막 28일차 = 월
        endsOnMonday.setCollectionDaysOfWeek(POHANG_GENERAL);

        double sun = engine().run(endsOnSunday, 1).getResidualWasteKg();
        double mon = engine().run(endsOnMonday, 1).getResidualWasteKg();

        assertEquals(6, SimulationEngine.dayOfWeek(27), "27일차는 일요일");
        assertEquals(0, SimulationEngine.dayOfWeek(28), "28일차는 월요일");
        assertTrue(mon < sun,
                "수거일에 끝나면 잔여가 적어야 한다. 일요일 종료 " + sun + "kg, 월요일 종료 " + mon + "kg");
    }

    /**
     * 검증기를 거치지 않고 들어와도 <b>요일 집합이 주기를 대신한다</b>. 검증기는 둘의 동시
     * 지정을 거부하지만(V-D1), 엔진의 계약은 그 가드와 독립적으로 성립해야 한다.
     *
     * <p>둘을 함께 적용(AND)하면 교집합이 비어 수거가 한 번도 없는 실행이 만들어질 수 있고,
     * 그 결과는 "수거가 없어서 민원이 폭증했다"처럼 그럴듯하게 보인다 — 설정 오류가 결과와
     * 구별되지 않는다. 그래서 요일 집합이 있으면 주기는 보지 않는다.
     */
    @Test
    void daySetOverridesIntervalEvenWithoutValidation() {
        // 주기 5일과 월화목금은 교집합이 거의 없다(0·5·10·15·20·25일차 중 월화목금은 0·20 뿐).
        SimulationConfig both = base();
        both.setCollectionDaysOfWeek(POHANG_GENERAL);
        both.setCollectionIntervalDays(5);

        SimulationConfig daysOnly = base();
        daysOnly.setCollectionDaysOfWeek(POHANG_GENERAL);

        assertFalse(validator().validate(both).ready(), "검증기는 이 조합을 거부한다");
        assertEquals(engine().run(daysOnly, 1).getTotalComplaints(),
                     engine().run(both, 1).getTotalComplaints(),
                "요일 집합이 지정됐으면 주기는 결과에 영향을 주지 않아야 한다");
    }

    // ── 종류별 요일 ────────────────────────────────────────────────────────

    /** 실제로는 종류마다 요일이 다르다 — 생활 월화목금, 음식물 화목토, 재활용 수토. */
    @Test
    void perWasteTypeDaysAreHonoured() {
        SimulationConfig c = base();
        List<WasteType> types = new ArrayList<>();
        types.add(new WasteType("GENERAL", "일반", 0.5, 30, 0.8, 1).withDaysOfWeek(List.of(0, 1, 3, 4)));
        types.add(new WasteType("FOOD", "음식물", 0.3, 10, 0.7, 1).withDaysOfWeek(List.of(1, 3, 5)));
        types.add(new WasteType("RECYCLING", "재활용", 0.2, 50, 0.9, 1).withDaysOfWeek(List.of(2, 5)));
        c.setWasteTypes(types);

        assertTrue(validator().validate(c).ready(), "공식 스케줄이 검증을 통과해야 한다");

        // 종류별 요일을 지정한 쪽이, 같은 종류를 매일 수거하는 쪽보다 더 쌓인다.
        SimulationConfig everyday = base();
        everyday.setWasteTypes(WasteType.defaultSeparated().stream()
                .map(t -> new WasteType(t.getKey(), t.getLabelKo(), t.getFraction(),
                        t.getCapacity(), t.getThreshold(), 1)).toList());

        assertTrue(engine().run(c, 1).getResidualWasteKg()
                        > engine().run(everyday, 1).getResidualWasteKg(),
                "요일이 제한되면 남는 쓰레기가 더 많아야 한다");
    }

    // ── V-D1 검증 ──────────────────────────────────────────────────────────

    @Test
    void rejectsOutOfRangeDay() {
        SimulationConfig c = base();
        c.setCollectionDaysOfWeek(List.of(0, 7));
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> "collectionDaysOfWeek".equals(e.field())),
                r.errors().toString());
    }

    @Test
    void rejectsEmptyDaySet() {
        SimulationConfig c = base();
        c.setCollectionDaysOfWeek(List.of());
        assertFalse(validator().validate(c).ready());
    }

    /**
     * 둘을 함께 지정하면 교집합이 비어 수거가 한 번도 없을 수 있다. 그런 설정은 "수거가
     * 없어서 민원이 폭증한다"는 그럴듯한 결과를 내므로, 설정 오류가 결과처럼 보인다.
     */
    @Test
    void rejectsDaySetTogetherWithInterval() {
        SimulationConfig c = base();
        c.setCollectionDaysOfWeek(POHANG_GENERAL);
        c.setCollectionIntervalDays(3);
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream()
                        .anyMatch(e -> e.message().contains("하나만 쓰세요")), r.errors().toString());
    }

    @Test
    void rejectsWeekendDayWhenWeekendsAreSkipped() {
        SimulationConfig c = base();
        c.setCollectionDaysOfWeek(List.of(0, 5));   // 월 + 토
        c.setSkipWeekends(true);
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("영영 수거되지 않습니다")),
                r.errors().toString());
    }

    @Test
    void rejectsOutOfRangeDayOnAWasteType() {
        SimulationConfig c = base();
        c.setWasteTypes(List.of(
                new WasteType("GENERAL", "일반", 1.0, 30, 0.8, 1).withDaysOfWeek(List.of(-1))));
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.field().contains("GENERAL")),
                r.errors().toString());
    }

    @Test
    void acceptsPohangScheduleOnItsOwn() {
        SimulationConfig c = base();
        c.setCollectionDaysOfWeek(POHANG_GENERAL);
        assertTrue(validator().validate(c).ready(),
                validator().validate(c).errors().toString());
    }
}
