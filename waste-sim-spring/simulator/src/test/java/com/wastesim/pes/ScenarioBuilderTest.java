package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.service.TrafficDataService;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실험 변수를 펼쳐 실행 설정 N벌을 만들고, 검증을 통과한 것에만 토큰을 주는가.
 *
 * <p>토큰이 설정에 고정되지 않으면 사용자가 확인한 설정과 실제 실행 설정이 달라질 수 있다.
 */
class ScenarioBuilderTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final ScenarioBuilder builder = new ScenarioBuilder(
            new PesFlattener(catalog),
            new PesBackVerifier(catalog),
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()),
            new com.wastesim.template.SubtaskPlanner(catalog, new com.wastesim.template.AnswerNormalizer()));

    private Pes basePes() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("truckType", "SMALL_1TON");
        v.put("truckCount", 3);
        v.put("numBuildings", 4);
        v.put("residentsPerBuilding", 25);
        v.put("days", 30);
        v.put("seeds", 30);
        v.put("trafficMode", "IGNORE");
        v.put("travelTimeMode", "LEGACY_CONSTANT");
        v.put("dispatchIntervalMinutes", 15);
        v.put("occupationMix", List.of("BlueCollar", "Student", "Housewife"));
        v.put("routeAvailableCapacityKg", 150);
        v.put("binCapacityKg", 30.0);
        v.put("complaintThreshold", 0.8);
        v.put("dischargeTimeMode", "PAPER_BASELINE");
        Map<String, String> origins = new LinkedHashMap<>();
        v.keySet().forEach(k -> origins.put(k, "USER"));
        return new Pes("jangnyang-ses", "1.1.0", v, origins);
    }

    @Test
    void 수거시각_다섯개를_펼쳐_설정_다섯벌을_만든다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(360, 540, 720, 900, 1080),
                List.of("meanComplaints", "peakFillKg"));
        Scenario s = builder.build(basePes(), frame);
        assertTrue(s.valid(), "검증 실패: " + s.blocks());
        assertEquals(5, s.runs().size());
        assertEquals(List.of(360, 540, 720, 900, 1080),
                s.runs().stream().map(SimulationConfig::getCollectionTimeMinutes).toList());
    }

    @Test
    void 공통설정은_모든_실행에서_같다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(360, 720), List.of("meanComplaints"));
        Scenario s = builder.build(basePes(), frame);
        for (SimulationConfig cfg : s.runs()) {
            assertEquals("SMALL_1TON", cfg.getTruckType());
            assertEquals(3, cfg.getNumTrucks());
            assertEquals(15, cfg.getDispatchIntervalMinutes());
        }
    }

    @Test
    void 검증을_통과해도_토큰은_아직_없다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        Scenario s = builder.build(basePes(), frame);
        assertTrue(s.valid(), "검증은 통과해야 한다");
        assertNull(s.confirmToken(),
                "검증 통과는 '돌릴 수 있다' 이지 '사용자가 확인했다' 가 아니다");
    }

    @Test
    void 확인하면_토큰이_나오고_그_설정에_맞는다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        Scenario s = builder.build(basePes(), frame);
        Scenario confirmed = confirm(s);
        assertTrue(builder.tokenMatches(confirmed, confirmed.confirmToken()));
    }

    /** 확인 단계가 하는 일 — 토큰을 실어 같은 시나리오를 다시 만든다. */
    private Scenario confirm(Scenario s) {
        return s.withToken(builder.tokenFor(s));
    }

    @Test
    void 범위를_벗어난_값이_있으면_토큰이_없다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720, 1500), List.of("meanComplaints"));
        Scenario s = builder.build(basePes(), frame);
        assertFalse(s.valid(), "1500분은 0~1439 범위를 벗어난다");
        assertNull(s.confirmToken(), "검증하지 않은 설정에 토큰을 주면 안 된다");
        assertTrue(s.blocks().stream().anyMatch(b -> b.contains("물어야 할 결정 collectionTimeMinutes")
                        && b.contains("1439 이하여야 합니다. 받은 값: 1500")),
                "형식에서 떨어진 값은 정규화 사유와 함께 물어야 할 결정으로 나와야 한다: " + s.blocks());
    }

    @Test
    void 설정이_다르면_토큰도_다르다() {
        ExperimentFrame f1 = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        ExperimentFrame f2 = new ExperimentFrame(
                "collectionTimeMinutes", List.of(900), List.of("meanComplaints"));
        assertNotEquals(builder.tokenFor(builder.build(basePes(), f1)),
                        builder.tokenFor(builder.build(basePes(), f2)));
    }

    @Test
    void 같은_설정이면_토큰이_재현된다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        assertEquals(builder.tokenFor(builder.build(basePes(), frame)),
                     builder.tokenFor(builder.build(basePes(), frame)));
    }

    @Test
    void 남의_토큰은_맞지_않는다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));
        Scenario s = confirm(builder.build(basePes(), frame));
        assertFalse(builder.tokenMatches(s, "cft-남의토큰"));
        assertFalse(builder.tokenMatches(s, null));
    }

    private Pes withValue(Pes base, String key, Object value, String origin) {
        Map<String, Object> v = new LinkedHashMap<>(base.values());
        Map<String, String> o = new LinkedHashMap<>(base.origins());
        v.put(key, value);
        o.put(key, origin);
        return new Pes(base.sesId(), base.sesVersion(), v, o);
    }

    private static final ExperimentFrame ONE_RUN = new ExperimentFrame(
            "collectionTimeMinutes", List.of(720), List.of("meanComplaints"));

    @Test
    void 가지친_쪽_사용자_값은_막는다() {
        Pes pes = withValue(basePes(), "trafficProfileId", "jangryang-weekday", "USER");
        Scenario s = builder.build(pes, ONE_RUN);
        assertEquals(1, s.branchBlocks().size(), "막힌 값: " + s.branchBlocks());
        assertTrue(s.branchBlocks().get(0).contains("trafficProfileId"));
        assertTrue(s.branchBlocks().get(0).contains("가지친 쪽의 값입니다"));
        assertFalse(s.valid());
    }

    @Test
    void 기본값으로_실린_가지친_값은_빼고_통과한다() {
        Pes pes = withValue(basePes(), "trafficProfileId", "jangryang-weekday", "MODEL_DEFAULT");
        Scenario s = builder.build(pes, ONE_RUN);
        assertTrue(s.branchBlocks().isEmpty(), "막힌 값: " + s.branchBlocks());
        assertTrue(s.valid(), "검증 실패: " + s.blocks() + s.backVerificationBlocks());
        assertNotEquals("jangryang-weekday", s.runs().get(0).getTrafficProfileId(),
                "가지친 기본값이 실행 설정에 실리면 안 된다");
    }

    @Test
    void 일부_비교_값에서만_생기는_사용자_값은_그쪽에만_싣는다() {
        // 1대 대 3대 비교의 배차 간격(onlyFor 3대). values 는 모든 실행에 공통이라, 1대 쪽에서 막으면
        // 넣어도(1대가 막힘) 빼도(3대가 물어야 할 결정) 시나리오를 만들 수 없다 — 2026-11-03 V3 실험 J3.
        Pes pes = withValue(withValue(basePes(), "dispatchIntervalMinutes", 10, "USER"), "collectionTimeMinutes", 720, "USER");
        Scenario s = builder.build(withoutTruckCount(pes),
                new ExperimentFrame("truckCount", List.of(1, 3), List.of("meanComplaints")));
        assertTrue(s.branchBlocks().isEmpty(), "막힌 값: " + s.branchBlocks());
        assertTrue(s.valid(), "검증 실패: " + s.blocks() + s.backVerificationBlocks());
        assertEquals(10, s.runs().get(1).getDispatchIntervalMinutes());
        assertNotEquals(10, s.runs().get(0).getDispatchIntervalMinutes(), "1대 쪽에 배차 간격이 실리면 안 된다");
        assertEquals(List.of("truckCount=1"), s.droppedDefaults().get("dispatchIntervalMinutes"));
    }

    @Test
    void 어느_비교_값에서도_생기지_않는_사용자_값은_막는다() {
        Scenario s = builder.build(withoutTruckCount(withValue(basePes(), "dispatchIntervalMinutes", 10, "USER")),
                new ExperimentFrame("truckCount", List.of(1), List.of("meanComplaints")));
        assertEquals(1, s.branchBlocks().size(), "막힌 값: " + s.branchBlocks());
        assertTrue(s.branchBlocks().get(0).startsWith("truckCount=1: dispatchIntervalMinutes"),
                s.branchBlocks().get(0));
    }

    private Pes withoutTruckCount(Pes pes) {
        Map<String, Object> v = new LinkedHashMap<>(pes.values());
        Map<String, String> o = new LinkedHashMap<>(pes.origins());
        v.remove("truckCount");
        o.remove("truckCount");
        return new Pes(pes.sesId(), pes.sesVersion(), v, o);
    }

    @Test
    void 보류인_결정의_값은_막지_않는다() {
        Map<String, Object> v = new LinkedHashMap<>(basePes().values());
        Map<String, String> o = new LinkedHashMap<>(basePes().origins());
        v.remove("travelTimeMode");
        o.remove("travelTimeMode");
        v.put("numBuildings", 6);
        v.put("zoneAssignmentRule", "CONTIGUOUS");
        o.put("zoneAssignmentRule", "USER");
        Scenario s = builder.build(new Pes("jangnyang-ses", "1.1.0", v, o), ONE_RUN);
        assertTrue(s.branchBlocks().isEmpty(), "막힌 값: " + s.branchBlocks());
    }

    @Test
    void 실험_변수가_가지친_쪽이면_기본값_출처여도_막는다() {
        // 변수를 빼면 같은 설정 N벌이 valid 로 나온다 — 비교가 비교가 아니게 된다.
        Pes pes = withValue(basePes(), "truckCount", 1, "USER");
        pes = withValue(pes, "dispatchIntervalMinutes", 15, "MODEL_DEFAULT");
        Scenario s = builder.build(pes, new ExperimentFrame(
                "dispatchIntervalMinutes", List.of(10, 20), List.of("meanComplaints")));
        assertEquals(2, s.branchBlocks().size(), "막힌 값: " + s.branchBlocks());
        assertTrue(s.branchBlocks().get(0).startsWith("dispatchIntervalMinutes=10: dispatchIntervalMinutes"),
                s.branchBlocks().get(0));
        assertFalse(s.valid());
        assertTrue(s.droppedDefaults().isEmpty(), "실험 변수는 빼지 않는다: " + s.droppedDefaults());
    }
}
