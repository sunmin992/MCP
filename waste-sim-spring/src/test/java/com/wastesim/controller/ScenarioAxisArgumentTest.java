package com.wastesim.controller;

import com.wastesim.model.SimulationConfig;
import com.wastesim.service.ScenarioService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ValidationResult;
import com.wastesim.tool.ToolResult;
import com.wastesim.web.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 시나리오 축 배열 인자가 <b>실행 전에</b> 검증되는지 고정한다.
 *
 * <p>두 가지 구멍을 막은 자리다.
 *
 * <ol>
 *   <li><b>monthlyFactor가 검증 게이트를 통째로 우회했다.</b> {@code validateMonthlyFactor}는
 *       길이 12 강제와 유한·양수 검사를 이미 갖고 있었지만, 컨트롤러가 사용자 값을 base가
 *       아니라 시나리오 안의 복사본에만 주입해서 검증 시점에는 항상 null이었다. 그 결과
 *       5개짜리 배열이 {@code monthlyWasteFactor[month % length]}로 조용히 순환 적용돼
 *       1·6·11월이 같은 값이 되고도 아무 경고가 없었다(D-26 위반).</li>
 *   <li><b>숫자가 아닌 원소가 500을 냈다.</b> 축 배열 파싱이 {@code runScenarioCustom}
 *       바깥에서 일어나 {@code ClassCastException}이 ApiError를 거치지 못했다 — 사용자
 *       입력 오류인데 서버 장애처럼 보였다.</li>
 * </ol>
 */
class ScenarioAxisArgumentTest {

    private final ScenarioService scenario = mock(ScenarioService.class);
    private final SimulationTool tool = mock(SimulationTool.class);
    private final ScenarioController controller = new ScenarioController(scenario, tool);

    /**
     * 파사드 대역이 <b>실제 검증기</b>를 돌린다 — 목에 규칙을 다시 적으면 목을 검증하게 되고,
     * 정작 이 수정이 고친 것(사용자 값이 검증기에 닿는가)은 확인되지 않는다.
     */
    private final SimulationConfigValidator realValidator =
            new SimulationConfigValidator(new TrafficDataService());

    private void withRealValidation() {
        when(tool.runScenarioCustom(any(), any())).thenAnswer(inv -> {
            SimulationConfig base = inv.getArgument(0);
            ValidationResult vr = realValidator.validate(base);
            return vr.ready() ? ToolResult.ok("ran") : ToolResult.rejected(vr.errors());
        });
    }

    private static ApiError errorOf(ResponseEntity<?> resp) {
        assertEquals(400, resp.getStatusCode().value());
        assertInstanceOf(ApiError.class, resp.getBody());
        return (ApiError) resp.getBody();
    }

    // ── 1. monthlyFactor가 검증 게이트에 닿는가 ─────────────────────────────

    @Test
    @DisplayName("사용자가 넘긴 monthlyFactor가 base에 실려 검증 게이트를 통과한다")
    void monthlyFactorReachesValidationGate() {
        withRealValidation();

        controller.monthlyWaste(Map.of("monthlyFactor", List.of(
                1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)));

        var captor = org.mockito.ArgumentCaptor.forClass(SimulationConfig.class);
        verify(tool).runScenarioCustom(captor.capture(), any());
        assertNotNull(captor.getValue().getMonthlyWasteFactor(),
                "base에 싣지 않으면 validateMonthlyFactor가 null을 보고 즉시 return한다 — "
                        + "검증 함수가 있어도 이 경로에서는 아무것도 검사하지 않게 된다");
        assertEquals(12, captor.getValue().getMonthlyWasteFactor().length);
    }

    @Test
    @DisplayName("가중치가 12개가 아니면 조용히 순환 적용하지 않고 400으로 거부한다")
    void wrongLengthMonthlyFactorIsRejected() {
        withRealValidation();

        ResponseEntity<?> resp = controller.monthlyWaste(
                Map.of("monthlyFactor", List.of(1.0, 1.2, 0.9, 1.1, 1.0)));

        ApiError err = errorOf(resp);
        assertEquals("VALIDATION", err.code());
        assertTrue(err.errors().stream().anyMatch(e -> "monthlyWasteFactor".equals(e.field())),
                "길이 5는 month % 5로 순환돼 1·6·11월이 같은 값이 된다 — 실행되면 안 된다");
    }

    @Test
    @DisplayName("가중치에 0 이하 값이 있으면 400으로 거부한다")
    void nonPositiveMonthlyFactorIsRejected() {
        withRealValidation();

        ResponseEntity<?> resp = controller.monthlyWaste(Map.of("monthlyFactor", List.of(
                1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -0.5)));

        assertEquals("VALIDATION", errorOf(resp).code());
    }

    @Test
    @DisplayName("monthlyFactor를 아예 안 보내면 기본 계절 가중치로 실행된다 — 하위호환")
    void absentMonthlyFactorStillRunsWithDefaults() {
        withRealValidation();

        ResponseEntity<?> resp = controller.monthlyWaste(Map.of());

        assertEquals(200, resp.getStatusCode().value(),
                "\"지정하지 않음\"과 \"잘못된 값을 지정함\"은 다른 요청이다");
    }

    // ── 2. 숫자가 아닌 축 원소 ─────────────────────────────────────────────

    @Test
    @DisplayName("축 배열에 숫자가 아닌 원소가 있으면 500이 아니라 400 VALIDATION이다")
    void nonNumericAxisElementIsStructured400() {
        var e = assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.infraGrid(Map.of("capacities", List.of(30.0, "많이", 50.0))),
                "예전에는 ClassCastException이 그대로 올라가 500이 됐다");

        ApiError err = errorOf(controller.badScenarioArg(e));
        assertEquals("VALIDATION", err.code());
        assertTrue(err.errors().stream().anyMatch(v -> "capacities".equals(v.field())),
                "어느 필드의 몇 번째 원소가 문제인지 알려줘야 고칠 수 있다");
        verify(tool, never()).runScenarioCustom(any(), any());
    }

    @Test
    @DisplayName("NaN·Infinity 축 값도 파싱 단계에서 거부된다")
    void nonFiniteAxisElementIsRejected() {
        var e = assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.behaviorGrid(Map.of("alphas", List.of(10.0, Double.NaN))));

        ApiError err = errorOf(controller.badScenarioArg(e));
        assertEquals("VALIDATION", err.code());
        assertTrue(err.errors().stream().anyMatch(v -> "alphas".equals(v.field())));
    }

    @Test
    @DisplayName("배열이 아닌 값을 축으로 보내면 400으로 거부된다")
    void nonArrayAxisIsRejected() {
        var e = assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.multiTruck(Map.of("truckCounts", "1,2,4")));

        assertEquals("VALIDATION", errorOf(controller.badScenarioArg(e)).code());
    }

    @Test
    @DisplayName("빈 축 배열은 오류가 아니라 시나리오 기본 축으로 실행된다")
    void emptyAxisFallsBackToDefaults() {
        when(tool.runScenarioCustom(any(), any())).thenReturn(ToolResult.ok("ran"));

        ResponseEntity<?> resp = controller.infraGrid(Map.of("capacities", List.of()));

        assertEquals(200, resp.getStatusCode().value(),
                "빈 배열은 축을 지정하지 않은 것과 같게 다룬다(기존 동작 유지)");
    }

    @Test
    @DisplayName("정수 시나리오 축은 소수·문자열을 조용히 변환하지 않는다")
    void integerScenarioAxesRejectFractionsAndMalformedPairs() {
        assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.multiTruck(Map.of("truckCounts", List.of(1, 2.7, 4))));
        assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.density(Map.of("densities", List.of(List.of(4, "25")))));
        assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.truckRoute(Map.of("routeTravelMinutes", 15.5)));
        assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.multiTruck(Map.of("truckCounts", List.of(0, 2))));
        assertThrows(ScenarioController.ScenarioArgException.class,
                () -> controller.density(Map.of("densities", List.of(List.of(-1, 25)))));
        verify(tool, never()).runScenarioCustom(any(), any());
    }

}
