package com.wastesim.edge.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

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

    @Test
    @DisplayName("6위치 전수 열거는 15쌍 × 4방향 = 60조합이고 ID가 P01~P60이다")
    void enumeratesSixtyCombinations() {
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));

        assertEquals(60, all.size());
        assertEquals("P01", all.get(0).id());
        assertEquals("P60", all.get(59).id());

        // 위치쌍은 순서 없는 조합이라 같은 배치가 두 번 나오면 안 된다.
        Set<String> shapes = new HashSet<>();
        for (FanLayoutCandidate c : all) {
            String a = c.position1().name() + ":" + c.flow1().name();
            String b = c.position2().name() + ":" + c.flow2().name();
            List<String> pair = new ArrayList<>(List.of(a, b));
            Collections.sort(pair);
            assertTrue(shapes.add(String.join("|", pair)), "중복 조합: " + c.id());
        }
    }

    @Test
    @DisplayName("열거 순서가 고정된다 — P02는 하단 흡기 + 상단 배기, P58은 우측 하단 흡기 + 우측 상단 배기")
    void enumerationOrderIsStable() {
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));
        Map<String, FanLayoutCandidate> byId = new LinkedHashMap<>();
        for (FanLayoutCandidate c : all) byId.put(c.id(), c);

        FanLayoutCandidate p02 = byId.get("P02");
        assertEquals(FanMountPosition.BOTTOM, p02.position1());
        assertEquals(FanFlowRole.INTAKE, p02.flow1());
        assertEquals(FanMountPosition.TOP, p02.position2());
        assertEquals(FanFlowRole.EXHAUST, p02.flow2());

        FanLayoutCandidate p58 = byId.get("P58");
        assertEquals(FanMountPosition.RIGHT_BOTTOM, p58.position1());
        assertEquals(FanFlowRole.INTAKE, p58.flow1());
        assertEquals(FanMountPosition.RIGHT_TOP, p58.position2());
        assertEquals(FanFlowRole.EXHAUST, p58.flow2());
    }

    @Test
    @DisplayName("위치를 2곳으로 줄이면 1쌍 × 4방향 = 4조합만 나온다")
    void enumerationHonoursPositionSubset() {
        List<FanLayoutCandidate> some = FanLayoutScoreModel.enumerateAll(
                List.of(FanMountPosition.BOTTOM, FanMountPosition.TOP));
        assertEquals(4, some.size());
        assertEquals("P01", some.get(0).id());
        assertEquals("P04", some.get(3).id());
    }
}
