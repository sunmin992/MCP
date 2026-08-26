package com.wastesim.service;

import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.simulation.SimulationEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 월별 배출량 시나리오가 <b>잡음 위에 순위를 세우지 않는지</b> 고정한다.
 *
 * <p>예전에는 {@code avg > bestV} 엄격 비교로 최댓값 하나를 골랐다. 그런데 이 시뮬레이션은
 * 확률적이라 <b>가중치가 완전히 같은 달들도 매번 다른 값</b>이 나온다 — 12개월 가중치를 전부
 * 1.0으로 두고 돌려도 "5월이 최다"처럼 특정 달이 뽑혔다. 그건 계절성이 아니라 난수가 정한
 * 순위다. 정확한 동률만 찾아서는 이 문제가 잡히지 않는다(실제로 12개월을 같은 가중치로 둬도
 * 정확히 같은 값이 나오는 일은 사실상 없다).
 *
 * <p>그래서 시드 간 표준오차를 잡음 척도로 삼아, 그 안에 들어오는 달들은 최댓값과 구별되지
 * 않는다고 보고 함께 적는다(D-25·D-32 — 없는 우열을 만들지 않는다).
 */
class MonthlyWasteTieTest {

    private final ScenarioService scenario =
            new ScenarioService(new SimulationService(new SimulationEngine(new TrafficDataService())));

    /** 시드를 여러 개 줘야 잡음 척도를 추정할 수 있다. */
    private SimulationConfig base(int seeds) {
        SimulationConfig c = new SimulationConfig();
        c.setSeeds(seeds);
        c.setNumBuildings(2);
        c.setResidentsPerBuilding(10);
        return c;
    }

    private static String insight(ScenarioResponse r, String key) {
        Optional<Map<String, Object>> hit = r.getInsights().stream()
                .filter(m -> key.equals(m.get("key"))).findFirst();
        assertTrue(hit.isPresent(), () -> key + " 인사이트가 없다: " + r.getInsights());
        return String.valueOf(hit.get().get("value"));
    }

    private static boolean has(ScenarioResponse r, String key) {
        return r.getInsights().stream().anyMatch(m -> key.equals(m.get("key")));
    }

    private static double[] flatWeights() {
        double[] w = new double[12];
        Arrays.fill(w, 1.0);
        return w;
    }

    @Test
    @DisplayName("가중치가 평탄하면 한 달을 최다로 뽑지 않는다 — 난수가 정한 순위를 계절성으로 보고하지 않는다")
    void flatWeightsDoNotProduceASingleWinner() {
        ScenarioResponse r = scenario.monthlyWaste(base(4), flatWeights());

        String max = insight(r, "최다 배출 월");
        assertTrue(max.contains("·"),
                "12개월 가중치가 같은데 한 달만 최다로 적으면 없는 계절성을 만든 것이다. 받은 값: " + max);
        assertTrue(has(r, "순위 신뢰도"),
                "왜 여러 달을 함께 적었는지(잡음 범위)를 알려야 사용자가 해석할 수 있다");
    }

    @Test
    @DisplayName("계절 차이가 뚜렷하면 최다·최소 월이 하나씩만 나온다")
    void distinctWeightsGiveASingleWinner() {
        // 7월만 크게, 2월만 작게 — 잡음보다 훨씬 큰 차이를 준다.
        double[] w = flatWeights();
        w[6] = 4.0;   // 7월
        w[1] = 0.2;   // 2월

        ScenarioResponse r = scenario.monthlyWaste(base(4), w);

        assertEquals("7월", insight(r, "최다 배출 월").split(" ")[0],
                "가장 큰 가중치를 준 달이 유일한 최다여야 한다");
        assertEquals("2월", insight(r, "최소 배출 월").split(" ")[0]);
        assertFalse(has(r, "순위 신뢰도"),
                "차이가 뚜렷한데 '구별되지 않는다'고 하면 반대 방향의 오해가 생긴다");
    }

    @Test
    @DisplayName("최댓값이 두 달에 걸리면 둘 다 적는다 — 이른 달이 조용히 이기지 않는다")
    void twoWayTieListsBothMonths() {
        double[] w = flatWeights();
        w[2] = 3.0;   // 3월
        w[9] = 3.0;   // 10월

        ScenarioResponse r = scenario.monthlyWaste(base(4), w);
        String max = insight(r, "최다 배출 월");

        assertTrue(max.contains("3월") && max.contains("10월"),
                "같은 가중치를 준 두 달이 모두 나와야 한다. 받은 값: " + max);
        assertTrue(has(r, "순위 신뢰도"));
    }

    @Test
    @DisplayName("시드가 1개면 잡음을 추정할 수 없으므로 순위를 단정하지 않는다")
    void singleSeedDisclosesThatRankingIsOneRealization() {
        ScenarioResponse r = scenario.monthlyWaste(base(1), flatWeights());

        assertTrue(has(r, "순위 신뢰도"), "시드 1개로는 계절성과 난수를 가릴 수 없다는 사실을 알려야 한다");
        assertTrue(insight(r, "순위 신뢰도").contains("시드"),
                "무엇을 늘려야 하는지까지 알려야 조치로 이어진다");
    }

    @Test
    @DisplayName("계절 가중치가 가정값이라는 경고는 항상 남는다")
    void assumptionWarningAlwaysPresent() {
        ScenarioResponse r = scenario.monthlyWaste(base(2), null);   // 기본 계절 가중치
        assertTrue(r.getInsights().stream()
                        .anyMatch(m -> String.valueOf(m.get("value")).contains("실측 데이터가 아닙니다")),
                "실측이 아니라는 사실은 결과 해석의 전제다");
    }
}
