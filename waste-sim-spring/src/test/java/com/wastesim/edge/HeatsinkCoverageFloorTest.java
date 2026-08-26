package com.wastesim.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 접촉률 극소 구간(0.1%~1%)의 coverage 일관성 회귀 테스트(부록 B.2 E-08 결정 반영).
 *
 * <p>결정 내용: <b>거부하지 않고 계산하되, 계산용 덮임률 하나만 쓴다.</b>
 *
 * <ul>
 *   <li>{@code reportedCoverage} — 실제 겹친 넓이 그대로. "얼마나 어긋났는가"를 답한다</li>
 *   <li>{@code effectiveCoverage} — 모델 유효 하한(1%)을 적용한 값.
 *       R_tim·R_misalign·R_spread가 <b>전부</b> 이 하나를 쓴다</li>
 * </ul>
 *
 * <p>예전에는 R_misalign만 하한 1%를 쓰고 R_tim·R_spread는 실제 면적을 써서, 같은 "접촉 면적"
 * 개념이 항마다 다른 값을 가졌다. 그래서 덮임률을 1%에서 0.1%로 떨어뜨리면 R_misalign은 멈춘
 * 채 R_tim만 10배로 뛰는, 물리적으로 설명할 수 없는 구간이 생겼다.
 *
 * <p>배치를 거부(INVALID_LAYOUT)하지 않기로 한 이유는 {@link HeatsinkThermalModel#MIN_EFFECTIVE_COVERAGE}
 * 주석에 적어 뒀다 — 요약하면 이 배치는 실제로 만들 수 있는 형상이고, 거부하면 "오프셋을 주면
 * 얼마나 나빠지는가"라는 이 도구의 질문에 곡선 끝까지 답할 수 없기 때문이다.
 */
class HeatsinkCoverageFloorTest {

    private final HeatsinkThermalModel model = new HeatsinkThermalModel();

    /**
     * Pi4 패키지는 한 변 15mm(면적 225mm²)이고 방열판은 40×40mm다. X로 offX만큼 밀면
     * 겹침 폭이 {@code 27.5 − offX}mm가 되므로 덮임률 = {@code (27.5 − offX) / 15}이다.
     * 즉 offX 27.35mm가 1%, 27.485mm가 0.1%에 해당한다 — 이 좁은 구간을 직접 겨냥한다.
     */
    private static double offsetForCoverage(double coverage) {
        return 27.5 - coverage * 15.0;
    }

    private HeatsinkThermalModel.Result evaluate(double offsetXMm) {
        HeatsinkLayout l = new HeatsinkLayout("cov",
                new HeatsinkLayout.Heatsink(40, 40, 3, 10, 12, 1.2, HeatsinkLayout.Material.ALUMINUM),
                new HeatsinkLayout.Placement(offsetXMm, 0, HeatsinkLayout.FinAlignment.ALIGNED),
                new HeatsinkLayout.Airflow(HeatsinkLayout.AirflowType.NATURAL, 0, 0, 0),
                new HeatsinkLayout.Tim(HeatsinkLayout.TimType.PAD, 0.5, 0),
                List.of());
        return model.evaluate(BoardType.PI4, l, 25.0, 70.0);
    }

    @Test
    @DisplayName("하한 위에서는 두 덮임률이 같고, 계산도 예전과 동일하다")
    void aboveFloorBothCoveragesAgree() {
        HeatsinkThermalModel.Result r = evaluate(offsetForCoverage(0.5));

        assertFalse(r.coverageFloorApplied(), "50% 덮임에 하한이 걸릴 이유가 없다");
        assertEquals(r.reportedCoverage(), r.effectiveCoverage(), 1e-9);
        assertEquals(r.effectiveCoverage(), r.coverage(), 1e-9, "구 필드는 계산용 값을 가리킨다");
        assertEquals(r.contactAreaMm2(), r.effectiveContactAreaMm2(), 0.15,
                "하한이 걸리지 않으면 실제 접촉면적과 계산용 면적이 같다");
    }

    @Test
    @DisplayName("0.1%~1% 구간에서 세 저항 항이 같은 덮임률을 참조한다 — 항마다 다른 면적을 쓰지 않는다")
    void allThreeResistanceTermsShareOneCoverage() {
        // 이 구간 전체에서 effectiveCoverage는 1%로 고정되므로, 세 항 모두 값이 변하면 안 된다.
        HeatsinkThermalModel.Result atOnePercent = evaluate(offsetForCoverage(0.01));
        HeatsinkThermalModel.Result atTenth = evaluate(offsetForCoverage(0.001));

        assertEquals(HeatsinkThermalModel.MIN_EFFECTIVE_COVERAGE, atTenth.effectiveCoverage(), 1e-9);
        assertEquals(atOnePercent.breakdown().rMisalign(), atTenth.breakdown().rMisalign(), 1e-6,
                "R_misalign은 예전에도 하한을 썼다");
        assertEquals(atOnePercent.breakdown().rTim(), atTenth.breakdown().rTim(), 1e-6,
                "R_tim도 같은 하한을 써야 한다 — 예전에는 여기만 실제 면적이라 10배로 뛰었다");
        assertEquals(atOnePercent.breakdown().rSpread(), atTenth.breakdown().rSpread(), 1e-6,
                "R_spread도 같은 하한을 써야 한다");
    }

    @Test
    @DisplayName("실제 덮임률은 하한과 무관하게 그대로 보고된다 — 조용한 보정이 아니다(D-26)")
    void reportedCoverageKeepsTheTruth() {
        HeatsinkThermalModel.Result r = evaluate(offsetForCoverage(0.001));

        assertTrue(r.coverageFloorApplied(), "하한을 적용했다는 사실이 결과에 실려야 한다");
        assertEquals(0.001, r.reportedCoverage(), 5e-4, "실제 겹침은 0.1%다");
        assertEquals(0.01, r.effectiveCoverage(), 1e-9, "계산에는 1%를 썼다");
        assertTrue(r.reportedCoverage() < r.effectiveCoverage());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("하한")),
                "무엇을 왜 보정했는지 경고에 적어야 한다");
    }

    @Test
    @DisplayName("극소 구간에서도 열저항은 단조 증가를 유지하고 무냉각을 넘지 않는다")
    void staysMonotonicAndBoundedByBare() {
        double bare = BoardType.PI4.rJaKPerW(CoolingPreset.BARE);
        double prev = -1;
        for (double coverage : new double[]{0.05, 0.02, 0.01, 0.005, 0.001}) {
            double rJa = evaluate(offsetForCoverage(coverage)).rJaKPerW();
            assertTrue(rJa >= prev, "덮임률이 줄면 나빠지기만 해야 한다(" + coverage + ")");
            assertTrue(rJa <= bare + 0.01,
                    "방열판을 붙였는데 무냉각보다 나빠질 수는 없다 — 하한을 적용한 결과는 "
                            + "'방열판이 없는 것과 다름없다'로 수렴해야 한다");
            prev = rJa;
        }
    }

    @Test
    @DisplayName("겹침이 정확히 0이면 하한을 적용하지 않는다 — 무냉각과 같아야 한다")
    void zeroOverlapIsNotFloored() {
        HeatsinkThermalModel.Result r = evaluate(60);   // 방열판이 패키지를 완전히 벗어남
        double bare = BoardType.PI4.rJaKPerW(CoolingPreset.BARE);

        assertEquals(0.0, r.reportedCoverage(), 1e-9);
        assertEquals(0.0, r.effectiveCoverage(), 1e-9,
                "접촉이 아예 없으면 계산용 덮임률도 0이다 — 여기에 1% 하한을 먹이면 "
                        + "없는 접촉면을 만들어 내 무냉각보다 좋은 값이 나온다");
        assertFalse(r.coverageFloorApplied());
        assertEquals(bare, r.rJaKPerW(), 0.05, "접촉 경로가 없으면 무냉각과 같다");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("전혀 겹치지 않는다")));
    }

    @Test
    @DisplayName("MCP 응답이 보고용·계산용 덮임률을 모두 싣는다")
    void mcpResponseCarriesBothCoverages() {
        // 도구 응답 조립은 Result만 있으면 되므로, 여기서는 필드 존재와 값의 일치만 본다.
        HeatsinkThermalModel.Result r = evaluate(offsetForCoverage(0.001));

        assertEquals(r.effectiveCoverage(), r.coverage(), 1e-9,
                "구 coverage 필드는 계산용 값을 그대로 유지한다(기존 클라이언트의 숫자가 바뀌지 않는다)");
        assertTrue(r.effectiveContactAreaMm2() > r.contactAreaMm2(),
                "하한이 걸린 구간에서는 계산용 면적이 실제 겹침보다 크다");
    }
}
