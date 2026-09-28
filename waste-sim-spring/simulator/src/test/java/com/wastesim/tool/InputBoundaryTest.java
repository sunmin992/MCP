package com.wastesim.tool;

import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 입력 경계 강화 검증 (DEBUGGING_ISSUES.md W-04).
 *
 * <p><b>잘못된 값이 조용히 다른 값으로 바뀌는</b> 부류를 막는다 — 12:99가 13:39가 되면
 * 사용자가 요청한 시각과 다른 시각으로 실험이 돌아가고 아무도 모른다.
 *
 * <p>같은 성격의 엣지 쪽 항목(E-03 정수 절삭·E-05 보정 CSV의 NaN)은 라즈베리파이 도메인과
 * 함께 이 저장소를 떠났다. 남은 것은 장량동 입력 경계뿐이다.
 */
class InputBoundaryTest {


    // ── W-04: HH:MM 파서 ────────────────────────────────────────────────

    @Test
    @DisplayName("정상적인 시각 표기는 그대로 해석한다")
    void validTimesParse() {
        assertEquals(510, SimulationConfig.hhmmToMinutes("8:30"));
        assertEquals(510, SimulationConfig.hhmmToMinutes("08:30"));
        assertEquals(1439, SimulationConfig.hhmmToMinutes("23:59"));
        assertEquals(0, SimulationConfig.hhmmToMinutes("00:00"));
    }

    /**
     * 12:99는 총 분이 819(=13:39)라 하루 범위 안에 들어가므로 이후 범위 검증도 통과한다.
     * 즉 사용자가 요청한 시각과 다른 시각으로 실험이 돌아가고 아무도 모른다.
     */
    @Test
    @DisplayName("분이 60 이상이면 거부한다 — 12:99가 13:39로 둔갑하면 안 된다")
    void minuteOverflowRejected() {
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.hhmmToMinutes("12:99"));
    }

    @Test
    @DisplayName("시가 24 이상이거나 형식이 깨지면 거부한다")
    void malformedTimesRejected() {
        for (String bad : new String[]{"24:00", "25:30", "12:", ":30", "abc", "", "12:30:45", "-1:00"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SimulationConfig.hhmmToMinutes(bad), "거부해야 한다: " + bad);
        }
    }
}
