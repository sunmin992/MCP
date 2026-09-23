package com.wastesim.template;

import com.wastesim.ledger.Activation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 생성 조건이 서브태스크의 존재를 정한다.
 *
 * <p>세 갈래여야 한다 — 참이면 만들고(ACTIVE), 거짓이면 만들지 않고(INACTIVE),
 * 아직 판정할 수 없으면 보류한다(UNKNOWN). 보류를 거짓으로 뭉개면 나중에 조건이
 * 참이 되어도 그 결정을 영영 묻지 않는다.
 */
class GenerateConditionTest {

    private Map<String, Object> answers(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void ALWAYS는_언제나_활성이다() {
        assertEquals(Activation.ACTIVE, GenerateCondition.ALWAYS.evaluate(answers()));
    }

    @Test
    void 배차간격은_실제운행_두대이상일때만_활성이다() {
        GenerateCondition c = GenerateCondition.EFFECTIVE_TRUCKS_AT_LEAST_2;
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("numBuildings", 4, "truckCount", 3)));
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("numBuildings", 4, "truckCount", 2)));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("numBuildings", 4, "truckCount", 1)));
    }

    @Test
    void 건물보다_차량이_많으면_실제운행은_건물수로_잘린다() {
        GenerateCondition c = GenerateCondition.EFFECTIVE_TRUCKS_AT_LEAST_2;
        assertEquals(Activation.INACTIVE, c.evaluate(answers("numBuildings", 1, "truckCount", 9)),
                "건물이 1동이면 차량이 아무리 많아도 실제 운행은 1대다");
    }

    @Test
    void 차량수를_모르면_보류한다() {
        GenerateCondition c = GenerateCondition.EFFECTIVE_TRUCKS_AT_LEAST_2;
        assertEquals(Activation.UNKNOWN, c.evaluate(answers("numBuildings", 4)));
        assertEquals(Activation.UNKNOWN, c.evaluate(answers()));
    }

    @Test
    void 교통프로파일은_교통반영일때만_활성이다() {
        GenerateCondition c = GenerateCondition.TRAFFIC_APPLIED;
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("trafficMode", "APPLY")));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("trafficMode", "IGNORE")));
        assertEquals(Activation.UNKNOWN,  c.evaluate(answers()));
    }

    @Test
    void 구역배정규칙은_구역근사이고_건물이_다섯동이상일때_활성이다() {
        GenerateCondition c = GenerateCondition.ZONE_PROXY_OVER_4_BUILDINGS;
        assertEquals(Activation.ACTIVE,
                c.evaluate(answers("travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 5)));
        assertEquals(Activation.INACTIVE,
                c.evaluate(answers("travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 4)),
                "4동까지는 지점 id를 구역 id로 보는 폴백이 통한다");
        assertEquals(Activation.INACTIVE,
                c.evaluate(answers("travelTimeMode", "LEGACY_CONSTANT", "numBuildings", 26)));
        assertEquals(Activation.UNKNOWN,
                c.evaluate(answers("travelTimeMode", "ZONE_PROXY_HYBRID")));
    }

    @Test
    void 구역내이동시간은_덩어리배정일때_활성이다() {
        GenerateCondition c = GenerateCondition.CONTIGUOUS_ZONE_RULE;
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("zoneAssignmentRule", "CONTIGUOUS")));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("zoneAssignmentRule", "ROUND_ROBIN")));
        assertEquals(Activation.UNKNOWN,  c.evaluate(answers()));
    }
}
