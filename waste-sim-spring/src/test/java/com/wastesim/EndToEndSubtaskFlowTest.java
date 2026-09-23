package com.wastesim;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.pes.ExperimentFrame;
import com.wastesim.pes.Pes;
import com.wastesim.pes.PesBackVerifier;
import com.wastesim.pes.PesFlattener;
import com.wastesim.pes.Scenario;
import com.wastesim.pes.ScenarioBuilder;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.template.SubtaskInstance;
import com.wastesim.template.SubtaskPlan;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.SubtaskStatus;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요청에서 실행까지 끊기지 않는가.
 *
 * <p>사용자가 말한 값이 서브태스크 → PES → 실행 설정 → 엔진까지 그대로 가는지를 본다.
 * 중간에 하나라도 끊기면 "사용자가 준 값으로 돌렸다"가 거짓이 된다.
 */
class EndToEndSubtaskFlowTest {

    /** 재현을 위해 시드를 고정한다 — 값이 흔들리면 무엇이 깨졌는지 가릴 수 없다. */
    private static final int SEED = 42;

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog);
    private final PesFlattener flattener = new PesFlattener(catalog);
    private final PesBackVerifier backVerifier = new PesBackVerifier(catalog);
    private final ScenarioBuilder builder = new ScenarioBuilder(
            flattener, backVerifier,
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));

    /** 사용자가 "1톤 3대, 주택 4동 25세대, 30일" 이라고 답한 상태. */
    private Map<String, Object> answersFromUser() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("truckType", "SMALL_1TON");
        a.put("truckCount", 3);
        a.put("numBuildings", 4);
        a.put("residentsPerBuilding", 25);
        a.put("days", 30);
        a.put("seeds", 5);                 // 시험 시간을 줄인다
        a.put("trafficMode", "IGNORE");
        a.put("travelTimeMode", "LEGACY_CONSTANT");
        a.put("dispatchIntervalMinutes", 15);
        // 구역 배정은 travelTimeMode 가 근사가 아니라 생성되지 않지만, 구역 내 이동시간의
        // 생성 조건이 이 값을 직접 읽으므로 채워 둬야 보류(DEFERRED)가 아니라
        // 미생성(NOT_GENERATED)으로 판정된다.
        a.put("zoneAssignmentRule", "ROUND_ROBIN");
        // 이 블록에 배정된 적재 몫. 지정하지 않으면 가동률이 죽는다.
        a.put("routeAvailableCapacityKg", 150.0);
        return a;
    }

    private Pes pesOf(Map<String, Object> answers) {
        Map<String, String> origins = new LinkedHashMap<>();
        answers.keySet().forEach(k -> origins.put(k, "USER"));
        return new Pes(catalog.sesId(), catalog.sesVersion(), answers, origins);
    }

    @Test
    void 답변이_다_차면_실험변수_하나만_열려_있다() {
        SubtaskPlan plan = planner.plan(answersFromUser());

        // 실험 변수는 모델 구조상 하나로 정해지지 않은 채 남는다 — 그 자리를 닫는 것이
        // ExperimentFrame 의 일이다. 그러므로 여기서 complete() 는 아직 거짓이어야 하고,
        // 남은 것이 정확히 그 하나여야 한다. 다른 것이 섞여 있으면 묻지 않은 결정이
        // 기본값으로 실행에 실린다는 뜻이다.
        assertEquals(List.of("jn.collectionTime"),
                plan.toAsk().stream().map(SubtaskInstance::templateId).toList(),
                "실험 변수 말고 다른 것이 남아 있다");
        assertEquals(0, plan.counts().get(SubtaskStatus.DEFERRED),
                "보류가 남으면 조건이 나중에 참이 되어도 그 결정을 영영 묻지 않는다");
    }

    @Test
    void 실험변수까지_채우면_계획이_완결된다() {
        Map<String, Object> a = answersFromUser();
        a.put("collectionTimeMinutes", 720);
        SubtaskPlan plan = planner.plan(a);
        assertTrue(plan.complete(),
                "아직 물을 것이 남아 있다: " + plan.toAsk().stream()
                        .map(SubtaskInstance::templateId).toList());
    }

    @Test
    void 차량이_한대면_배차간격이_사라진다() {
        Map<String, Object> a = answersFromUser();
        a.put("collectionTimeMinutes", 720);
        a.put("truckCount", 1);
        a.remove("dispatchIntervalMinutes");
        SubtaskPlan plan = planner.plan(a);
        assertTrue(plan.complete(), "배차 간격이 생성되지 않았으므로 물을 것이 없어야 한다");
        assertEquals(SubtaskStatus.NOT_GENERATED,
                plan.instances().stream()
                        .filter(i -> i.templateId().equals("jn.dispatchInterval"))
                        .findFirst().orElseThrow().status(),
                "1대면 차량 간 시간차가 없어 배차 간격이 결과를 바꾸지 못한다");
    }

    @Test
    void 수거시각_다섯조건이_검증을_통과하고_엔진이_돈다() {
        Pes pes = pesOf(answersFromUser());
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(360, 540, 720, 900, 1080),
                List.of("meanComplaints", "peakFillKg"));

        Scenario scenario = builder.build(pes, frame);
        assertTrue(scenario.valid(), "검증 실패: " + scenario.blocks()
                + " / 역검증: " + scenario.backVerificationBlocks());
        assertNotNull(scenario.confirmToken());
        assertTrue(builder.tokenMatches(scenario, scenario.confirmToken()));

        assertEquals(List.of(360, 540, 720, 900, 1080),
                scenario.runs().stream().map(SimulationConfig::getCollectionTimeMinutes).toList(),
                "평탄화가 다섯 값을 싣지 못했다면 아래 단언의 실패 원인이 엔진이 아니다");

        SimulationEngine engine = new SimulationEngine(new TrafficDataService());
        List<Integer> complaints = new ArrayList<>();
        for (SimulationConfig cfg : scenario.runs()) {
            SimulationResult r = engine.run(cfg, SEED);
            assertNotNull(r, "수거 시각 " + cfg.getCollectionTimeMinutes() + " 에서 결과가 없다");
            complaints.add(r.getTotalComplaints());
        }
        assertEquals(5, complaints.size());
        assertFalse(complaints.stream().allMatch(c -> c.equals(complaints.get(0))),
                "다섯 조건의 민원이 전부 같다면 수거 시각이 결과에 반영되지 않은 것이다: " + complaints);
    }

    @Test
    void 사용자가_말한_값이_실행_설정까지_그대로_간다() {
        Pes pes = pesOf(answersFromUser());
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        SimulationConfig cfg = builder.build(pes, frame).runs().get(0);

        assertEquals("SMALL_1TON", cfg.getTruckType());
        assertEquals(3, cfg.getNumTrucks());
        assertEquals(4, cfg.getNumBuildings());
        assertEquals(25, cfg.getResidentsPerBuilding());
        assertEquals(30, cfg.getDays());
        assertEquals(15, cfg.getDispatchIntervalMinutes());
        assertEquals(150.0, cfg.getRouteAvailableCapacityKg());
        assertFalse(cfg.isTrafficEnabled(), "IGNORE 라고 답했는데 교통이 켜져 있다");
        assertEquals(List.of(), backVerifier.verify(pes, cfg));
    }

    @Test
    void 설정을_건드리면_토큰이_맞지_않는다() {
        Pes pes = pesOf(answersFromUser());
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        Scenario scenario = builder.build(pes, frame);
        String token = scenario.confirmToken();
        assertTrue(builder.tokenMatches(scenario, token));

        // 사용자가 확인한 뒤 누군가 설정을 바꾼 상황.
        scenario.runs().get(0).setNumTrucks(9);
        assertFalse(builder.tokenMatches(scenario, token),
                "설정이 바뀌었는데 토큰이 그대로 맞으면 확인 절차가 아무것도 보장하지 못한다");
    }
}
