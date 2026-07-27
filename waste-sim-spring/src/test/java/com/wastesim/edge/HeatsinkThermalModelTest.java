package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 방열판 배치 모델 검증. 절대값은 경험식이라 단정할 수 없으므로,
 * <b>배치를 나쁘게 바꾸면 반드시 나빠진다</b>는 방향성(단조성)과 물리적 상·하한을 검증한다.
 * 이 성질이 깨지면 "어느 배치가 나은가"라는 결론 자체가 뒤집히기 때문에 가장 중요한 테스트다.
 */
class HeatsinkThermalModelTest {

    private final HeatsinkThermalModel model = new HeatsinkThermalModel();

    private HeatsinkLayout layout(String name, double offsetX, HeatsinkLayout.FinAlignment align,
                                  HeatsinkLayout.Airflow air, HeatsinkLayout.Tim tim) {
        return new HeatsinkLayout(name,
                new HeatsinkLayout.Heatsink(40, 40, 3, 10, 12, 1.2, HeatsinkLayout.Material.ALUMINUM),
                new HeatsinkLayout.Placement(offsetX, 0, align), air, tim, List.of());
    }

    private static HeatsinkLayout.Airflow natural() {
        return new HeatsinkLayout.Airflow(HeatsinkLayout.AirflowType.NATURAL, 0, 0, 0);
    }

    private static HeatsinkLayout.Airflow fan(double mps, double distMm) {
        return new HeatsinkLayout.Airflow(HeatsinkLayout.AirflowType.FORCED, mps, 0, distMm);
    }

    private static HeatsinkLayout.Tim pad(double mm) {
        return new HeatsinkLayout.Tim(HeatsinkLayout.TimType.PAD, mm, 0);
    }

    private double rJa(HeatsinkLayout l) {
        return model.evaluate(BoardType.PI4, l, 25.0, 70.0).rJaKPerW();
    }

    @Test
    @DisplayName("SoC 중심에서 멀어질수록 열저항이 단조 증가한다 — 배치 실험의 핵심 가설")
    void offsetMonotonicallyWorsens() {
        double prev = -1;
        for (double offset : new double[]{0, 5, 10, 15, 20, 25}) {
            double r = rJa(layout("off" + offset, offset, HeatsinkLayout.FinAlignment.ALIGNED, natural(), pad(0.5)));
            assertTrue(r > prev, offset + "mm 어긋남이 이전보다 나빠야 한다(" + r + " vs " + prev + ")");
            prev = r;
        }
    }

    @Test
    @DisplayName("완전히 빗나간 방열판은 무냉각과 같아지고, 그보다 나빠지지는 않는다")
    void completelyMissedHeatsinkFallsBackToBare() {
        double bare = BoardType.PI4.rJaKPerW(CoolingPreset.BARE);
        double missed = rJa(layout("완전 이탈", 60, HeatsinkLayout.FinAlignment.ALIGNED, natural(), pad(0.5)));
        assertTrue(missed <= bare + 0.01, "방열판을 붙였는데 무냉각보다 나빠질 수는 없다");
        assertEquals(bare, missed, 0.05);
    }

    @Test
    @DisplayName("어떤 방열판을 붙여도 다이-패키지 열저항(R_jc) 아래로는 내려가지 않는다")
    void cannotBeatJunctionToCaseFloor() {
        var huge = new HeatsinkLayout("초대형+강풍",
                new HeatsinkLayout.Heatsink(120, 120, 5, 30, 40, 1.0, HeatsinkLayout.Material.COPPER),
                new HeatsinkLayout.Placement(0, 0, HeatsinkLayout.FinAlignment.ALIGNED),
                fan(15, 0), new HeatsinkLayout.Tim(HeatsinkLayout.TimType.PASTE, 0.02, 0), List.of());
        assertTrue(rJa(huge) > BoardType.PI4.rJcKPerW(), "R_jc는 물리적 하한이라 넘을 수 없다");
    }

    @Test
    @DisplayName("팬을 붙이면 좋아지고, 팬이 멀어질수록 그 이득이 줄어든다")
    void fanHelpsAndDistanceMatters() {
        double none = rJa(layout("자연대류", 0, HeatsinkLayout.FinAlignment.ALIGNED, natural(), pad(0.5)));
        double near = rJa(layout("팬 5mm", 0, HeatsinkLayout.FinAlignment.ALIGNED, fan(2.5, 5), pad(0.5)));
        double far = rJa(layout("팬 80mm", 0, HeatsinkLayout.FinAlignment.ALIGNED, fan(2.5, 80), pad(0.5)));
        assertTrue(near < none);
        assertTrue(near < far);
        assertTrue(far < none, "멀어도 팬은 자연대류보다는 낫다");
    }

    @Test
    @DisplayName("핀이 기류를 가로막으면 같은 방열판이라도 나빠진다")
    void finAlignmentMatters() {
        double aligned = rJa(layout("나란함", 0, HeatsinkLayout.FinAlignment.ALIGNED, fan(2.0, 5), pad(0.5)));
        double cross = rJa(layout("가로막음", 0, HeatsinkLayout.FinAlignment.CROSS, fan(2.0, 5), pad(0.5)));
        assertTrue(cross > aligned, "같은 부품도 90° 돌려 붙이면 성능이 달라진다");
    }

    @Test
    @DisplayName("두꺼운 서멀패드보다 얇은 그리스가 낫다")
    void thinnerTimIsBetter() {
        double thickPad = rJa(layout("패드 1mm", 0, HeatsinkLayout.FinAlignment.ALIGNED, natural(), pad(1.0)));
        double thinPaste = rJa(layout("그리스 0.05mm", 0, HeatsinkLayout.FinAlignment.ALIGNED, natural(),
                new HeatsinkLayout.Tim(HeatsinkLayout.TimType.PASTE, 0.05, 0)));
        assertTrue(thinPaste < thickPad);
    }

    @Test
    @DisplayName("자연대류에서 핀을 지나치게 촘촘히 하면 면적이 늘어도 오히려 나빠진다")
    void tooManyFinsHurtInNaturalConvection() {
        var sparse = new HeatsinkLayout("핀 10개",
                new HeatsinkLayout.Heatsink(40, 40, 3, 10, 12, 1.2, HeatsinkLayout.Material.ALUMINUM),
                new HeatsinkLayout.Placement(0, 0, HeatsinkLayout.FinAlignment.ALIGNED),
                natural(), pad(0.5), List.of());
        var dense = new HeatsinkLayout("핀 40개",
                new HeatsinkLayout.Heatsink(40, 40, 3, 40, 12, 0.6, HeatsinkLayout.Material.ALUMINUM),
                new HeatsinkLayout.Placement(0, 0, HeatsinkLayout.FinAlignment.ALIGNED),
                natural(), pad(0.5), List.of());
        assertTrue(rJa(dense) > rJa(sparse), "핀 간격이 좁으면 경계층이 겹쳐 면적 증가분이 성능으로 이어지지 않는다");
        assertFalse(model.evaluate(BoardType.PI4, dense, 25.0, 70.0).warnings().isEmpty(),
                "왜 나빠졌는지 경고로 알려줘야 한다");
    }

    @Test
    @DisplayName("접촉면 겹침 계산이 기하학적으로 정확하다")
    void overlapGeometryIsExact() {
        assertEquals(225.0, HeatsinkThermalModel.rectOverlapMm2(15, 15, 40, 40, 0, 0), 1e-9);
        assertEquals(0.0, HeatsinkThermalModel.rectOverlapMm2(15, 15, 40, 40, 40, 0), 1e-9);
        // 다이 15mm, 방열판 40mm, X로 20mm 이동 → X 겹침 7.5mm × Y 전체 15mm
        assertEquals(112.5, HeatsinkThermalModel.rectOverlapMm2(15, 15, 40, 40, 20, 0), 1e-9);
    }

    @Test
    @DisplayName("방열판 밖의 부수 발열점은 미커버로 보고되고 경고가 붙는다")
    void uncoveredHotspotIsReported() {
        var l = new HeatsinkLayout("PMIC 미커버",
                new HeatsinkLayout.Heatsink(20, 20, 2, 6, 8, 1.0, HeatsinkLayout.Material.ALUMINUM),
                new HeatsinkLayout.Placement(0, 0, HeatsinkLayout.FinAlignment.ALIGNED),
                natural(), pad(0.5),
                List.of(new HeatsinkLayout.Hotspot("PMIC", 22, -6, 1.2)));
        var r = model.evaluate(BoardType.PI4, l, 25.0, 70.0);
        assertEquals(1, r.hotspots().size());
        assertFalse(r.hotspots().get(0).coveredByHeatsink());
        assertTrue(r.hotspots().get(0).estimatedTempC() > 25.0);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("PMIC")));
    }
}
