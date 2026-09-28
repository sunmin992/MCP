package com.wastesim.simulation;

import com.wastesim.model.ScenarioScale;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.ZoneAssignmentRule;
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
 * 건물을 교통 구역에 배정하는 가정.
 *
 * <p>수거 지점 좌표가 없어서 {@code ZONE_PROXY_HYBRID}로 우회하는데, 그 우회는 <b>4동에서만
 * 작동했다.</b> 배정이 없으면 지점 id를 그대로 구역 id로 보고, 그 폴백은 이름이 겹치는
 * {@code Node_A~D}까지만 우연히 성립한다. 그래서 용량 축이 작동하는 26동 규모와 이동시간
 * 축을 함께 쓸 수 없었다 — 빠진 것은 좌표가 아니라 배정이었다.
 *
 * <p>이 클래스가 지키는 것은 두 가지다. <b>두 축이 함께 도는가</b>, 그리고 <b>그 결과가
 * 운영 예측이 아니라고 말하는가</b>. 두 번째가 깨지면 가정으로 만든 숫자가 장량동에 대한
 * 예측처럼 인용된다.
 */
class ZoneAssignmentRuleTest {

    private static SimulationEngine engine() {
        return new SimulationEngine(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    private static SimulationConfigValidator validator() {
        return new SimulationConfigValidator(new TrafficDataService(),
                CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    /** 26동 규모 + 구역 근사 이동시간 — 두 축을 함께 쓰는 설정. */
    private static SimulationConfig both(String rule) {
        SimulationConfig c = ScenarioScale.JANGRYANG_CAPACITY.newConfig();
        c.setDays(28);
        c.setTravelTimeMode("ZONE_PROXY_HYBRID");
        c.setZoneAssignmentRule(rule);
        c.setIntraZoneTravelMinutes(3);
        c.setServiceMinutesPerSite(5);
        return c;
    }

    // ── 이것이 이 작업의 목적이다 ──────────────────────────────────────────

    /**
     * <b>배정 없이는 26동을 돌릴 수 없다.</b> 이 실패가 기능의 출발점이므로 그대로 고정한다 —
     * 조용히 통과하게 만들면 {@code Node_E}가 존재하지 않는 구역을 가리키는 상태로 계산된다.
     */
    @Test
    void withoutAnAssignmentTwentySixBuildingsAreBlocked() {
        ValidationResult r = validator().validate(both("NONE"));
        assertFalse(r.ready(), "이름 폴백으로 26동이 통과하면 없는 구역으로 계산된다");
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("Node_D->Node_E")),
                r.errors().toString());
    }

    /** 배정하면 통과한다 — 용량 축과 이동시간 축이 처음으로 함께 돈다. */
    @Test
    void bothRulesLetTheCapacityAndTravelAxesRunTogether() {
        for (String rule : List.of("CONTIGUOUS", "ROUND_ROBIN")) {
            ValidationResult r = validator().validate(both(rule));
            assertTrue(r.ready(), rule + "이 통과해야 한다: " + r.errors());
        }
    }

    /** 두 축이 실제로 동시에 살아 있는지 결과로 확인한다. */
    @Test
    void capacityAxisStaysAliveWhileTravelIsComputedFromZones() {
        SimulationResult r = engine().run(both("CONTIGUOUS"), 42);
        assertTrue(r.getTruckUtilizationPercent() > 90.0,
                "용량 축: 가동률이 90%를 넘어야 한다 — " + r.getTruckUtilizationPercent());
        assertTrue(r.getAvgCompletionMinutes() > 100.0,
                "이동시간 축: 26지점 순회가 계산돼야 한다 — " + r.getAvgCompletionMinutes());
    }

    // ── 결과가 운영 예측이 아니라고 말한다 ─────────────────────────────────

    /**
     * <b>이 테스트가 사용자 요구의 핵심이다.</b> 배정을 가정한 결과는 운영 예측이 아니고,
     * 결과 자체가 그렇게 말해야 한다.
     */
    @Test
    void assumedAssignmentIsMarkedAsAnExperimentNotAPrediction() {
        SimulationResult r = engine().run(both("CONTIGUOUS"), 42);

        assertTrue(r.isNotForOperationalUse(), "가정으로 만든 결과가 운영 후보로 표시되면 안 된다");
        assertEquals("가정 비교 실험 (운영 예측 아님)", r.getResultUseLabel());
        assertTrue(r.getDataQualityFlags().contains("ZONE_ASSIGNMENT_ASSUMED"),
                r.getDataQualityFlags().toString());
        assertTrue(r.getDataQualityWarnings().stream()
                        .anyMatch(w -> w.contains("운영 예측이 아니라 가정 비교 실험")),
                r.getDataQualityWarnings().toString());
    }

    /** 어느 규칙을 썼는지가 문구에 남아야 한다 — 규칙에 따라 순회 시간이 크게 다르다. */
    @Test
    void theWarningNamesWhichRuleWasUsed() {
        assertTrue(engine().run(both("ROUND_ROBIN"), 42).getDataQualityWarnings().stream()
                        .anyMatch(w -> w.contains("ROUND_ROBIN")),
                "규칙 이름이 빠지면 두 실험 결과를 구별할 수 없다");
    }

    /**
     * 배정은 <b>상수 모드에서도</b> 표시된다. 혼잡 가중치를 구역별로 찾으므로 이동시간 모드와
     * 무관하게 배정이 결과에 관여한다.
     */
    @Test
    void assignmentIsFlaggedEvenInConstantTravelMode() {
        SimulationConfig c = ScenarioScale.JANGRYANG_CAPACITY.newConfig();
        c.setDays(28);
        c.setZoneAssignmentRule("CONTIGUOUS");

        assertTrue(engine().run(c, 42).getDataQualityFlags().contains("ZONE_ASSIGNMENT_ASSUMED"),
                "상수 모드에서도 배정이 혼잡 가중치 조회에 쓰인다");
    }

    /** 배정하지 않은 실행에는 그 표시가 없다 — 쓰지 않은 가정을 경고하면 안 된다. */
    @Test
    void noAssignmentFlagWhenNoRuleIsUsed() {
        SimulationConfig c = new SimulationConfig();
        c.setDays(28);
        SimulationResult r = engine().run(c, 42);
        assertFalse(r.getDataQualityFlags().contains("ZONE_ASSIGNMENT_ASSUMED"));
        assertEquals(List.of(), r.getDataQualityFlags(), "가정을 얹지 않은 실행이다");
    }

    /**
     * 다만 <b>논문 기준선도 운영 후보는 아니다.</b> 좌표를 쓰지 않고 이동시간이 손으로 정한
     * 상수이므로, 그 숫자 역시 장량동에 대한 예측이 아니다. "운영 후보"는 실제 지점 좌표로
     * 계산하고 가정을 얹지 않은 실행에만 붙는다 — 지금 그런 실행은 없다.
     */
    @Test
    void evenTheConstantBaselineIsNotAnOperationalPrediction() {
        SimulationConfig c = new SimulationConfig();
        c.setDays(28);
        SimulationResult r = engine().run(c, 42);
        assertTrue(r.isNotForOperationalUse(),
                "좌표를 쓰지 않은 상수 이동시간을 운영 예측이라고 부를 수 없다");
        assertEquals("가정 비교 실험 (운영 예측 아님)", r.getResultUseLabel());
    }

    // ── 조사한 사실이 가정을 이긴다 ────────────────────────────────────────

    /**
     * 등록된 지점의 {@code trafficZone}이 규칙보다 우선한다. 순서가 뒤집히면 가정이 조사한
     * 사실을 덮는다.
     */
    @Test
    void registeredZoneWinsOverTheRule() {
        CollectionSiteRegistry sites = TestSites.allInZoneA();   // 네 지점 모두 Node_A
        // 규칙만 보면 Node_B는 다른 구역이어야 하지만, 등록 정보가 Node_A라고 말한다.
        assertEquals("Node_A", sites.resolveZone("Node_B", ZoneAssignmentRule.ROUND_ROBIN, 4));
        // 등록되지 않은 지점은 규칙이 채운다.
        assertEquals("Node_C", sites.resolveZone("Node_G", ZoneAssignmentRule.ROUND_ROBIN, 26));
    }

    /** 규칙도 등록 정보도 없으면 지점 id를 그대로 쓴다 — 기존 폴백이 남아 있어야 한다. */
    @Test
    void fallsBackToTheSiteIdWhenNothingAssigns() {
        assertEquals("Node_G",
                CollectionSiteRegistry.empty().resolveZone("Node_G", ZoneAssignmentRule.NONE, 26));
    }

    // ── 두 규칙이 서로 다른 가정이다 ───────────────────────────────────────

    /**
     * {@code ROUND_ROBIN}은 26동·4구역·1대에서 <b>모든 구간이 구역을 넘는다.</b> 그래서
     * 구역 내 이동시간이 결과에 관여하지 않고, 순회 시간이 전부 OSRM 산출값과 정차시간에서
     * 나온다 — 가정 파라미터가 하나 줄어든 값이다.
     */
    @Test
    void roundRobinNeverUsesTheAssumedIntraZoneTime() {
        SimulationConfig a = both("ROUND_ROBIN");
        a.setIntraZoneTravelMinutes(0);
        SimulationConfig b = both("ROUND_ROBIN");
        b.setIntraZoneTravelMinutes(30);

        assertEquals(engine().run(a, 42).getAvgCompletionMinutes(),
                     engine().run(b, 42).getAvgCompletionMinutes(),
                "번갈아 배정에서 구역 내 이동이 결과를 바꾸면 배정이 잘못된 것이다");
        assertFalse(engine().run(a, 42).getDataQualityFlags().contains("INTRA_ZONE_TIME_ASSUMED"),
                "쓰이지 않은 가정을 경고하면 안 된다");
    }

    /**
     * {@code CONTIGUOUS}는 반대로 구역 내 이동이 대부분이라 그 가정값이 순회 시간을
     * 지배한다. 두 규칙이 같은 값을 내면 범위를 얻는 목적이 사라진다.
     */
    @Test
    void contiguousIsDominatedByTheAssumedIntraZoneTime() {
        SimulationConfig a = both("CONTIGUOUS");
        a.setIntraZoneTravelMinutes(0);
        SimulationConfig b = both("CONTIGUOUS");
        b.setIntraZoneTravelMinutes(6);

        double low = engine().run(a, 42).getAvgCompletionMinutes();
        double high = engine().run(b, 42).getAvgCompletionMinutes();
        assertTrue(high - low > 100.0,
                "연속 블록에서는 구역 내 이동이 22/25 구간이라 순회 시간이 크게 벌어져야 한다: "
                        + low + " -> " + high);

        assertNotEquals(engine().run(both("CONTIGUOUS"), 42).getAvgCompletionMinutes(),
                        engine().run(both("ROUND_ROBIN"), 42).getAvgCompletionMinutes(),
                "두 규칙이 같은 값을 내면 범위로 보고할 것이 없다");
    }

    // ── 배정 자체의 성질 ───────────────────────────────────────────────────

    @Test
    void contiguousMakesBlocksAndRoundRobinAlternates() {
        List<String> zones = List.of("Node_A", "Node_B", "Node_C", "Node_D");

        assertEquals("Node_A", ZoneAssignmentRule.CONTIGUOUS.assign(0, 8, zones).orElseThrow());
        assertEquals("Node_A", ZoneAssignmentRule.CONTIGUOUS.assign(1, 8, zones).orElseThrow());
        assertEquals("Node_B", ZoneAssignmentRule.CONTIGUOUS.assign(2, 8, zones).orElseThrow());
        assertEquals("Node_D", ZoneAssignmentRule.CONTIGUOUS.assign(7, 8, zones).orElseThrow());

        assertEquals("Node_A", ZoneAssignmentRule.ROUND_ROBIN.assign(0, 8, zones).orElseThrow());
        assertEquals("Node_B", ZoneAssignmentRule.ROUND_ROBIN.assign(1, 8, zones).orElseThrow());
        assertEquals("Node_A", ZoneAssignmentRule.ROUND_ROBIN.assign(4, 8, zones).orElseThrow());
    }

    /** 마지막 건물이 구역 범위를 넘지 않아야 한다 — 비례 배분의 경계 조건. */
    @Test
    void contiguousNeverOverrunsTheZoneList() {
        List<String> zones = List.of("Node_A", "Node_B", "Node_C", "Node_D");
        for (int total = 1; total <= 26; total++) {
            for (int i = 0; i < total; i++) {
                assertTrue(zones.contains(
                                ZoneAssignmentRule.CONTIGUOUS.assign(i, total, zones).orElseThrow()),
                        "건물 " + i + "/" + total + "이 구역 목록을 벗어났다");
            }
        }
    }

    @Test
    void noneAssignsNothing() {
        assertFalse(ZoneAssignmentRule.NONE.assigns());
        assertTrue(ZoneAssignmentRule.NONE
                .assign(0, 4, List.of("Node_A")).isEmpty());
        assertTrue(ZoneAssignmentRule.CONTIGUOUS.assign(0, 4, List.of()).isEmpty(),
                "구역이 없으면 배정할 것이 없다");
    }

    /**
     * 구역 목록이 정렬돼 있어야 같은 설정이 같은 배정을 낸다. 순서가 흔들리면 재현성
     * (NFR-02)이 깨지는데, 결과가 달라지는 방식이 조용해서 알아채기 어렵다.
     */
    @Test
    void zoneOrderIsDeterministic() {
        List<String> first = CollectionSiteRegistry.empty().sortedZoneIds();
        assertEquals(List.of("Node_A", "Node_B", "Node_C", "Node_D"), first);
        assertEquals(first, CollectionSiteRegistry.empty().sortedZoneIds());
    }

    @Test
    void sameSeedSameResult() {
        assertEquals(engine().run(both("CONTIGUOUS"), 7).getTotalComplaints(),
                     engine().run(both("CONTIGUOUS"), 7).getTotalComplaints());
    }

    // ── 이름 해석과 V-S2 ───────────────────────────────────────────────────

    @Test
    void ruleNameIsCaseAndHyphenTolerant() {
        assertEquals(ZoneAssignmentRule.ROUND_ROBIN, ZoneAssignmentRule.fromName("round-robin"));
        assertEquals(ZoneAssignmentRule.CONTIGUOUS, ZoneAssignmentRule.fromName("연속 블록"));
        assertEquals(ZoneAssignmentRule.NONE, ZoneAssignmentRule.fromName(null));
        assertEquals(ZoneAssignmentRule.NONE, ZoneAssignmentRule.fromName(""));
    }

    @Test
    void defaultIsNoAssignment() {
        assertEquals(ZoneAssignmentRule.NONE, new SimulationConfig().resolveZoneAssignmentRule(),
                "기본값이 바뀌면 기존 실행이 조용히 가정을 쓰게 된다");
    }

    /**
     * 오타를 조용히 {@code NONE}으로 떨어뜨리면, 배정한 줄 알았던 실행이 5동부터 엉뚱한
     * 오류를 내고 원인이 오타였다는 것을 알 수 없다.
     */
    @Test
    void rejectsUnknownRuleName() {
        SimulationConfig c = both("CONTIGUOUS_BLOCKS");
        ValidationResult r = validator().validate(c);
        assertFalse(r.ready());
        assertTrue(r.errors().stream().anyMatch(e -> "zoneAssignmentRule".equals(e.field())),
                r.errors().toString());
    }

    /** 설정 복사에 규칙이 실려야 한다 — 시나리오 실험이 copy()로 변형을 만든다. */
    @Test
    void copyCarriesTheRule() {
        assertEquals("ROUND_ROBIN", both("ROUND_ROBIN").copy().getZoneAssignmentRule());
    }
}
