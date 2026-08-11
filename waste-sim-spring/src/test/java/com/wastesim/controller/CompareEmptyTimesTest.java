package com.wastesim.controller;

import com.wastesim.service.SimulationService;
import com.wastesim.tool.SimulationTool;
import com.wastesim.web.ApiError;
import com.wastesim.web.CompareRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A-02 해소 — {@code POST /api/simulation/compare}의 빈 {@code times} 배열은
 * 기본값으로 대체하지 않고 400으로 거부한다.
 *
 * <p>예전 동작은 빈 배열을 조용히 기본값 3종(10:00·12:00·14:00)으로 갈아끼우고 200을
 * 돌려줬다. 그러면 클라이언트는 자기가 보낸 times가 통째로 비었다는 것을 알 수 없고,
 * <b>요청하지도 않은 시각의 결과</b>를 자기 요청의 답으로 읽는다 — D-26(조용한 보정
 * 금지)이 정확히 막으려는 상황이다.
 *
 * <p>단, times를 <b>아예 안 보낸</b> 경우는 여전히 기본값으로 실행된다. "지정하지 않음"과
 * "비어 있는 값을 지정함"은 다른 요청이고, 전자까지 막으면 기존 클라이언트가 깨진다.
 */
class CompareEmptyTimesTest {

    private final SimulationService simulationService = mock(SimulationService.class);
    private final SimulationTool tool = mock(SimulationTool.class);
    private final SimulationController controller = new SimulationController(simulationService, tool);

    @Test
    @DisplayName("빈 times 배열은 기본값으로 대체되지 않고 400 VALIDATION으로 거부된다")
    void emptyTimesIsRejected() {
        CompareRequest body = new CompareRequest();
        body.setTimes(List.of());

        ResponseEntity<?> resp = controller.compare(body);

        assertEquals(400, resp.getStatusCode().value());
        assertInstanceOf(ApiError.class, resp.getBody());
        ApiError err = (ApiError) resp.getBody();
        assertEquals("VALIDATION", err.code());
        assertTrue(err.errors().stream().anyMatch(e -> "times".equals(e.field())),
                "어느 필드가 문제인지 알려줘야 클라이언트가 요청을 고칠 수 있다");

        // 거부는 실행 전에 일어나야 한다 — fail-closed(C3).
        verifyNoInteractions(tool);
        verifyNoInteractions(simulationService);
    }

    @Test
    @DisplayName("null times도 같은 이유로 거부된다")
    void nullTimesIsRejected() {
        CompareRequest body = new CompareRequest();
        body.setTimes(null);

        assertEquals(400, controller.compare(body).getStatusCode().value());
        verifyNoInteractions(tool);
    }

    @Test
    @DisplayName("times를 아예 지정하지 않으면 기존대로 기본값 3종으로 실행된다(하위호환)")
    void absentTimesStillUsesDefaults() {
        CompareRequest body = new CompareRequest();   // setter가 호출되지 않은 상태 = 미지정

        assertFalse(body.isTimesExplicitlyEmpty());
        assertEquals(List.of("10:00", "12:00", "14:00"), body.getTimes());
    }
}
