package com.wastesim.simulation;

import com.wastesim.model.DischargeTimeMode;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationResult;
import com.wastesim.traffic.TravelTimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배출 시각 두 모델.
 *
 * <p>논문 모델은 주민이 <b>집을 나설 때</b> 버린다고 본다(생산직 07:22 · 학생 08:58 ·
 * 주부 14:00). 포항시 공식 배출 규정은 <b>20:00~06:00</b>이다 — 논문의 세 시각 중 어느
 * 것도 그 창 안에 없다. 어느 쪽이 맞는지가 아니라 <b>둘의 차이가 결과를 얼마나 바꾸는지</b>가
 * 볼 만한 것이므로 논문 모델을 지우지 않고 모드로 나눴다.
 */
class DischargeTimeModeTest {

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
        c.setDays(28);
        c.setNumBuildings(4);
        return c;
    }

    private static SimulationConfig actual() {
        SimulationConfig c = base();
        c.setDischargeTimeMode("POHANG_ACTUAL");
        return c;
    }

    // ── 모드 해석과 기본값 ─────────────────────────────────────────────────

    @Test
    void defaultIsThePaperModel() {
        assertEquals(DischargeTimeMode.PAPER_BASELINE, new SimulationConfig().resolveDischargeTimeMode(),
                "기본값이 바뀌면 기존 사용자의 결과가 조용히 달라진다");
        assertEquals(DischargeTimeMode.PAPER_BASELINE, DischargeTimeMode.fromName(null));
        assertEquals(DischargeTimeMode.PAPER_BASELINE, DischargeTimeMode.fromName(""));
    }

    @Test
    void modeNameIsCaseAndHyphenTolerant() {
        assertEquals(DischargeTimeMode.POHANG_ACTUAL, DischargeTimeMode.fromName("pohang_actual"));
        assertEquals(DischargeTimeMode.POHANG_ACTUAL, DischargeTimeMode.fromName("POHANG-ACTUAL"));
        assertThrows(IllegalArgumentException.class, () -> DischargeTimeMode.fromName("POHANG"));
    }

    /** 공식 창 20:00~06:00은 자정을 넘으므로 길이가 600분이다. */
    @Test
    void windowSpanHandlesMidnightCrossing() {
        SimulationConfig c = base();
        assertEquals(20 * 60, c.getDischargeWindowStartMinutes());
        assertEquals(6 * 60, c.getDischargeWindowEndMinutes());
        assertEquals(600, c.dischargeWindowSpanMinutes(), "20:00~06:00 = 10시간");

        c.setDischargeWindowStartMinutes(9 * 60);
        c.setDischargeWindowEndMinutes(18 * 60);
        assertEquals(540, c.dischargeWindowSpanMinutes(), "자정을 넘지 않는 창도 맞아야 한다");
    }

    // ── 기준선: 논문 모델은 움직이지 않는다 ────────────────────────────────

    @Test
    void paperModelResultsAreUnchanged() {
        var a = engine().run(base(), 1);
        var b = engine().run(base(), 1);
        assertEquals(a.getTotalComplaints(), b.getTotalComplaints());

        // 창 설정을 바꿔도 논문 모델은 그것을 보지 않는다.
        SimulationConfig windowChanged = base();
        windowChanged.setDischargeWindowStartMinutes(3 * 60);
        windowChanged.setDischargeWindowEndMinutes(4 * 60);
        assertEquals(a.getTotalComplaints(), engine().run(windowChanged, 1).getTotalComplaints(),
                "논문 모델이 배출 창을 참조하면 안 된다");
    }

    // ── 실제 규정 모드는 다른 결과를 낸다 ──────────────────────────────────

    @Test
    void actualModeChangesTheResult() {
        int paper = engine().run(base(), 1).getTotalComplaints();
        int pohang = engine().run(actual(), 1).getTotalComplaints();

        assertNotEquals(paper, pohang,
                "배출 시각이 07:22~14:00에서 20:00~06:00으로 옮겨졌는데 결과가 같으면 "
                        + "모드가 실제로 갈라지지 않은 것이다. 둘 다 " + paper);
    }

    /**
     * 창을 좁히면 배출이 한 시점에 몰려 쓰레기통이 더 급하게 찬다. 창 길이가 결과에
     * 반영되지 않으면 균등분포가 실제로 창을 쓰지 않는 것이다.
     */
    @Test
    void narrowWindowConcentratesDischarge() {
        SimulationConfig wide = actual();                       // 600분
        SimulationConfig narrow = actual();
        narrow.setDischargeWindowStartMinutes(20 * 60);
        narrow.setDischargeWindowEndMinutes(20 * 60 + 10);      // 10분
        assertEquals(10, narrow.dischargeWindowSpanMinutes());

        assertNotEquals(engine().run(wide, 1).getTotalComplaints(),
                        engine().run(narrow, 1).getTotalComplaints(),
                "창 길이가 결과를 바꾸지 않으면 균등분포가 창을 쓰지 않는 것이다");
    }

    /**
     * <b>이 모드에서는 직업 구성비가 결과를 바꾸지 못한다.</b>
     *
     * <p>이 엔진에서 직업이 좌우하는 것은 외출·귀가 시각뿐이고 배출량은 전역값
     * ({@code wasteMeanKg})이다. 배출 시각을 균등분포로 바꾸면 직업이 관여할 자리가 남지
     * 않는다 — 논문 모델의 "직업 구성이 민원에 영향을 준다"는 기제가 이 모드에서는 사라진다.
     *
     * <p>감춰야 할 결과가 아니라 <b>이 모드를 고를 때 알아야 할 사실</b>이라서 단언으로
     * 고정한다. 나중에 직업별 배출량이나 직업별 배출 시각 선호가 모델에 들어오면 이 단언이
     * 깨지고, 그때가 이 서술을 고칠 시점이다.
     */
    @Test
    void occupationNoLongerAffectsTimingInActualMode() {
        SimulationConfig blueOnly = actual();
        blueOnly.setOccupationMix(List.of("BlueCollar"));
        SimulationConfig houseOnly = actual();
        houseOnly.setOccupationMix(List.of("Housewife"));

        assertEquals(engine().run(blueOnly, 1).getTotalComplaints(),
                     engine().run(houseOnly, 1).getTotalComplaints(),
                "실제 규정 모드에서 직업은 배출 시각에 영향을 주지 않는다");

        // 대조군 — 논문 모델에서는 직업이 결과를 바꾼다(07:22 vs 14:00).
        SimulationConfig paperBlue = base();
        paperBlue.setOccupationMix(List.of("BlueCollar"));
        SimulationConfig paperHouse = base();
        paperHouse.setOccupationMix(List.of("Housewife"));
        assertNotEquals(engine().run(paperBlue, 1).getTotalComplaints(),
                        engine().run(paperHouse, 1).getTotalComplaints(),
                "논문 모델에서는 직업이 결과를 바꿔야 한다");
    }

    /**
     * 창이 자정을 넘으므로 배출의 일부가 <b>다음 날</b>로 넘어간다. 이것이 공식 데이터의
     * "일요일 배출 → 월요일 새벽 수거"가 성립하는 방식이다.
     *
     * <p>일요일만 수거하는 스케줄과 월요일만 수거하는 스케줄을 비교한다. 배출이 전부 같은
     * 날에 머문다면 두 스케줄의 차이가 단순히 하루 밀린 것에 그쳐야 하지만, 창이 자정을
     * 넘으면 각 스케줄이 잡아내는 배출의 조합이 달라진다.
     */
    @Test
    void windowSpillsIntoTheNextDay() {
        SimulationConfig sunday = actual();
        sunday.setCollectionDaysOfWeek(List.of(6));
        SimulationConfig monday = actual();
        monday.setCollectionDaysOfWeek(List.of(0));

        assertNotEquals(engine().run(sunday, 1).getTotalComplaints(),
                        engine().run(monday, 1).getTotalComplaints(),
                "자정을 넘는 창에서 수거 요일이 하루 다르면 잡아내는 배출이 달라진다");
    }

    /** 실제 규정 두 축(요일 집합 + 배출 창)을 함께 쓴 설정이 검증을 통과해야 한다. */
    @Test
    void pohangActualScheduleAndWindowValidateTogether() {
        SimulationConfig c = actual();
        c.setCollectionDaysOfWeek(List.of(0, 1, 3, 4));   // 월화목금
        ValidationResult r = validator().validate(c);
        assertTrue(r.ready(), r.errors().toString());
    }

    // ── V-D2 검증 ──────────────────────────────────────────────────────────

    @Test
    void rejectsUnknownMode() {
        SimulationConfig c = base();
        c.setDischargeTimeMode("POHANG");
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> "dischargeTimeMode".equals(e.field())),
                r.errors().toString());
    }

    @Test
    void rejectsWindowTimeOutOfRange() {
        SimulationConfig c = actual();
        c.setDischargeWindowStartMinutes(1440);
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream()
                .anyMatch(e -> "dischargeWindowStartMinutes".equals(e.field())), r.errors().toString());
    }

    /** 시작과 종료가 같으면 모든 주민이 같은 순간에 버리게 되고, 그건 "창"이 아니다. */
    @Test
    void rejectsZeroLengthWindow() {
        SimulationConfig c = actual();
        c.setDischargeWindowStartMinutes(20 * 60);
        c.setDischargeWindowEndMinutes(20 * 60);
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("같은 순간")),
                r.errors().toString());
    }

    /** 자정을 넘는 창은 정상이다 — 시작 &gt; 종료를 오류로 보면 공식 규정을 표현할 수 없다. */
    @Test
    void midnightCrossingWindowIsAccepted() {
        assertTrue(validator().validate(actual()).ready(),
                "20:00~06:00이 거부되면 공식 배출 규정을 쓸 수 없다");
    }
}
