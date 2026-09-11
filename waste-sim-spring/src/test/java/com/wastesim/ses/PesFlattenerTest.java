package com.wastesim.ses;

import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PesFlattenerTest {

    private static SimulationConfig flatten(Map<String, Object> answers) {
        return PesFlattener.flatten(SesPruner.prune(answers));
    }

    // 필수 spec 축 4개는 코드값으로 답한다(Ruling 3) — 트리 자식 이름("5톤 차량")이
    // 아니라 SimulationConfig 세터가 실제로 받는 값이다.
    private static Map<String, Object> base() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scenarioType", "single-run");
        a.put("dischargeTimeMode", "PAPER_BASELINE");
        a.put("travelTimeMode", "LEGACY_CONSTANT");
        a.put("truckType", "LARGE_5TON");
        a.put("numBuildings", 10);
        a.put("residentsPerBuilding", 20);
        a.put("truckCount", 2);
        a.put("trafficMode", "NONE");
        return a;
    }

    @Test
    void countsBecomeConfigNumbers() {
        SimulationConfig c = flatten(base());
        assertEquals(10, c.getNumBuildings());
        assertEquals(20, c.getResidentsPerBuilding());
        assertEquals(2, c.getNumTrucks());
    }

    @Test
    void unansweredAttributeKeepsConfigDefault() {
        assertEquals(new SimulationConfig().getDays(), flatten(base()).getDays(),
                "답하지 않은 값은 SimulationConfig의 기본값 그대로여야 한다");
    }

    @Test
    void deadTrafficCouplingDisablesTraffic() {
        assertFalse(flatten(base()).isTrafficEnabled());
    }
}
