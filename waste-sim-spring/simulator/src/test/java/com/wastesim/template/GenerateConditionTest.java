package com.wastesim.template;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final TemplateCatalog CATALOG = new TemplateCatalog();
    private static final ObjectMapper M = new ObjectMapper();

    private static GenerateCondition of(String templateId) {
        return CATALOG.byId(templateId).orElseThrow().generateWhen();
    }

    private static GenerateCondition parse(String json) throws Exception {
        return GenerateCondition.of(M.readTree(json));
    }

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
        GenerateCondition c = of("jn.dispatchInterval");
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("numBuildings", 4, "truckCount", 3)));
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("numBuildings", 4, "truckCount", 2)));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("numBuildings", 4, "truckCount", 1)));
    }

    @Test
    void 건물보다_차량이_많으면_실제운행은_건물수로_잘린다() {
        GenerateCondition c = of("jn.dispatchInterval");
        assertEquals(Activation.INACTIVE, c.evaluate(answers("numBuildings", 1, "truckCount", 9)),
                "건물이 1동이면 차량이 아무리 많아도 실제 운행은 1대다");
    }

    @Test
    void 차량수를_모르면_보류한다() {
        GenerateCondition c = of("jn.dispatchInterval");
        assertEquals(Activation.UNKNOWN, c.evaluate(answers("numBuildings", 4)));
        assertEquals(Activation.UNKNOWN, c.evaluate(answers()));
    }

    @Test
    void 교통프로파일은_교통반영일때만_활성이다() {
        GenerateCondition c = of("jn.trafficProfile");
        assertEquals(Activation.ACTIVE,   c.evaluate(answers("trafficMode", "APPLY")));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("trafficMode", "IGNORE")));
        assertEquals(Activation.UNKNOWN,  c.evaluate(answers()));
    }

    @Test
    void 구역배정규칙은_구역근사이고_건물이_다섯동이상일때_활성이다() {
        GenerateCondition c = of("jn.zoneAssignmentRule");
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
        GenerateCondition c = of("jn.intraZoneTravel");
        assertEquals(Activation.ACTIVE, c.evaluate(answers(
                "travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 5, "zoneAssignmentRule", "CONTIGUOUS")));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("zoneAssignmentRule", "ROUND_ROBIN")),
                "번갈아 나누면 구역 근사 여부와 상관없이 필요 없다");
        assertEquals(Activation.UNKNOWN, c.evaluate(answers("zoneAssignmentRule", "CONTIGUOUS")),
                "규칙 자체가 보류인 동안은 이것도 보류다");
        assertEquals(Activation.UNKNOWN, c.evaluate(answers()));
    }

    @Test
    void 구역배정규칙이_생성되지_않으면_구역내이동시간도_생성되지_않는다() {
        // 규칙을 묻지 않는 설정에서 규칙 값이 없는 것은 "아직 모름" 이 아니라 "영영 없음" 이다.
        // 보류로 두면 필요한 값을 다 채워도 complete=false 가 되어 대화가 끝나지 않는다.
        GenerateCondition c = of("jn.intraZoneTravel");
        assertEquals(Activation.INACTIVE,
                c.evaluate(answers("travelTimeMode", "LEGACY_CONSTANT", "numBuildings", 4)));
        assertEquals(Activation.INACTIVE,
                c.evaluate(answers("travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 4)),
                "4동까지는 구역 배정 규칙이 걸리지 않는다");
        assertEquals(Activation.UNKNOWN,
                c.evaluate(answers("travelTimeMode", "ZONE_PROXY_HYBRID", "numBuildings", 5)),
                "규칙이 걸리는데 아직 답이 없으면 그때는 정말 모르는 것이다");
    }

    @Test
    void 하나라도_거짓으로_정해지면_나머지를_몰라도_만들지_않는다() {
        // Kleene 3값 논리. 차량이 1대면 건물 수가 몇이든 배차 간격은 결과를 바꾸지 못한다.
        assertEquals(Activation.INACTIVE, of("jn.dispatchInterval").evaluate(answers("truckCount", 1)));
        assertEquals(Activation.INACTIVE, of("jn.zoneAssignmentRule").evaluate(answers("travelTimeMode", "LEGACY_CONSTANT")));
        assertEquals(Activation.INACTIVE, of("jn.intraZoneTravel").evaluate(answers("numBuildings", 3)));
    }

    @Test
    void 여러번_수거는_말했을때만_만들고_한번_수거는_그_반대다() {
        assertEquals(Activation.INACTIVE, of("jn.collectionTimes").evaluate(answers()), "말하지 않았으면 보류가 아니라 필요 없음");
        assertEquals(Activation.ACTIVE, of("jn.collectionTime").evaluate(answers()));
        assertEquals(Activation.ACTIVE, of("jn.collectionTimes").evaluate(answers("collectionTimesMinutes", java.util.List.of(360, 1080))));
        assertEquals(Activation.INACTIVE, of("jn.collectionTime").evaluate(answers("collectionTimesMinutes", java.util.List.of(360, 1080))));
        assertEquals(Activation.ACTIVE, of("jn.collectionTime").evaluate(answers("collectionTimesMinutes", java.util.List.of())));
    }

    @Test
    void any는_하나라도_참이면_참이고_모름이_섞이면_모름이다() throws Exception {
        GenerateCondition c = parse("{\"any\": [{\"eq\": [\"a\", \"X\"]}, {\"lt\": [\"b\", 3]}]}");
        assertEquals(Activation.ACTIVE, c.evaluate(answers("b", 2)));
        assertEquals(Activation.UNKNOWN, c.evaluate(answers("b", 5)));
        assertEquals(Activation.INACTIVE, c.evaluate(answers("a", "Y", "b", 5)));
        assertEquals(java.util.List.of("a", "b"), c.inputs());
    }

    @Test
    void 숫자가_아닌_답은_비교할_수_없어_보류다() throws Exception {
        assertEquals(Activation.UNKNOWN, parse("{\"gte\": [\"n\", 2]}").evaluate(answers("n", "여러 대")));
        assertEquals(Activation.INACTIVE, parse("{\"eq\": [\"n\", 2]}").evaluate(answers("n", "여러 대")));
        assertEquals(Activation.ACTIVE, parse("{\"eq\": [\"n\", 2]}").evaluate(answers("n", "2")));
    }

    @Test
    void 모르는_식은_불러올_때_막는다() {
        for (String bad : new String[]{"\"SOMETIMES\"", "{\"has\": \"a\"}", "{\"gte\": [\"a\", \"x\"]}",
                "{\"all\": []}", "{\"eq\": [\"a\"]}", "{\"eq\": [\"a\", 1], \"ne\": [\"b\", 1]}"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
        }
    }

    @Test
    void 식은_적힌_그대로_다시_나간다() throws Exception {
        // get_templates 가 LLM 에 보여 주는 것은 이 식이다.
        String json = M.writeValueAsString(of("jn.trafficProfile"));
        assertEquals("{\"eq\":[\"trafficMode\",\"APPLY\"]}", json);
        assertEquals("\"ALWAYS\"", M.writeValueAsString(GenerateCondition.ALWAYS));
    }
}
