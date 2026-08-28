package com.wastesim.controller;

import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.ScenarioService;
import com.wastesim.service.SimulationService;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import com.wastesim.web.ApiError;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 수거 시각 스윕의 REST 경로가 <b>잘못된 범위·간격을 실행 전에</b> 400으로 거부하는지 본다.
 *
 * <p>여기서 막는 것은 단순한 입력 오류가 아니라 <b>서버가 멈추는 결함</b>이다.
 * {@code stepMinutes}가 0 이하이면 스윕 루프의 {@code m += stepMin}이 전진하지 않아
 * 루프가 끝나지 않고, 매 회 실제 시뮬레이션까지 돌린다 — 요청 하나로 워커 스레드가
 * 영구히 묶인다. 스윕 인자는 {@code SimulationConfig}가 아니라 별도 파라미터라
 * 기존 설정 검증 게이트가 보지 못하는 자리였다.
 *
 * <p>{@link ScenarioAxisArgumentTest}가 고정한 규칙(축 인자도 게이트 안에서 검사한다)의
 * 같은 계열이다 — 다만 저쪽은 500으로 새던 문제였고, 이쪽은 응답 자체가 돌아오지 않던
 * 문제다.
 */
class CollectionSweepGuardTest {

    private final SimulationService sim = mock(SimulationService.class);
    private final ScenarioService scenario = new ScenarioService(sim);
    private final SimulationTool tool = new SimulationTool(
            new SimulationConfigValidator(new TrafficDataService()),
            mock(SimulationModelRegistry.class),
            scenario,
            new SimpleMeterRegistry());
    private final ScenarioController controller = new ScenarioController(scenario, tool);

    private void engineAlwaysReturns(double complaints) {
        when(sim.runExperiment(any(SimulationConfig.class))).thenAnswer(inv -> {
            SimulationResult r = new SimulationResult();
            r.setMeanComplaints(complaints);
            r.setStdComplaints(0.0);
            return r;
        });
    }

    private static ApiError rejectionOf(ResponseEntity<?> resp) {
        assertEquals(400, resp.getStatusCode().value(), "사용자 입력 오류는 400이어야 한다");
        assertInstanceOf(ApiError.class, resp.getBody());
        return (ApiError) resp.getBody();
    }

    private static void assertBlames(ApiError err, String field) {
        List<ValidationError> errors = err.errors();
        assertFalse(errors.isEmpty(), "어느 필드가 틀렸는지 말해 줘야 사용자가 고칠 수 있다");
        assertTrue(errors.stream().anyMatch(e -> field.equals(e.field())),
                "필드 " + field + "를 지목해야 한다: " + errors);
    }

    @Test
    @DisplayName("stepMinutes=0은 무한 루프에 들어가기 전에 400으로 거부된다")
    void zeroStepIsRejectedWithoutHanging() {
        engineAlwaysReturns(10.0);

        // 가드가 사라지면 이 호출은 실패가 아니라 영원히 돌아오지 않는다.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ApiError err = rejectionOf(controller.collectionSweep(Map.of("stepMinutes", 0)));
            assertBlames(err, "stepMinutes");
        });
        verify(sim, never()).runExperiment(any(SimulationConfig.class));
    }

    @Test
    @DisplayName("음수 stepMinutes도 같은 이유로 거부된다")
    void negativeStepIsRejected() {
        engineAlwaysReturns(10.0);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ApiError err = rejectionOf(controller.collectionSweep(Map.of("stepMinutes", -15)));
            assertBlames(err, "stepMinutes");
        });
        verify(sim, never()).runExperiment(any(SimulationConfig.class));
    }

    @Test
    @DisplayName("시작이 종료보다 늦으면 거부한다 — 조용히 빈 결과를 내지 않는다")
    void invertedRangeIsRejected() {
        engineAlwaysReturns(10.0);

        ApiError err = rejectionOf(controller.collectionSweep(
                Map.of("start", "18:00", "end", "06:00")));

        assertBlames(err, "start");
        verify(sim, never()).runExperiment(any(SimulationConfig.class));
    }

    @Test
    @DisplayName("후보가 상한을 넘으면 거부하고 상한값을 알려 준다")
    void tooManyCandidatesAreRejected() {
        engineAlwaysReturns(10.0);

        ApiError err = rejectionOf(controller.collectionSweep(
                Map.of("start", "00:00", "end", "23:59", "stepMinutes", 1)));

        assertBlames(err, "stepMinutes");
        assertTrue(err.errors().stream().anyMatch(
                        e -> e.message().contains(String.valueOf(ScenarioService.MAX_SWEEP_POINTS))),
                "상한을 알려 줘야 간격을 얼마로 넓힐지 정할 수 있다: " + err.errors());
        verify(sim, never()).runExperiment(any(SimulationConfig.class));
    }

    @Test
    @DisplayName("정상 범위는 그대로 실행돼 200과 결과를 돌려준다(과잉 차단 없음)")
    void validSweepStillRuns() {
        engineAlwaysReturns(10.0);

        ResponseEntity<?> resp = controller.collectionSweep(
                Map.of("start", "06:00", "end", "18:00", "stepMinutes", 60));

        assertEquals(200, resp.getStatusCode().value());
        verify(sim, times(13)).runExperiment(any(SimulationConfig.class));
    }

    @Test
    @DisplayName("인자를 아예 주지 않으면 기본 범위(06~18시, 60분)로 실행된다")
    void defaultsStillRun() {
        engineAlwaysReturns(10.0);

        ResponseEntity<?> resp = controller.collectionSweep(null);

        assertEquals(200, resp.getStatusCode().value());
        verify(sim, times(13)).runExperiment(any(SimulationConfig.class));
    }
}
