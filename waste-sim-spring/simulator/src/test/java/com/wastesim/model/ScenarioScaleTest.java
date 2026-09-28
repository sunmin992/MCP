package com.wastesim.model;

import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.ConfigArgs;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationResult;
import com.wastesim.traffic.TravelTimeMatrix;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시나리오 규모.
 *
 * <p>논문 기준선(4동 25명)에서는 용량 축이 아예 작동하지 않는다 — 하루 90kg 대 5톤이라
 * 트럭 가동률이 어떤 설정에서도 1.8%이고 미수거는 항상 0이다. 이 클래스는 두 가지를
 * 지킨다: <b>기준선이 움직이지 않았는가</b>, 그리고 <b>새 규모에서 용량 축이 실제로 살아
 * 있는가</b>. 두 번째가 깨지면 규모를 바꾼 목적이 사라진다.
 */
class ScenarioScaleTest {

    private static SimulationEngine engine() {
        return new SimulationEngine(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfigValidator validator() {
        return new SimulationConfigValidator(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfig scaled(ScenarioScale s) {
        SimulationConfig c = s.newConfig();
        c.setDays(28);
        return c;
    }

    // ── 기준선은 움직이지 않는다 ───────────────────────────────────────────

    /** 필드 기본값이 논문 기준선과 같아야 한다 — 논문 재현이 여기 걸려 있다. */
    @Test
    void fieldDefaultsStillMatchThePaperBaseline() {
        SimulationConfig plain = new SimulationConfig();
        assertNull(plain.getScenarioScale(), "규모를 지정하지 않은 것이 기본이어야 한다");
        assertEquals(ScenarioScale.PAPER_BASELINE, plain.resolveScenarioScale());

        ScenarioScale p = ScenarioScale.PAPER_BASELINE;
        assertEquals(p.numBuildings, plain.getNumBuildings());
        assertEquals(p.residentsPerBuilding, plain.getResidentsPerBuilding());
        assertEquals(p.capacityKg, plain.getCapacity());
        assertEquals(p.truckType, plain.getTruckType());
        assertEquals(p.numTrucks, plain.getNumTrucks());
    }

    /** 기준선 규모를 명시해도 결과가 기본 실행과 같아야 한다. */
    @Test
    void applyingThePaperBaselineChangesNothing() {
        SimulationConfig plain = new SimulationConfig();
        plain.setDays(28);
        assertEquals(engine().run(plain, 42).getTotalComplaints(),
                     engine().run(scaled(ScenarioScale.PAPER_BASELINE), 42).getTotalComplaints());
    }

    // ── 새 규모에서 용량 축이 살아 있다 ────────────────────────────────────

    /**
     * <b>이 테스트가 규모를 바꾼 이유다.</b> 기준선에서 트럭 가동률은 1.8%로 고정이고
     * 미수거는 0이다. 새 규모에서는 가동률이 90%를 넘어야 한다 — 그래야 용량·가동률을
     * 논할 수 있다.
     */
    @Test
    void capacityAxisIsDeadInTheBaselineAndAliveInTheNewScale() {
        var base = engine().run(scaled(ScenarioScale.PAPER_BASELINE), 42);
        var wide = engine().run(scaled(ScenarioScale.JANGRYANG_CAPACITY), 42);

        assertTrue(base.getTruckUtilizationPercent() < 5.0,
                "기준선 가동률이 5%를 넘으면 이 서술을 다시 세워야 한다: "
                        + base.getTruckUtilizationPercent());
        assertTrue(wide.getTruckUtilizationPercent() > 90.0,
                "새 규모에서 가동률이 90%를 넘어야 용량 축을 논할 수 있다: "
                        + wide.getTruckUtilizationPercent());
    }

    /**
     * 새 규모는 <b>가동률 92.5%에 미수거 0, 민원 0</b>에서 출발한다. 여기가 출발점이라야
     * 어느 축을 흔들었을 때 무엇이 나빠지는지 읽을 수 있다 — 처음부터 넘치고 있으면
     * 모든 변화가 이미 터진 값 위에 얹힌다.
     */
    @Test
    void newScaleStartsAtTheEdgeWithNothingLeftBehind() {
        var r = engine().run(scaled(ScenarioScale.JANGRYANG_CAPACITY), 42);
        assertEquals(0.0, r.getUncollectedDemandKg(), 1e-9, "출발점에서 미수거가 있으면 안 된다");
        assertEquals(0, r.getTotalComplaints(),
                "지점 용량 60kg이 동당 40명의 배출을 담아야 한다 — 넘침 민원이 나오면 "
                        + "결과가 넘침 하나에 지배된다");
    }

    /**
     * 트럭 대수가 실제 축이 된다 — 1대 92.5%가 2대에서 절반 근처로 내려가야 한다.
     * 기준선(1.8% → 0.9%)에서는 이 차이를 읽을 수 없었다.
     */
    @Test
    void truckCountBecomesAMeaningfulAxis() {
        SimulationConfig one = scaled(ScenarioScale.JANGRYANG_CAPACITY);
        SimulationConfig two = scaled(ScenarioScale.JANGRYANG_CAPACITY);
        two.setNumTrucks(2);

        double u1 = engine().run(one, 42).getTruckUtilizationPercent();
        double u2 = engine().run(two, 42).getTruckUtilizationPercent();
        assertTrue(u1 - u2 > 30.0, "대수를 늘렸을 때 가동률이 뚜렷하게 내려가야 한다: "
                + u1 + " -> " + u2);
    }

    /**
     * 미수거는 기울기가 아니라 <b>절벽</b>이다. 동당 40명에서 0이고, 45명이면 터진다.
     * 민감도를 볼 구간이 좁다는 것을 고정해 둔다 — 40·80·120명으로 훑으면 0과 폭발만 본다.
     */
    @Test
    void uncollectedDemandIsACliffNotASlope() {
        SimulationConfig edge = scaled(ScenarioScale.JANGRYANG_CAPACITY);
        SimulationConfig over = scaled(ScenarioScale.JANGRYANG_CAPACITY);
        over.setResidentsPerBuilding(45);

        assertEquals(0.0, engine().run(edge, 42).getUncollectedDemandKg(), 1e-9);
        assertTrue(engine().run(over, 42).getUncollectedDemandKg() > 1000.0,
                "동당 45명이면 1톤 용량을 넘어 미수거가 뚜렷하게 나와야 한다");
    }

    // ── 규모 계산 ──────────────────────────────────────────────────────────

    @Test
    void dailyGenerationAndPressureMatchTheNumbersInTheDocs() {
        assertEquals(90.0, ScenarioScale.PAPER_BASELINE.dailyGenerationKg(0.9), 1e-9);
        assertEquals(936.0, ScenarioScale.JANGRYANG_CAPACITY.dailyGenerationKg(0.9), 1e-9);

        assertEquals(0.018, ScenarioScale.PAPER_BASELINE.capacityPressure(0.9), 1e-6);
        assertEquals(0.936, ScenarioScale.JANGRYANG_CAPACITY.capacityPressure(0.9), 1e-6);
    }

    /**
     * 건물 수 상한은 26이다 — 노드 id가 {@code Node_A~Z}뿐이다. 규모가 그 상한을 넘으면
     * 노드 id를 만들 수 없다.
     */
    @Test
    void everyScaleFitsWithinTheNodeIdAlphabet() {
        for (ScenarioScale s : ScenarioScale.values()) {
            assertTrue(s.numBuildings >= 1 && s.numBuildings <= 26,
                    s + "의 건물 수 " + s.numBuildings + "은 Node_A~Z 범위를 벗어난다");
        }
    }

    // ── 규모는 출발점이고 최종 결정이 아니다 ───────────────────────────────

    /** {@code applyTo}는 다섯 필드만 건드린다 — 수거 시각·직업 구성은 규모와 무관하다. */
    @Test
    void applyingAScaleLeavesUnrelatedAxesAlone() {
        SimulationConfig c = new SimulationConfig();
        c.setCollectionTimeLabel("13:00");
        c.setOccupationMix(java.util.List.of("Student"));
        c.setDays(14);

        ScenarioScale.JANGRYANG_CAPACITY.applyTo(c);
        assertEquals("13:00", c.getCollectionTimeLabel());
        assertEquals(java.util.List.of("Student"), c.getOccupationMix());
        assertEquals(14, c.getDays());
        assertEquals(26, c.getNumBuildings());
    }

    /** 규모를 고른 뒤 한 축만 조정하는 요청이 통해야 한다 — 개별 값이 규모를 덮는다. */
    @Test
    void explicitValuesWinOverTheScale() throws Exception {
        var json = new ObjectMapper().readTree(
                "{\"scenarioScale\":\"JANGRYANG_CAPACITY\",\"residentsPerBuilding\":60}");
        SimulationConfig c = ConfigArgs.fromJson(json);

        assertEquals(26, c.getNumBuildings(), "규모가 건물 수를 채워야 한다");
        assertEquals("SMALL_1TON", c.getTruckType(), "규모가 차종을 채워야 한다");
        assertEquals(60, c.getResidentsPerBuilding(),
                "명시적으로 준 값이 규모를 덮어야 한다. 순서가 뒤집히면 40으로 조용히 무시된다");
    }

    @Test
    void scaleNameIsCaseAndHyphenTolerant() {
        assertEquals(ScenarioScale.JANGRYANG_CAPACITY,
                ScenarioScale.fromName("jangryang-capacity"));
        assertEquals(ScenarioScale.JANGRYANG_CAPACITY,
                ScenarioScale.fromName("장량동 용량 규모"));
        assertEquals(ScenarioScale.PAPER_BASELINE, ScenarioScale.fromName(null));
        assertEquals(ScenarioScale.PAPER_BASELINE, ScenarioScale.fromName(""));
    }

    // ── V-S1 ───────────────────────────────────────────────────────────────

    /**
     * 알 수 없는 이름을 조용히 기준선으로 떨어뜨리면 오타 하나가 규모를 1/6로 바꾸고
     * 아무 표시도 남지 않는다.
     */
    @Test
    void rejectsUnknownScaleName() {
        SimulationConfig c = new SimulationConfig();
        c.setScenarioScale("JANGRYANG");
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready(), "오타를 조용히 기준선으로 떨어뜨리면 안 된다");
        assertTrue(r.errors().stream().anyMatch(e -> "scenarioScale".equals(e.field())),
                r.errors().toString());
    }

    @Test
    void bothKnownScalesValidate() {
        for (ScenarioScale s : ScenarioScale.values()) {
            SimulationConfig c = s.newConfig();
            c.setScenarioScale(s.name());
            assertTrue(validator().validate(c).ready(),
                    s + "가 검증을 통과해야 한다: " + validator().validate(c).errors());
        }
    }
}
