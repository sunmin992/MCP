package com.wastesim.simulation;

import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RoutePlannerTest {

    private static SimulationConfig config() {
        SimulationConfig c = new SimulationConfig();
        c.setNumBuildings(4);
        c.setTruckCount(2);
        return c;
    }

    @Test
    void unspecifiedAndEmptyOrdersUseNaturalRoundRobin() {
        SimulationConfig c = config();
        List<List<Integer>> expected = List.of(List.of(0, 2), List.of(1, 3));
        assertEquals(expected, RoutePlanner.assignRoutes(c));
        c.setRouteSequence(List.of());
        assertEquals(expected, RoutePlanner.assignRoutes(c));
    }

    @Test
    void explicitOrderIsCanonicalizedBeforeAssignment() {
        SimulationConfig c = config();
        List<String> requested = new ArrayList<>(List.of("node_b", "NODE_c", "Node_A", "node_d"));
        c.setRouteSequence(requested);

        List<List<Integer>> routes = RoutePlanner.assignRoutes(c);
        assertEquals(List.of(List.of(1, 0), List.of(2, 3)), routes);
        assertEquals(List.of("node_b", "NODE_c", "Node_A", "node_d"), requested);
        requested.set(0, "Node_D");
        assertEquals(List.of(List.of(1, 0), List.of(2, 3)), routes,
                "반환 경로는 입력 목록 변경과 독립적인 스냅샷이어야 한다");
    }

    static Stream<List<String>> invalidOrders() {
        return Stream.of(
                List.of("Node_B", "Node_A", "Node_C"),
                List.of("Node_B", "Node_A", "Node_C", "Node_D", "Node_E"),
                List.of("Node_B", "Node_B", "Node_C", "Node_D"),
                List.of("Node_B", "node_b", "Node_C", "Node_D"),
                List.of("Node_B", "Node_A", "Node_C", "Node_E"),
                List.of("Node_B", "Node_A", "Node_C", "invalid"),
                Arrays.asList("Node_B", "Node_A", "Node_C", null));
    }

    @ParameterizedTest
    @MethodSource("invalidOrders")
    void invalidOrdersRetainDefensiveFallbackButStillFailValidation(List<String> requested) {
        SimulationConfig c = config();
        c.setRouteSequence(requested);
        c.setTravelTimeMode("ZONE_PROXY_HYBRID");
        assertEquals(List.of(List.of(0, 2), List.of(1, 3)), RoutePlanner.assignRoutes(c));

        var result = new SimulationConfigValidator(new TrafficDataService()).validate(c);
        assertFalse(result.ready());
        assertTrue(result.errors().stream().anyMatch(e -> "routeSequence".equals(e.field())),
                "방어적 폴백이 잘못된 사용자 요청을 허용해서는 안 된다");
    }

    @Test
    void unevenRoutesKeepTruckIndicesAndVisitOrder() {
        SimulationConfig c = config();
        c.setTruckCount(3);
        assertEquals(List.of(List.of(0, 3), List.of(1), List.of(2)), RoutePlanner.assignRoutes(c));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6, Integer.MAX_VALUE})
    void surplusTrucksDoNotAllocateEmptyRoutes(int trucks) {
        SimulationConfig c = config();
        c.setTruckCount(trucks);
        assertEquals(List.of(List.of(0), List.of(1), List.of(2), List.of(3)),
                RoutePlanner.assignRoutes(c));
    }

    @Test
    void nonpositiveTruckCountKeepsLegacyDefensiveFallback() {
        SimulationConfig c = config();
        c.setTruckCount(0);
        assertEquals(List.of(List.of(0, 1, 2, 3)), RoutePlanner.assignRoutes(c));
        assertFalse(new SimulationConfigValidator(new TrafficDataService()).validate(c).ready());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 27})
    void invalidBuildingCountsRemainValidationErrors(int buildings) {
        SimulationConfig c = config();
        c.setNumBuildings(buildings);
        c.setTravelTimeMode("ZONE_PROXY_HYBRID");
        assertThrows(IllegalArgumentException.class, () -> RoutePlanner.assignRoutes(c));
        var result = assertDoesNotThrow(() -> new SimulationConfigValidator(new TrafficDataService())
                .validate(c));
        assertFalse(result.ready());
        assertTrue(result.errors().stream().anyMatch(e -> "numBuildings".equals(e.field())));
    }

    @Test
    void legacyNodeIdEntryPointsKeepTheirContract() {
        for (int index = 0; index < 26; index++) {
            String id = "Node_" + (char) ('A' + index);
            assertEquals(id, SimulationEngine.nodeId(index));
            assertEquals(index, SimulationEngine.nodeIndex(id.toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals(-1, SimulationEngine.nodeIndex(null));
        assertEquals(-1, SimulationEngine.nodeIndex("Node_AA"));
    }
}
