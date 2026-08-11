package com.wastesim.service;

import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.simulation.TravelTimeCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 차종 × 방문 순서 탐색 시나리오 검증.
 *
 * <p>이 실험의 존재 이유는 두 축의 <b>상호작용</b>이다 — 1톤 트럭은 골목을 빨리 돌지만
 * 용량이 작아 많이 쌓인 건물을 뒤에 두면 용량이 먼저 바닥난다. 즉 어느 차종이 유리한지가
 * 방문 순서에 따라 뒤집힌다. 그래서 "격자를 빠짐없이 돌았는가"와 "최소값을 실제로
 * 골랐는가"가 이 시나리오의 합격선이고, 아래 테스트가 그 둘을 고정한다.
 *
 * <p>엔진은 mock으로 대체한다 — 여기서 검증할 것은 열역학이나 DEVS가 아니라 탐색 로직
 * 자체이고, 실제 엔진을 돌리면 조합 수만큼 다중 시드 실험이 돌아 단위 테스트가 분 단위로 늘어난다.
 */
class TruckRouteSearchTest {

    /** 설정 → 월 평균 민원을 정해 주는 가짜 엔진. */
    private ScenarioService serviceReturning(Function<SimulationConfig, Double> meanOf) {
        SimulationService sim = mock(SimulationService.class);
        when(sim.runExperiment(any())).thenAnswer(inv -> {
            SimulationConfig cfg = inv.getArgument(0);
            SimulationResult r = new SimulationResult();
            r.setMeanComplaints(meanOf.apply(cfg));
            r.setStdComplaints(0.0);
            return r;
        });
        return new ScenarioService(sim);
    }

    private SimulationConfig base(int buildings) {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setNumBuildings(buildings);
        return cfg;
    }

    private Object insight(ScenarioResponse resp, String key) {
        return resp.getInsights().stream()
                .filter(m -> key.equals(m.get("key")))
                .findFirst().orElseThrow(() -> new AssertionError("insight 없음: " + key));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> combo(ScenarioResponse resp, String key) {
        return (Map<String, Object>) insight(resp, key);
    }

    @Test
    @DisplayName("건물 3개면 3! = 6가지 순서를 전 차종과 교차해 빠짐없이 돈다")
    void searchesFullGridWhenPermutationsAreSmall() {
        ScenarioService svc = serviceReturning(cfg -> 5.0);
        ScenarioResponse resp = svc.truckRouteSearch(base(3), null, null);

        assertEquals(6, resp.getXCategories().size(), "3! = 6가지 순서를 모두 x축에 둬야 한다");
        assertEquals(3, resp.getSeries().size(), "차종 3종이 각각 계열이어야 한다");
        for (ScenarioResponse.Series s : resp.getSeries()) {
            assertEquals(6, s.getValues().size(), "모든 차종이 모든 순서에서 실행돼야 격자가 성립한다");
        }
        assertEquals("3차종 × 6순서 = 18가지", ((Map<?, ?>) insight(resp, "탐색 조합 수")).get("value"));
    }

    @Test
    @DisplayName("전수 탐색이면 '전수가 아니다' 경고를 붙이지 않는다")
    void noPartialSearchWarningWhenExhaustive() {
        ScenarioService svc = serviceReturning(cfg -> 5.0);
        ScenarioResponse resp = svc.truckRouteSearch(base(3), null, null);

        assertTrue(resp.getInsights().stream().noneMatch(m -> "탐색 범위".equals(m.get("key"))),
                "전수를 돌았는데 부분 탐색이라고 알리면 안 된다");
    }

    @Test
    @DisplayName("민원이 가장 적은 차종·순서 조합을 최적으로 고른다")
    void picksMinimumComplaintCombination() {
        // 1톤 + C→B→A 조합에서만 1.0, 나머지는 9.0 — 최적이 유일하게 정해진다.
        ScenarioService svc = serviceReturning(cfg ->
                "SMALL_1TON".equals(cfg.getTruckType())
                        && List.of("Node_C", "Node_B", "Node_A").equals(cfg.getRouteSequence())
                        ? 1.0 : 9.0);

        ScenarioResponse resp = svc.truckRouteSearch(base(3), null, null);

        Map<String, Object> best = combo(resp, "최적 조합");
        assertEquals("SMALL_1TON", best.get("truckType"));
        assertEquals(List.of("Node_C", "Node_B", "Node_A"), best.get("routeSequence"));
        assertEquals(1.0, (Double) best.get("mean"), 1e-9);

        // 개선 폭은 최악(9.0) − 최적(1.0)
        assertEquals("8.0건", ((Map<?, ?>) insight(resp, "개선 폭")).get("value"));
    }

    @Test
    @DisplayName("차종이 유리한지가 순서에 따라 뒤집혀도 전역 최소를 찾는다")
    void findsGlobalMinimumWhenAxesInteract() {
        // 정방향에서는 5톤이 유리(2.0 vs 6.0)하지만, 역방향에서는 1톤이 더 유리(1.0)하다.
        // 축을 따로 훑으면 정방향에서 이긴 5톤에 갇혀 1.0을 놓친다.
        ScenarioService svc = serviceReturning(cfg -> {
            boolean reversed = List.of("Node_C", "Node_B", "Node_A").equals(cfg.getRouteSequence());
            return switch (cfg.getTruckType()) {
                case "LARGE_5TON" -> reversed ? 4.0 : 2.0;
                case "SMALL_1TON" -> reversed ? 1.0 : 6.0;
                default -> 5.0;
            };
        });

        Map<String, Object> best = combo(svc.truckRouteSearch(base(3), null, null), "최적 조합");
        assertEquals("SMALL_1TON", best.get("truckType"));
        assertEquals(List.of("Node_C", "Node_B", "Node_A"), best.get("routeSequence"));
    }

    @Test
    @DisplayName("순서 후보를 직접 주면 그것만 돌고, 전수가 아님을 밝힌다")
    void honoursExplicitRouteCandidates() {
        ScenarioService svc = serviceReturning(cfg -> 5.0);
        List<List<String>> candidates = List.of(
                List.of("Node_A", "Node_B", "Node_C"),
                List.of("Node_B", "Node_A", "Node_C"));

        ScenarioResponse resp = svc.truckRouteSearch(base(3), candidates, null);

        assertEquals(List.of("A→B→C", "B→A→C"), resp.getXCategories());
        assertNotNull(insight(resp, "탐색 범위"), "부분 탐색이면 그 사실을 반드시 알려야 한다");
    }

    @Test
    @DisplayName("차종을 지정하면 그 차종만 비교한다")
    void honoursExplicitTruckTypes() {
        ScenarioService svc = serviceReturning(cfg -> 5.0);
        ScenarioResponse resp = svc.truckRouteSearch(base(3), null, List.of("SMALL_1TON", "LARGE_5TON"));

        assertEquals(2, resp.getSeries().size());
        assertEquals(List.of("1톤", "5톤"),
                resp.getSeries().stream().map(ScenarioResponse.Series::getName).toList());
    }

    @Test
    @DisplayName("같은 차종을 여러 번 적어도 격자가 부풀지 않는다")
    void deduplicatesTruckTypes() {
        ScenarioService svc = serviceReturning(cfg -> 5.0);
        ScenarioResponse resp = svc.truckRouteSearch(base(3), null,
                List.of("SMALL_1TON", "small_1ton", "SMALL_1TON"));

        assertEquals(1, resp.getSeries().size());
    }

    @Test
    @DisplayName("건물이 많아 전수가 불가능하면 후보를 지어내지 않고 범위를 밝힌다")
    void doesNotInventCandidatesBeyondExhaustiveLimit() {
        // 5! = 120 > 24(MAX_AUTO_PERMUTATIONS) — 대표 후보(정방향·역방향)만 돈다.
        ScenarioService svc = serviceReturning(cfg -> 5.0);
        ScenarioResponse resp = svc.truckRouteSearch(base(5), null, null);

        assertEquals(2, resp.getXCategories().size());
        assertEquals(List.of("A→B→C→D→E", "E→D→C→B→A"), resp.getXCategories());
        String range = String.valueOf(((Map<?, ?>) insight(resp, "탐색 범위")).get("value"));
        assertTrue(range.contains("120"), "가능한 순서가 몇 가지인지 알려줘야 한다: " + range);
    }

    @Test
    @DisplayName("이동시간이 0이면 기본값을 채우고 무엇을 가정했는지 밝힌다")
    void fillsTravelTimeAndDisclosesIt() {
        // 이동시간이 0이면 모든 건물이 같은 시각에 수거돼 순서가 결과에 반영될 여지가 없다.
        SimulationService sim = mock(SimulationService.class);
        when(sim.runExperiment(any())).thenAnswer(inv -> {
            SimulationConfig cfg = inv.getArgument(0);
            assertEquals(TravelTimeCalculator.DEFAULT_ROUTE_TRAVEL_MINUTES, cfg.getRouteTravelMinutes(),
                    "탐색에 넘기는 설정에 이동시간이 채워져 있어야 한다");
            SimulationResult r = new SimulationResult();
            r.setMeanComplaints(5.0);
            return r;
        });

        ScenarioResponse resp = new ScenarioService(sim).truckRouteSearch(base(3), null, null);
        assertNotNull(insight(resp, "가정"), "조용히 채우지 말고 무엇을 가정했는지 밝혀야 한다");
    }

    @Test
    @DisplayName("이동시간을 지정하면 그대로 쓰고 가정 문구를 붙이지 않는다")
    void keepsExplicitTravelTime() {
        SimulationService sim = mock(SimulationService.class);
        when(sim.runExperiment(any())).thenAnswer(inv -> {
            SimulationConfig cfg = inv.getArgument(0);
            assertEquals(25, cfg.getRouteTravelMinutes());
            SimulationResult r = new SimulationResult();
            r.setMeanComplaints(5.0);
            return r;
        });

        SimulationConfig cfg = base(3);
        cfg.setRouteTravelMinutes(25);
        ScenarioResponse resp = new ScenarioService(sim).truckRouteSearch(cfg, null, null);

        assertTrue(resp.getInsights().stream().noneMatch(m -> "가정".equals(m.get("key"))),
                "사용자가 지정한 값에 대해 '가정했다'고 말하면 안 된다");
    }

    @Test
    @DisplayName("최적 조합 insight는 사람이 읽는 value와 기계가 읽는 필드를 함께 담는다")
    void comboInsightCarriesBothViews() {
        ScenarioService svc = serviceReturning(cfg ->
                "MEDIUM_2P5T".equals(cfg.getTruckType()) ? 2.0 : 8.0);

        Map<String, Object> best = combo(svc.truckRouteSearch(base(3), null, null), "최적 조합");

        // UI 공통 렌더러는 value만 읽는다 — 없으면 화면에 undefined가 찍힌다.
        assertNotNull(best.get("value"));
        assertTrue(String.valueOf(best.get("value")).contains("2.5톤"));
        // MCP·REST 소비자는 문자열을 되파싱하지 않고 조합을 그대로 재현할 수 있어야 한다.
        assertEquals("MEDIUM_2P5T", best.get("truckType"));
        assertInstanceOf(List.class, best.get("routeSequence"));
    }

    // ── 격자가 평평할 때 없는 우열을 만들지 않는다 ──────────────────────────
    //
    // 실측으로 확인된 상황: 건물 3개·이동시간 15분 기본 조건에서는 18개 조합이 전부
    // 9.3건으로 같게 나온다. 이때 "차종 축의 영향이 더 큽니다"라고 결론을 내면
    // 표에는 같은 숫자만 늘어서 있는데 결론만 승자를 지목하는 상태가 된다.

    @Test
    @DisplayName("전 조합이 동률이면 축 순위를 매기지 않고 무엇을 바꿔야 하는지 알린다")
    void doesNotRankAxesWhenGridIsFlat() {
        ScenarioService svc = serviceReturning(cfg -> 9.3);   // 어떤 조합이든 같은 값
        ScenarioResponse resp = svc.truckRouteSearch(base(3), null, null);

        String axis = String.valueOf(((Map<?, ?>) insight(resp, "축별 효과")).get("value"));
        assertFalse(axis.contains("더 큽니다"), "0건 차이로 승자를 지목하면 안 된다: " + axis);
        assertTrue(axis.contains("바꾸지 못했습니다"), "차이가 없다는 사실을 먼저 말해야 한다: " + axis);
        // 사용자가 다음에 무엇을 할 수 있는지까지 알려야 실험이 막히지 않는다.
        assertTrue(axis.contains("이동시간") && axis.contains("거주민"),
                "무엇을 올려야 축이 살아나는지 알려야 한다: " + axis);
    }

    @Test
    @DisplayName("동률이면 최적·최악 조합에도 우열이 없음을 표시한다")
    void marksCombosAsTiedWhenGridIsFlat() {
        ScenarioService svc = serviceReturning(cfg -> 9.3);
        Map<String, Object> best = combo(svc.truckRouteSearch(base(3), null, null), "최적 조합");

        assertEquals(Boolean.TRUE, best.get("tied"));
        assertTrue(String.valueOf(best.get("value")).contains("동률"));
    }

    @Test
    @DisplayName("차이가 있으면 종전대로 우세한 축을 지목한다")
    void ranksAxesWhenGridIsNotFlat() {
        // 순서는 결과를 안 바꾸고 차종만 바꾼다 → 차종 축이 우세.
        ScenarioService svc = serviceReturning(cfg ->
                "SMALL_1TON".equals(cfg.getTruckType()) ? 4.0 : 9.0);
        ScenarioResponse resp = svc.truckRouteSearch(base(3), null, null);

        String axis = String.valueOf(((Map<?, ?>) insight(resp, "축별 효과")).get("value"));
        assertTrue(axis.contains("차종 축의 영향이 더 큽니다"), axis);
        assertEquals(Boolean.FALSE, combo(resp, "최적 조합").get("tied"));
    }

    @Test
    @DisplayName("같은 입력은 같은 결과를 낸다 — 후보에 무작위를 섞지 않는다(NFR-02)")
    void isDeterministic() {
        ScenarioService svc = serviceReturning(cfg -> cfg.getRouteSequence().hashCode() % 7 + 10.0);

        ScenarioResponse a = svc.truckRouteSearch(base(4), null, null);
        ScenarioResponse b = svc.truckRouteSearch(base(4), null, null);

        assertEquals(a.getXCategories(), b.getXCategories());
        assertEquals(combo(a, "최적 조합").get("routeSequence"), combo(b, "최적 조합").get("routeSequence"));
        assertEquals(combo(a, "최적 조합").get("truckType"), combo(b, "최적 조합").get("truckType"));
    }
}
