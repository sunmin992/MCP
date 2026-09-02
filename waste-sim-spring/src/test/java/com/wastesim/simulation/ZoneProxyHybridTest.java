package com.wastesim.simulation;

import com.wastesim.model.CoordinateQuality;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TravelTimeMode;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.site.TestSites;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationResult;
import com.wastesim.traffic.TravelTimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구역 근사 이동시간 모드.
 *
 * <p>수거 지점 좌표가 0곳이라 {@code OSRM_HYBRID}는 쓸 수 없다. 그렇다고 실측 도로 시간을
 * 아예 못 쓰는 것은 아니다 — 교통 구역 4곳 사이는 실제로 재 뒀다. 각 지점이 <b>어느 구역에
 * 속하는지</b>만 알면, 구역이 바뀌는 이동에는 그 실측값을 쓸 수 있다.
 *
 * <p>대신 <b>같은 구역 안에서 어느 순서로 도는지는 결과에 반영되지 않는다.</b> 구역 내 이동이
 * 전부 같은 평균값이 되기 때문이다. 그래서 이 모드의 결과에는 좌표 품질 표시가 붙는다 —
 * 근사값이 실측처럼 인용되는 것을 막는 것이 이 표시의 유일한 목적이다.
 */
class ZoneProxyHybridTest {

    private static SimulationEngine engine() {
        return new SimulationEngine(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfigValidator validator() {
        return new SimulationConfigValidator(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfig zoneProxy() {
        SimulationConfig c = new SimulationConfig();
        c.setDays(28);
        c.setNumBuildings(4);
        c.setTravelTimeMode("ZONE_PROXY_HYBRID");
        return c;
    }

    // ── 모드 해석 ──────────────────────────────────────────────────────────

    @Test
    void modeNameIsCaseAndHyphenTolerant() {
        assertEquals(TravelTimeMode.ZONE_PROXY_HYBRID, TravelTimeMode.fromName("ZONE_PROXY_HYBRID"));
        assertEquals(TravelTimeMode.ZONE_PROXY_HYBRID, TravelTimeMode.fromName("zone-proxy-hybrid"));
    }

    @Test
    void defaultModeIsUnchanged() {
        assertEquals(TravelTimeMode.LEGACY_CONSTANT, new SimulationConfig().resolveTravelTimeMode(),
                "기본값이 바뀌면 기존 사용자의 결과가 조용히 달라진다");
        assertEquals(0, new SimulationConfig().getIntraZoneTravelMinutes());
    }

    // ── 좌표 품질은 모드와 다른 축이다 ─────────────────────────────────────

    /**
     * 세 모드가 각각 다른 좌표 품질을 낸다. 두 축을 한 enum에 합치면 이 대응이 사라진다.
     */
    @Test
    void eachModeReportsItsOwnCoordinateQuality() {
        assertEquals(CoordinateQuality.NOT_USED,
                TravelTimeMode.LEGACY_CONSTANT.coordinateQuality());
        assertEquals(CoordinateQuality.MEASURED_SITE,
                TravelTimeMode.OSRM_HYBRID.coordinateQuality());
        assertEquals(CoordinateQuality.TRAFFIC_ZONE_PROXY,
                TravelTimeMode.ZONE_PROXY_HYBRID.coordinateQuality());
    }

    /** 좌표를 쓰지 않는 계산에는 경고할 것이 없다 — 경고를 남발하면 읽히지 않는다. */
    @Test
    void onlyApproximationsCarryAWarning() {
        assertFalse(CoordinateQuality.NOT_USED.hasWarning());
        assertFalse(CoordinateQuality.MEASURED_SITE.hasWarning());
        assertTrue(CoordinateQuality.TRAFFIC_ZONE_PROXY.hasWarning());
        assertTrue(CoordinateQuality.ADDRESS_GEOCODED.hasWarning());
        assertTrue(CoordinateQuality.SYNTHETIC.hasWarning());
    }

    // ── 결과에 표시가 실려 나간다 ──────────────────────────────────────────

    /**
     * <b>이 테스트가 이 기능의 요점이다.</b> 근사로 계산한 결과를 받았을 때, 그것이 근사임을
     * 결과 자체가 말해야 한다. 표시가 빠지면 38분이라는 숫자만 남고 그 숫자의 출처가 사라진다.
     */
    @Test
    void resultCarriesTheApproximationWarning() {
        SimulationResult r = engine().run(zoneProxy(), 1);
        assertEquals(CoordinateQuality.TRAFFIC_ZONE_PROXY, r.getCoordinateQuality());
        assertEquals("교통 구역 근사", r.getCoordinateQualityLabel());
        assertNotNull(r.getDataQualityWarning(), "근사 결과에 경고가 없으면 안 된다");
        assertTrue(r.getDataQualityWarning().contains("실제 수거 지점 좌표를 쓰지 않았습니다"),
                r.getDataQualityWarning());
    }

    @Test
    void constantModeResultCarriesNoWarning() {
        SimulationResult r = engine().run(new SimulationConfig(), 1);
        assertEquals(CoordinateQuality.NOT_USED, r.getCoordinateQuality());
        assertNull(r.getDataQualityWarning(), "좌표를 쓰지 않은 계산에 좌표 경고를 붙이면 안 된다");
    }

    // ── 실제로 다른 값을 낸다 ──────────────────────────────────────────────

    /**
     * 상수 모드와 결과가 같으면 실측 행렬이 계산에 들어가지 않은 것이다.
     */
    @Test
    void zoneProxyDiffersFromTheConstantModel() {
        SimulationConfig constant = new SimulationConfig();
        constant.setDays(28);
        constant.setNumBuildings(4);

        assertNotEquals(engine().run(constant, 1).getAvgCompletionMinutes(),
                        engine().run(zoneProxy(), 1).getAvgCompletionMinutes(),
                "구역 실측 행렬을 썼는데 순회 시간이 상수 모드와 같으면 행렬이 쓰이지 않은 것이다");
    }

    /** 정차시간은 지점 수만큼 누적된다 — 순회 시간을 좌우하는 가장 큰 미측정 파라미터다. */
    @Test
    void serviceMinutesAccumulatePerSite() {
        SimulationConfig zero = zoneProxy();
        SimulationConfig five = zoneProxy();
        five.setServiceMinutesPerSite(5);

        double d0 = engine().run(zero, 1).getAvgCompletionMinutes();
        double d5 = engine().run(five, 1).getAvgCompletionMinutes();
        assertTrue(d5 > d0 + 10,
                "지점 4곳에 5분씩이면 순회 시간이 뚜렷하게 늘어야 한다: " + d0 + " -> " + d5);
    }

    // ── 구역 내 이동 ───────────────────────────────────────────────────────

    /**
     * 기본 4개 건물은 서로 <b>다른</b> 구역으로 해석되므로 구역 내 이동이 한 번도 일어나지
     * 않는다. 따라서 {@code intraZoneTravelMinutes}를 바꿔도 결과가 변하지 않아야 한다 —
     * 변한다면 구역이 같은지 판정하는 곳이 잘못된 것이다.
     */
    @Test
    void intraZoneMinutesAreInertWhenEverySiteIsInADifferentZone() {
        SimulationConfig zero = zoneProxy();
        SimulationConfig ten = zoneProxy();
        ten.setIntraZoneTravelMinutes(10);

        assertEquals(engine().run(zero, 1).getAvgCompletionMinutes(),
                     engine().run(ten, 1).getAvgCompletionMinutes(),
                "지점 4곳이 모두 다른 구역인데 구역 내 이동시간이 결과를 바꾸면 판정이 잘못된 것이다");
    }

    /**
     * 여러 지점이 <b>같은</b> 구역에 있을 때는 {@code intraZoneTravelMinutes}가 결과를 바꿔야
     * 한다. 지점을 등록해 매핑을 만들고 확인한다.
     */
    @Test
    void intraZoneMinutesTakeEffectWhenSitesShareAZone() {
        CollectionSiteRegistry shared = TestSites.allInZoneA();
        SimulationEngine eng = new SimulationEngine(new TrafficDataService(), shared,
                TravelTimeMatrix.empty());

        SimulationConfig zero = zoneProxy();
        SimulationConfig ten = zoneProxy();
        ten.setIntraZoneTravelMinutes(10);

        assertNotEquals(eng.run(zero, 1).getAvgCompletionMinutes(),
                        eng.run(ten, 1).getAvgCompletionMinutes(),
                "지점 4곳이 같은 구역이면 구역 내 이동시간이 순회 시간 전체를 좌우해야 한다");
    }

    /**
     * 같은 구역 안에서는 <b>방문 순서가 결과에 반영되지 않는다.</b> 이 모드로 지점 단위 경로
     * 최적화를 논할 수 없는 이유이며, 감출 것이 아니라 명시해야 하는 한계다.
     */
    @Test
    void visitOrderInsideOneZoneDoesNotChangeTheResult() {
        CollectionSiteRegistry shared = TestSites.allInZoneA();
        SimulationEngine eng = new SimulationEngine(new TrafficDataService(), shared,
                TravelTimeMatrix.empty());

        SimulationConfig forward = zoneProxy();
        forward.setIntraZoneTravelMinutes(4);
        forward.setRouteSequence(List.of("Node_A", "Node_B", "Node_C", "Node_D"));
        SimulationConfig reversed = zoneProxy();
        reversed.setIntraZoneTravelMinutes(4);
        reversed.setRouteSequence(List.of("Node_A", "Node_D", "Node_C", "Node_B"));

        assertEquals(eng.run(forward, 1).getAvgCompletionMinutes(),
                     eng.run(reversed, 1).getAvgCompletionMinutes(),
                "구역 내 이동이 통짜 평균이므로 순서가 결과를 바꿀 수 없다 — "
                        + "바뀐다면 이 모드의 서술이 실제 동작과 어긋난 것이다");
    }

    // ── V-T7 검증 ──────────────────────────────────────────────────────────

    @Test
    void zoneProxyPassesValidationWithTheShippedZoneMatrix() {
        ValidationResult r = validator().validate(zoneProxy());
        assertTrue(r.ready(), "구역 행렬 12쌍으로 4개 지점 경로가 통과해야 한다: " + r.errors());
    }

    /** 구역 좌표는 4곳뿐이다 — 5번째 지점은 덮이지 않고, 그 사실이 그대로 드러나야 한다. */
    @Test
    void fifthZoneIsStillUncovered() {
        SimulationConfig c = zoneProxy();
        c.setNumBuildings(5);
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready(), "측정하지 않은 구역 구간을 조용히 통과시키면 안 된다");
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("Node_E")),
                r.errors().toString());
    }

    @Test
    void rejectsNegativeIntraZoneMinutes() {
        SimulationConfig c = zoneProxy();
        c.setIntraZoneTravelMinutes(-1);
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream()
                .anyMatch(e -> "intraZoneTravelMinutes".equals(e.field())), r.errors().toString());
    }

    /**
     * 여러 지점이 한 구역에 몰려 있으면 <b>5번째 지점도 통과한다</b> — 구역 구간이 4곳
     * 안에서만 움직이기 때문이다. 지점 수가 아니라 구역 수가 제약이라는 점을 고정한다.
     */
    @Test
    void manySitesInFewZonesValidate() {
        SimulationConfig c = zoneProxy();
        c.setNumBuildings(4);
        ValidationResult r = new SimulationConfigValidator(new TrafficDataService(),
                TestSites.allInZoneA(), TravelTimeMatrix.empty()).validate(c);
        assertTrue(r.ready(), "네 지점이 모두 한 구역이면 구역 간 구간이 아예 없다: " + r.errors());
    }

    @Test
    void rejectsUnknownModeAndListsZoneProxy() {
        SimulationConfig c = new SimulationConfig();
        c.setTravelTimeMode("ZONE_PROXY");
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("ZONE_PROXY_HYBRID")),
                "허용 목록에 새 모드가 들어 있어야 한다: " + r.errors());
    }

    // ── 재현성(NFR-02) ─────────────────────────────────────────────────────

    @Test
    void sameSeedSameResult() {
        assertEquals(engine().run(zoneProxy(), 7).getTotalComplaints(),
                     engine().run(zoneProxy(), 7).getTotalComplaints());
    }
}
