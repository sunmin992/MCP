package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.WasteType;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 수거 주기가 차량 운행에 반영되는지 검증 (DEBUGGING_ISSUES.md W-03).
 *
 * <p>예전에는 {@code isTruckDay()}가 공휴일·주말만 봤다. 그래서 격일 수거로 설정해도
 * <b>차량은 매일 돌면서</b> 실제로는 아무 용기도 비우지 않는 날이 생겼고, 그 날의 교통
 * 민원과 이동 시간이 결과에 그대로 쌓였다. 게다가 전역 {@code collectionIntervalDays}는
 * 엔진에서 아예 읽히지 않아 설정해도 아무 효과가 없었다.
 */
class CollectionIntervalTest {

    private final TrafficDataService trafficData = new TrafficDataService();

    private SimulationConfig base(int intervalDays) {
        SimulationConfig c = new SimulationConfig();
        // 18:00은 실측 프로파일에서 경로 후반 노드가 RED인 시각이다(Node_C 2.14 · Node_A 2.12,
        // RED 기준 1.45). 첫 노드는 혼잡 판정에서 제외되므로 뒤쪽 노드가 걸려야 한다.
        //
        // 예전에는 13:00이었고 프로파일을 defaultProfileId()로 받았다. 그 기본값이 해시
        // 순서로 정해지던 시절에 <b>통행량 기반 구 프로파일</b>(피크 13시)이 돌아왔기 때문에
        // 통과하던 것이다 — 이 테스트의 전제가 맵 순회 순서에 얹혀 있었다. 실측 프로파일의
        // 피크는 18시이므로, 필요한 프로파일을 이름으로 명시하고 시각을 그에 맞춘다.
        c.setCollectionTimeLabel("18:00");
        c.setDays(8);
        c.setSeeds(1);
        c.setCollectionIntervalDays(intervalDays);
        c.setTrafficEnabled(true);
        c.setTrafficProfileId(TrafficDataService.DEFAULT_PROFILE_ID);
        c.setRouteTravelMinutes(15);         // 이동이 있어야 구간별 혼잡 판정이 일어난다
        return c;
    }

    private SimulationResult run(SimulationConfig c) {
        return new SimulationEngine(trafficData).run(c, 42);
    }

    @Test
    @DisplayName("격일 수거면 교통 민원이 매일 수거의 절반 수준이어야 한다")
    void everyOtherDayHalvesTrafficComplaints() {
        double daily = run(base(1)).getTrafficPenalty();
        double everyOther = run(base(2)).getTrafficPenalty();

        assertTrue(daily > 0, "이 테스트는 교통 민원이 실제로 발생하는 조건이어야 한다");
        assertTrue(everyOther < daily,
                "격일인데 매일과 교통 민원이 같으면 차량이 비수거일에도 돈 것이다 ("
                        + everyOther + " vs " + daily + ")");
        // 8일 중 격일이면 운행일이 절반이므로 민원도 대략 절반이다(정확히 비례하지는
        // 않을 수 있어 여유를 둔다).
        assertEquals(daily / 2.0, everyOther, daily * 0.25,
                "격일 수거의 교통 민원이 매일 수거의 절반 근처여야 한다");
    }

    @Test
    @DisplayName("3일 주기면 교통 민원이 더 줄어든다 — 주기가 길수록 단조 감소")
    void longerIntervalMeansFewerTrafficComplaints() {
        double d1 = run(base(1)).getTrafficPenalty();
        double d2 = run(base(2)).getTrafficPenalty();
        double d4 = run(base(4)).getTrafficPenalty();
        assertTrue(d1 > d2 && d2 > d4,
                "주기가 길수록 운행일이 줄어야 한다 (" + d1 + " > " + d2 + " > " + d4 + ")");
    }

    @Test
    @DisplayName("기본 설정(매일 수거)은 결과가 달라지지 않는다 — 하위호환")
    void dailyCollectionUnchanged() {
        SimulationConfig c = base(1);
        SimulationResult a = run(c);
        SimulationResult b = run(c);
        assertEquals(a.getTotalComplaints(), b.getTotalComplaints());
        assertEquals(a.getTrafficPenalty(), b.getTrafficPenalty());
    }

    @Test
    @DisplayName("유형별 주기가 모두 긴 날은 차량이 아예 뜨지 않는다")
    void noTruckWhenNoTypeIsDue() {
        SimulationConfig c = base(1);
        // 두 유형 모두 2일 주기 — 홀수 날에는 비울 것이 없다.
        c.setWasteTypes(List.of(type("A", 0.5, 2), type("B", 0.5, 2)));
        double both2 = run(c).getTrafficPenalty();

        SimulationConfig daily = base(1);
        daily.setWasteTypes(List.of(type("A", 0.5, 1), type("B", 0.5, 1)));
        double both1 = run(daily).getTrafficPenalty();

        assertTrue(both2 < both1,
                "모든 유형이 비수거일이면 차량이 뜨면 안 된다 (" + both2 + " vs " + both1 + ")");
    }

    @Test
    @DisplayName("한 유형이라도 수거일이면 차량은 운행한다")
    void truckRunsWhenAnyTypeIsDue() {
        SimulationConfig mixed = base(1);
        // 하나는 매일, 하나는 3일 주기 — 매일 뜨는 유형이 있으니 매일 운행해야 한다.
        mixed.setWasteTypes(List.of(type("A", 0.5, 1), type("B", 0.5, 3)));
        SimulationConfig allDaily = base(1);
        allDaily.setWasteTypes(List.of(type("A", 0.5, 1), type("B", 0.5, 1)));

        assertEquals(run(allDaily).getTrafficPenalty(), run(mixed).getTrafficPenalty(),
                "매일 수거 유형이 하나라도 있으면 운행일 수가 같아야 한다");
    }

    private WasteType type(String key, double fraction, int intervalDays) {
        WasteType w = new WasteType();
        w.setKey(key);
        w.setFraction(fraction);
        w.setCapacity(30);
        w.setThreshold(0.8);
        w.setIntervalDays(intervalDays);
        return w;
    }
}
