package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.traffic.TravelTimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 엔진 수준에서 두 모드를 대조한다.
 *
 * <p>이 클래스의 첫 두 테스트가 이 작업 전체의 안전망이다 — <b>혼합 모드를 더했는데 상수
 * 모드의 결과가 움직이지 않았는가.</b> 움직였다면 앞으로 "OSRM이 얼마나 다른가"를 물을
 * 기준선이 사라진다.
 */
class EngineTravelTimeModeTest {

    private static SimulationEngine engine(TravelTimeMatrix matrix) {
        return new SimulationEngine(new TrafficDataService(), CollectionSiteRegistry.empty(), matrix);
    }

    private static TravelTimeMatrix measured() {
        TravelTimeMatrix m = new TravelTimeMatrix("/traffic/test-travel-times.json");
        m.load();
        return m;
    }

    /** 경로와 교통을 켠 기준 설정 — 이동시간이 결과에 실제로 반영되는 조건. */
    private static SimulationConfig routed() {
        SimulationConfig c = new SimulationConfig();
        c.setDays(3);
        c.setNumBuildings(4);
        c.setRouteSequence(List.of("Node_A", "Node_B", "Node_C", "Node_D"));
        c.setRouteTravelMinutes(15);
        c.setTruckType("LARGE_5TON");
        c.setTrafficEnabled(true);
        c.setTrafficProfileId("jangryang-weekday");
        return c;
    }

    // ── 기준선: 상수 모드는 움직이지 않는다 ────────────────────────────────

    /**
     * 자유주행시간이 있어도 상수 모드는 그것을 보지 않는다. 행렬을 물린 엔진과 빈 엔진의
     * 결과가 같아야 한다 — 다르면 상수 모드가 어딘가에서 새 데이터를 참조하고 있다는 뜻이다.
     */
    @Test
    void legacyModeIgnoresMeasuredTravelTimesEntirely() {
        SimulationConfig c = routed();

        var withMatrix = engine(measured()).run(c, 1);
        var withoutMatrix = engine(TravelTimeMatrix.empty()).run(c, 1);

        assertEquals(withoutMatrix.getAvgCompletionMinutes(), withMatrix.getAvgCompletionMinutes(),
                "상수 모드가 자유주행시간을 참조하면 안 된다");
        assertEquals(withoutMatrix.getTotalComplaints(), withMatrix.getTotalComplaints());
        assertEquals(withoutMatrix.getCollectedWasteKg(), withMatrix.getCollectedWasteKg());
    }

    /**
     * 상수 모드의 순회 시간은 공식으로 예측된다 — 세 구간이 각각
     * {@code round(15 / 1.0 x 도착 구역 혼잡)}이다. 이 값이 바뀌면 공식이 바뀐 것이다.
     */
    @Test
    void legacyCompletionMatchesTheSharedFormula() {
        SimulationConfig c = routed();
        c.setCollectionTimeLabel("12:00");

        double avg = engine(measured()).run(c, 1).getAvgCompletionMinutes();

        // 12:00 출발, 도착 구역 B·C·D의 12~13시 가중치로 누적된다.
        assertTrue(avg > 0, "경로가 있으면 순회 시간이 0일 수 없다");
        assertEquals(Math.rint(avg), avg, "구간 시간은 정수 분이라 합도 정수다");
    }

    // ── 혼합 모드는 실제로 다른 값을 낸다 ──────────────────────────────────

    /**
     * 실측 자유주행시간은 구간당 2.5~3.2분이고 상수는 15분이다. 그래서 혼합 모드의 순회
     * 시간은 <b>확실히 짧아야</b> 한다. 같다면 모드가 실제로 갈라지지 않은 것이다.
     */
    @Test
    void hybridModeIsMuchFasterThanTheConstant() {
        SimulationConfig legacy = routed();
        SimulationConfig hybrid = routed();
        hybrid.setTravelTimeMode("OSRM_HYBRID");

        double legacyAvg = engine(measured()).run(legacy, 1).getAvgCompletionMinutes();
        double hybridAvg = engine(measured()).run(hybrid, 1).getAvgCompletionMinutes();

        assertTrue(hybridAvg < legacyAvg,
                "혼합 " + hybridAvg + "분이 상수 " + legacyAvg + "분보다 짧아야 한다");
        assertTrue(legacyAvg / hybridAvg > 2.0,
                "실측 구간이 2.5~3.2분인데 상수가 15분이므로 배수가 커야 한다. 실제: "
                        + legacyAvg + " / " + hybridAvg);
    }

    /**
     * 정차·상차 시간을 분리한 것의 실제 효과 — 서비스 시간을 올리면 순회가 그만큼 늘어난다.
     * 상수 모드에는 이 축이 없었다(한 값이 이동과 정차를 함께 떠맡았다).
     */
    @Test
    void serviceTimePerSiteLengthensTheRoute() {
        SimulationConfig noService = routed();
        noService.setTravelTimeMode("OSRM_HYBRID");
        SimulationConfig withService = routed();
        withService.setTravelTimeMode("OSRM_HYBRID");
        withService.setServiceMinutesPerSite(5);

        double a = engine(measured()).run(noService, 1).getAvgCompletionMinutes();
        double b = engine(measured()).run(withService, 1).getAvgCompletionMinutes();

        // 구간 3개 x 5분 = 15분. 첫 지점의 서비스는 세지 않는다(순회 시계의 시작점이다).
        assertEquals(15.0, b - a, 0.001,
                "도착 지점마다 5분이 더해져야 한다. " + a + " -> " + b);
    }

    /** 서비스 시간은 상수 모드에서 무시된다 — 상수값이 이미 정차분을 떠맡고 있어 이중 계산이 된다. */
    @Test
    void serviceTimeIsIgnoredInLegacyMode() {
        SimulationConfig a = routed();
        SimulationConfig b = routed();
        b.setServiceMinutesPerSite(30);

        assertEquals(engine(measured()).run(a, 1).getAvgCompletionMinutes(),
                     engine(measured()).run(b, 1).getAvgCompletionMinutes(),
                "상수 모드에서 서비스 시간을 반영하면 정차분이 이중으로 세어진다");
    }

    /**
     * 검증기를 거치지 않고 혼합 모드로 들어오면 엔진이 예외를 던진다. 상수로 조용히
     * 되돌리지 않는 이유는, 그러면 나온 숫자가 무엇으로 계산된 것인지 알 수 없어서다.
     */
    @Test
    void hybridWithoutMeasuredTimesFailsLoudlyInTheEngine() {
        SimulationConfig c = routed();
        c.setTravelTimeMode("OSRM_HYBRID");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> engine(TravelTimeMatrix.empty()).run(c, 1));
        assertTrue(e.getMessage().contains("자유주행시간이 없습니다"), e.getMessage());
    }
}
