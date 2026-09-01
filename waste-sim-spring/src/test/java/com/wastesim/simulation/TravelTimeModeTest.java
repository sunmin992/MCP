package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.TravelTimeMode;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationResult;
import com.wastesim.traffic.TravelTimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이동시간 모드 두 갈래를 고정한다.
 *
 * <p>가장 중요한 단언은 <b>상수 모드가 예전과 같은 결과를 낸다</b>는 것이다. 혼합 모드를
 * 더하면서 기존 결과가 조금이라도 움직이면 앞으로 나오는 모든 비교의 기준선이 사라진다.
 */
class TravelTimeModeTest {

    private static TravelTimeMatrix measured() {
        TravelTimeMatrix m = new TravelTimeMatrix("/traffic/test-travel-times.json");
        m.load();
        return m;
    }

    private static SimulationConfigValidator validator(TravelTimeMatrix m) {
        return new SimulationConfigValidator(new TrafficDataService(), CollectionSiteRegistry.empty(), m);
    }

    // ── 모드 해석 ──────────────────────────────────────────────────────────

    @Test
    void unspecifiedModeMeansLegacy() {
        assertEquals(TravelTimeMode.LEGACY_CONSTANT, TravelTimeMode.fromName(null));
        assertEquals(TravelTimeMode.LEGACY_CONSTANT, TravelTimeMode.fromName(""));
        assertEquals(TravelTimeMode.LEGACY_CONSTANT, new SimulationConfig().resolveTravelTimeMode(),
                "기본값이 바뀌면 기존 사용자의 결과가 조용히 달라진다");
    }

    @Test
    void modeNameIsCaseAndHyphenTolerant() {
        assertEquals(TravelTimeMode.OSRM_HYBRID, TravelTimeMode.fromName("osrm_hybrid"));
        assertEquals(TravelTimeMode.OSRM_HYBRID, TravelTimeMode.fromName("OSRM-HYBRID"));
        assertThrows(IllegalArgumentException.class, () -> TravelTimeMode.fromName("OSRM"));
    }

    // ── 공식 ───────────────────────────────────────────────────────────────

    /** 상수 모드 공식은 손대지 않았다 — 기본분 / 기동성 x 혼잡. */
    @Test
    void legacyFormulaIsUnchanged() {
        assertEquals(15, TravelTimeCalculator.hopMinutes(15, 1.0, 1.0));
        assertEquals(9, TravelTimeCalculator.hopMinutes(15, 1.6, 1.0));
        assertEquals(23, TravelTimeCalculator.hopMinutes(15, 1.0, 1.55));
    }

    /**
     * 혼합 공식 — round(자유주행분 / 기동성 x 혼잡) + 서비스분.
     * 162초 = 2.7분이므로 5톤에 혼잡이 없으면 3분(반올림)이다.
     */
    @Test
    void hybridFormulaUsesFreeFlowThenAddsService() {
        assertEquals(3, TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.0, 1.0, 0));
        assertEquals(8, TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.0, 1.0, 5),
                "서비스 시간은 주행분에 더해진다");
        assertEquals(2, TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.6, 1.0, 0),
                "기동성이 좋으면 주행이 빠르다");
        assertEquals(4, TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.0, 1.55, 0),
                "혼잡은 주행분에 곱한다");
    }

    /** 기동성은 주행에만 곱한다 — 상하차에 걸리는 시간이 차종 배수로 설명되지는 않는다. */
    @Test
    void mobilityDoesNotShortenServiceTime() {
        int big = TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.0, 1.0, 10);
        int small = TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.6, 1.0, 10);
        assertEquals(13, big);
        assertEquals(12, small);
        assertEquals(1, big - small, "차이는 주행분에서만 나야 한다 (3분 vs 2분)");
    }

    @Test
    void hybridRejectsNonsenseInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> TravelTimeCalculator.hopMinutesFromFreeFlow(-1.0, 1.0, 1.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TravelTimeCalculator.hopMinutesFromFreeFlow(Double.NaN, 1.0, 1.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TravelTimeCalculator.hopMinutesFromFreeFlow(162.0, 1.0, 1.0, -1));
    }

    // ── 자유주행시간 행렬 ──────────────────────────────────────────────────

    /** 방향이 있다 — OSRM은 일방통행과 중앙분리대를 반영한다. */
    @Test
    void matrixIsDirectional() {
        TravelTimeMatrix m = measured();
        assertEquals(162.0, m.freeFlowSeconds("Node_A", "Node_B").getAsDouble());
        assertEquals(186.6, m.freeFlowSeconds("Node_B", "Node_A").getAsDouble());
    }

    @Test
    void matrixAnswersNothingForUnknownHop() {
        TravelTimeMatrix m = measured();
        assertTrue(m.freeFlowSeconds("Node_A", "Node_Z").isEmpty(), "0으로 채우면 안 된다");
        assertTrue(m.freeFlowSeconds(null, "Node_B").isEmpty());
    }

    @Test
    void missingHopsNamesEveryUncoveredLeg() {
        TravelTimeMatrix m = measured();
        assertEquals(List.of(), m.missingHops(List.of("Node_A", "Node_B", "Node_C")));
        assertEquals(List.of("Node_D->Node_E"),
                m.missingHops(List.of("Node_C", "Node_D", "Node_E")));
        assertEquals(List.of(), m.missingHops(List.of("Node_A")), "구간이 없으면 빠진 것도 없다");
        assertEquals(List.of(), m.missingHops(null));
    }

    @Test
    void matrixRejectsMalformedEntries() {
        assertTrue(TravelTimeMatrix.empty().isEmpty());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TravelTimeMatrix("/traffic/없는-파일.json").load());
        assertTrue(e.getMessage().contains("클래스패스에 없습니다"), e.getMessage());
    }

    // ── V-T6 검증 ──────────────────────────────────────────────────────────

    /**
     * 혼합 모드를 골랐는데 자유주행시간이 없으면 막는다. 조용히 상수로 되돌리면 두 모드의
     * 결과가 구별되지 않아, 모드를 고른 이유 자체가 사라진다.
     */
    @Test
    void hybridWithoutMeasuredTimesIsRejected() {
        SimulationConfig c = new SimulationConfig();
        c.setTravelTimeMode("OSRM_HYBRID");

        ValidationResult r = validator(TravelTimeMatrix.empty()).validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> "travelTimeMode".equals(e.field())),
                r.errors().toString());
    }

    @Test
    void hybridWithMeasuredTimesPasses() {
        SimulationConfig c = new SimulationConfig();
        c.setTravelTimeMode("OSRM_HYBRID");
        c.setRouteSequence(List.of("Node_A", "Node_B", "Node_C", "Node_D"));

        assertTrue(validator(measured()).validate(c).ready());
    }

    /** 경로의 일부만 있으면 통과시키지 않는다 — 구간마다 다른 축의 값이 섞인 합계는 뜻이 없다. */
    @Test
    void hybridWithPartialCoverageIsRejected() {
        SimulationConfig c = new SimulationConfig();
        c.setTravelTimeMode("OSRM_HYBRID");
        c.setNumBuildings(5);
        c.setRouteSequence(List.of("Node_A", "Node_B", "Node_C", "Node_D", "Node_E"));

        ValidationResult r = validator(measured()).validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("Node_D->Node_E")),
                r.errors().toString());
    }

    @Test
    void unknownModeIsRejected() {
        SimulationConfig c = new SimulationConfig();
        c.setTravelTimeMode("OSRM");
        assertTrue(validator(measured()).validate(c).errors().stream()
                .anyMatch(e -> "travelTimeMode".equals(e.field())));
    }

    @Test
    void negativeServiceTimeIsRejected() {
        SimulationConfig c = new SimulationConfig();
        c.setServiceMinutesPerSite(-1);
        assertTrue(validator(measured()).validate(c).errors().stream()
                .anyMatch(e -> "serviceMinutesPerSite".equals(e.field())));
    }

    /** 상수 모드는 행렬이 비어 있어도 아무 영향을 받지 않는다. */
    @Test
    void legacyModeNeedsNoMeasuredTimes() {
        assertTrue(validator(TravelTimeMatrix.empty()).validate(new SimulationConfig()).ready());
    }
}
