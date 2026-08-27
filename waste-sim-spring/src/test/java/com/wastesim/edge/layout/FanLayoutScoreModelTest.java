package com.wastesim.edge.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 점수 모델의 골든 회귀 — 엑셀 dual_fan_all_layouts_preliminary.xlsx와
 * 숫자가 어긋나면 여기서 깨진다. 이 도구의 유일한 근거가 그 시트이므로,
 * 시트와의 일치가 곧 정확성 기준이다.
 */
class FanLayoutScoreModelTest {

    @Test
    @DisplayName("장착 위치 6곳의 높이·측면·위치효율이 엑셀 가정 시트와 같다")
    void positionsMatchAssumptionSheet() {
        assertEquals(6, FanMountPosition.values().length);

        assertEquals(0, FanMountPosition.BOTTOM.level());
        assertEquals(FanMountPosition.Side.CENTER, FanMountPosition.BOTTOM.side());
        assertEquals(0.95, FanMountPosition.BOTTOM.efficiency(), 1e-9);
        assertEquals("하단", FanMountPosition.BOTTOM.koLabel());

        assertEquals(2, FanMountPosition.TOP.level());
        assertEquals(FanMountPosition.Side.CENTER, FanMountPosition.TOP.side());
        assertEquals(0.90, FanMountPosition.TOP.efficiency(), 1e-9);

        assertEquals(0.78, FanMountPosition.LEFT_BOTTOM.efficiency(), 1e-9);
        assertEquals(FanMountPosition.Side.LEFT, FanMountPosition.LEFT_BOTTOM.side());
        assertEquals(0.82, FanMountPosition.LEFT_TOP.efficiency(), 1e-9);
        assertEquals(0.78, FanMountPosition.RIGHT_BOTTOM.efficiency(), 1e-9);
        assertEquals(FanMountPosition.Side.RIGHT, FanMountPosition.RIGHT_BOTTOM.side());
        assertEquals(0.82, FanMountPosition.RIGHT_TOP.efficiency(), 1e-9);
    }

    @Test
    @DisplayName("위치·방향은 영문 키와 한글 라벨을 모두 받고, 모르는 값은 null이다")
    void parseAcceptsBothNotations() {
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("bottom"));
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("  BOTTOM  "));
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("하단"));
        assertEquals(FanMountPosition.LEFT_TOP, FanMountPosition.parse("left_top"));
        assertEquals(FanMountPosition.LEFT_TOP, FanMountPosition.parse("좌측 상단"));
        assertNull(FanMountPosition.parse("뒷면"));
        assertNull(FanMountPosition.parse(null));

        assertEquals(FanFlowRole.INTAKE, FanFlowRole.parse("intake"));
        assertEquals(FanFlowRole.INTAKE, FanFlowRole.parse("흡기"));
        assertEquals(FanFlowRole.EXHAUST, FanFlowRole.parse("exhaust"));
        assertEquals(FanFlowRole.EXHAUST, FanFlowRole.parse("배기"));
        assertNull(FanFlowRole.parse("순환"));
        assertNull(FanFlowRole.parse(null));
    }
}
