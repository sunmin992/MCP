package com.wastesim.service;

import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 수거 시각 <b>비교</b>(WC)와 <b>스윕</b>(WS) 시나리오의 계약을 고정한다.
 *
 * <p>시뮬레이션 엔진은 목으로 대체하고 <b>수거 시각 → 민원 수</b> 함수를 테스트가 직접
 * 정한다. 진짜 엔진을 돌리면 시드 잡음 때문에 "최적 시각이 무엇인가"를 단언할 수 없어
 * 정작 이 시나리오들이 약속하는 것(어느 시각을 고르고, 동률일 때 무엇을 하지 않는가)을
 * 검증할 수 없다 — 여기서 보려는 것은 열 계산의 정확도가 아니라 <b>비교·선택 로직</b>이다.
 */
class CollectionTimeScenarioTest {

    private final SimulationService sim = mock(SimulationService.class);
    private final ScenarioService scenario = new ScenarioService(sim);

    /**
     * 수거 시각 라벨에 따라 민원 수를 정하는 가짜 엔진을 꽂는다.
     * 표준편차는 0으로 둬 잡음이 순위에 끼어들지 않게 한다.
     */
    private void engineReturns(Function<String, Double> complaintsByTime) {
        when(sim.runExperiment(any(SimulationConfig.class))).thenAnswer(inv -> {
            SimulationConfig cfg = inv.getArgument(0);
            SimulationResult r = new SimulationResult();
            r.setMeanComplaints(complaintsByTime.apply(cfg.getCollectionTimeLabel()));
            r.setStdComplaints(0.0);
            return r;
        });
    }

    /** insight는 {"key": 항목명, "value": 값} 한 줄로 쌓인다. 항목명으로 값을 찾는다. */
    private static String insight(ScenarioResponse resp, String key) {
        for (Map<String, Object> m : resp.getInsights()) {
            if (key.equals(m.get("key"))) return String.valueOf(m.get("value"));
        }
        return null;
    }

    // ── WC: 지정 시각 비교 ────────────────────────────────────────────────

    @Test
    @DisplayName("WC-01 두 시각을 같은 조건으로 각각 실행하고 민원이 적은 쪽을 고른다")
    void comparesTwoGivenTimes() {
        engineReturns(t -> "10:00".equals(t) ? 30.0 : 12.0);

        ScenarioResponse resp = scenario.collectionTimeComparison(
                new SimulationConfig(), List.of(10 * 60, 11 * 60));

        assertEquals(List.of("10:00", "11:00"), resp.getXCategories());
        assertEquals(List.of(30.0, 12.0), resp.getSeries().get(0).getValues());
        assertTrue(insight(resp, "민원이 가장 적은 수거 시각").startsWith("11:00"),
                "민원이 적은 11:00이 선택돼야 한다");
        // 비교의 값은 "얼마나 차이 나는가"에 있다 — 차이를 빼면 두 숫자를 나열한 것뿐이다.
        assertEquals("18.0건", insight(resp, "최대 차이"));
    }

    @Test
    @DisplayName("WC-04 시각을 셋 이상 줘도 전부 실행하고 비교한다")
    void comparesThreeOrMoreTimes() {
        engineReturns(t -> switch (t) { case "10:00" -> 30.0; case "11:00" -> 20.0; default -> 25.0; });

        ScenarioResponse resp = scenario.collectionTimeComparison(
                new SimulationConfig(), List.of(10 * 60, 11 * 60, 12 * 60));

        assertEquals(List.of("10:00", "11:00", "12:00"), resp.getXCategories());
        assertEquals(3, resp.getSeries().get(0).getValues().size());
        verify(sim, times(3)).runExperiment(any(SimulationConfig.class));
        assertTrue(insight(resp, "민원이 가장 적은 수거 시각").startsWith("11:00"));
    }

    @Test
    @DisplayName("WC-13 모든 시각의 민원이 같으면 최대 차이가 0으로 나와 우열이 없음이 드러난다")
    void tiedTimesReportZeroSpread() {
        engineReturns(t -> 20.0);

        ScenarioResponse resp = scenario.collectionTimeComparison(
                new SimulationConfig(), List.of(10 * 60, 11 * 60, 12 * 60));

        // 동률이어도 최선 라벨은 하나 나오지만, "최대 차이 0.0건"이 그 라벨을
        // 우열로 읽지 말라고 알려 주는 자리다.
        assertEquals("0.0건", insight(resp, "최대 차이"));
    }

    @Test
    @DisplayName("WC-01 base 설정은 그대로 두고 수거 시각만 바꿔 실행한다")
    void onlyCollectionTimeVariesAcrossRuns() {
        engineReturns(t -> 10.0);
        SimulationConfig base = new SimulationConfig();
        base.setDays(30);
        base.setSeeds(7);

        scenario.collectionTimeComparison(base, List.of(9 * 60, 15 * 60));

        // 비교가 성립하려면 수거 시각 외의 조건이 모든 실행에서 같아야 한다.
        verify(sim, times(2)).runExperiment(argThat(cfg -> cfg.getDays() == 30 && cfg.getSeeds() == 7));
        // 그리고 원본 base는 오염되지 않아야 한다(복사본에만 시각을 주입한다).
        // 기본값 12:00이 그대로 남아 있어야 하며, 마지막 후보 15:00이 눌러 붙으면 안 된다.
        assertEquals(12 * 60, base.getCollectionTimeMinutes(),
                "base에 시각이 눌러 붙으면 다음 시나리오가 그 시각을 물려받는다");
    }

    @Test
    @DisplayName("WC-01 단일 시각이면 비교가 아니므로 최대 차이를 만들지 않는다")
    void singleTimeHasNoSpreadInsight() {
        engineReturns(t -> 10.0);

        ScenarioResponse resp = scenario.collectionTimeComparison(
                new SimulationConfig(), List.of(10 * 60));

        assertEquals(1, resp.getSeries().get(0).getValues().size());
        assertNull(insight(resp, "최대 차이"), "비교 대상이 하나면 차이를 적을 수 없다");
    }

    // ── WS: 시각 스윕 ────────────────────────────────────────────────────

    @Test
    @DisplayName("WS-01·03 06:00~18:00을 60분 간격으로 훑으면 13개 후보를 만든다")
    void hourlySweepCoversWholeRangeInclusive() {
        engineReturns(t -> 10.0);

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 6 * 60, 18 * 60, 60);

        assertEquals(13, resp.getXCategories().size(), "양 끝을 포함해 06시부터 18시까지 13개");
        assertEquals("06:00", resp.getXCategories().get(0));
        assertEquals("18:00", resp.getXCategories().get(12));
    }

    @Test
    @DisplayName("WS-02 30분 간격이면 후보 수가 두 배 가까이 늘어난다")
    void halfHourSweepDoublesCandidates() {
        engineReturns(t -> 10.0);

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 6 * 60, 18 * 60, 30);

        assertEquals(25, resp.getXCategories().size());
        assertEquals("06:30", resp.getXCategories().get(1));
    }

    @Test
    @DisplayName("WS-04 시작과 종료가 같으면 후보가 하나뿐이다")
    void sweepWithEqualStartAndEndHasSingleCandidate() {
        engineReturns(t -> 10.0);

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 9 * 60, 9 * 60, 60);

        assertEquals(List.of("09:00"), resp.getXCategories());
        assertEquals("0.0건", insight(resp, "개선 폭"), "후보가 하나면 개선 폭이 있을 수 없다");
    }

    @Test
    @DisplayName("WS-05 시작이 종료보다 늦으면 후보를 하나도 만들지 않는다")
    void sweepWithInvertedRangeProducesNoCandidates() {
        engineReturns(t -> 10.0);

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 18 * 60, 6 * 60, 60);

        // 현재 구현은 거부가 아니라 빈 결과다. 빈 표를 그대로 내보내지 않도록
        // 호출측이 이 상태를 구분할 수 있어야 하므로 계약으로 고정한다.
        assertTrue(resp.getXCategories().isEmpty());
        verify(sim, never()).runExperiment(any(SimulationConfig.class));
    }

    @Test
    @DisplayName("WS-09 간격이 범위보다 크면 시작 시각 하나만 실행된다")
    void sweepStepLargerThanRangeRunsOnlyStart() {
        engineReturns(t -> 10.0);

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 6 * 60, 10 * 60, 600);

        assertEquals(List.of("06:00"), resp.getXCategories());
    }

    @Test
    @DisplayName("WS-13 뚜렷한 차이가 있으면 최적·최악 시각과 개선 폭을 모두 적는다")
    void sweepReportsBestWorstAndImprovement() {
        engineReturns(t -> switch (t) { case "06:00" -> 40.0; case "12:00" -> 10.0; default -> 25.0; });

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 6 * 60, 18 * 60, 360);

        assertTrue(insight(resp, "최적 수거시각").startsWith("12:00"));
        assertTrue(insight(resp, "최악 수거시각").startsWith("06:00"));
        assertEquals("30.0건", insight(resp, "개선 폭"));
    }

    @Test
    @DisplayName("WS-11 전 구간이 동률이면 개선 폭 0으로 축이 평평함을 드러낸다")
    void flatSweepReportsZeroImprovement() {
        engineReturns(t -> 22.0);

        ScenarioResponse resp = scenario.collectionSweep(new SimulationConfig(), 6 * 60, 18 * 60, 180);

        assertEquals("0.0건", insight(resp, "개선 폭"),
                "개선 폭 0이 곧 '이 축으로는 얻을 게 없다'는 뜻이다");
    }
}
