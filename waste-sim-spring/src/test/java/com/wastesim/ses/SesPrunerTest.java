package com.wastesim.ses;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec 답변은 트리의 한글 자식 이름이 아니라 Task 7이 확정한 코드값으로 들어온다
 * (Ruling 3). {@code SesFieldMapping.FieldBinding.optionCodes}가 트리 자식 이름을
 * v4 코드값으로 옮긴 사전이므로, 여기 답으로 그 코드값을 그대로 쓴다.
 */
class SesPrunerTest {

    private static Map<String, Object> answers() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scenarioType", "single-run");
        a.put("dischargeTimeMode", "PAPER_BASELINE");
        a.put("travelTimeMode", "LEGACY_CONSTANT");
        a.put("truckType", "LARGE_5TON");
        a.put("numBuildings", 10);
        a.put("residentsPerBuilding", 20);
        a.put("truckCount", 1);
        a.put("capacity", 60.0);
        a.put("trafficMode", "APPLY");
        return a;
    }

    @Test
    void specAnswerFoldsTheAxisToOneChild() {
        // chosenSpecs에는 코드값 그대로 담긴다 — SimulationConfig.setTruckType이
        // 받는 값이 코드값이라서다(Ruling 3).
        assertEquals("LARGE_5TON", SesPruner.prune(answers()).chosen("spec:수거차량:차종 축"));
    }

    @Test
    void multiAnswerSetsReplicationCount() {
        assertEquals(10, SesPruner.prune(answers()).count("multi:수거지점 집합"));
    }

    @Test
    void attributeAnswerBindsValue() {
        // value()는 pointId가 아니라 answerField로 찾는다(Ruling 2).
        assertEquals(60.0, SesPruner.prune(answers()).value("capacity"));
    }

    @Test
    void unansweredAttributeIsAbsentNotNull() {
        assertNull(SesPruner.prune(answers()).value("days"),
                "답하지 않은 속성은 PES에 넣지 않는다 — 넣지 않은 것이 곧 기록이다");
    }

    @Test
    void bothCollectionTimeFieldsSurviveIndependently() {
        // Ruling 2의 이유 자체를 검증한다: collectionTime과 collectionTimes는 서로 다른
        // 필드이면서 같은 SES 지점(attr:수거차량:수거시각)을 가리킨다. pointId를 키로
        // 쓰면 뒤에 처리된 필드가 앞의 값을 덮어써 사용자가 답한 단일 시각이 사라진다.
        Map<String, Object> a = new LinkedHashMap<>(answers());
        a.put("collectionTime", "09:00");
        a.put("collectionTimes", java.util.List.of("09:00", "18:00"));

        PrunedStructure pes = SesPruner.prune(a);

        assertEquals("09:00", pes.value("collectionTime"),
                "collectionTimes를 나중에 처리해도 collectionTime 값이 살아 있어야 한다");
        assertEquals(java.util.List.of("09:00", "18:00"), pes.value("collectionTimes"));
        // 두 필드 모두 같은 SES 지점을 가리킨다는 사실은 pointOfField로 남는다.
        assertEquals("attr:수거차량:수거시각", pes.pointOf("collectionTime"));
        assertEquals("attr:수거차량:수거시각", pes.pointOf("collectionTimes"));
    }

    @Test
    void trafficOffKillsBothTrafficCouplings() {
        Map<String, Object> off = new LinkedHashMap<>(answers());
        off.put("trafficMode", "NONE");
        PrunedStructure pes = SesPruner.prune(off);
        assertFalse(pes.isLive("coupling:교통 구역.혼잡계수->수거 경로.이동시간"));
        assertFalse(pes.isLive("coupling:교통 구역.혼잡계수->교통혼잡 판정.판정"));
    }

    @Test
    void trafficOnKeepsThemAlive() {
        assertTrue(SesPruner.prune(answers())
                .isLive("coupling:교통 구역.혼잡계수->수거 경로.이동시간"));
    }

    @Test
    void unansweredSpecAxisFailsLoudly() {
        Map<String, Object> missing = new LinkedHashMap<>(answers());
        missing.remove("truckType");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SesPruner.prune(missing));
        assertTrue(e.getMessage().contains("차종 축"),
                "어느 축이 안 정해졌는지 말해야 한다: " + e.getMessage());
    }

    @Test
    void answerOutsideAxisOptionsFailsLoudly() {
        Map<String, Object> bogus = new LinkedHashMap<>(answers());
        bogus.put("truckType", "HEAVY_10TON");
        assertThrows(IllegalStateException.class, () -> SesPruner.prune(bogus));
    }
}
