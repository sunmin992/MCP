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
            new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty()));

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
        Map<String, String> origins = new LinkedHashMap<>();
        v.keySet().forEach(k -> origins.put(k, "USER"));
        return new Pes("jangnyang-ses", "1.0.0", v, origins);
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
        return new Scenario(s.scenarioId(), s.runs(), s.blocks(),
                s.backVerificationBlocks(), builder.tokenFor(s));
    }

    @Test
    void 범위를_벗어난_값이_있으면_토큰이_없다() {
        ExperimentFrame frame = new ExperimentFrame(
                "collectionTimeMinutes", List.of(720, 1500), List.of("meanComplaints"));
        Scenario s = builder.build(basePes(), frame);
        assertFalse(s.valid(), "1500분은 0~1439 범위를 벗어난다");
        assertNull(s.confirmToken(), "검증하지 않은 설정에 토큰을 주면 안 된다");
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
}
