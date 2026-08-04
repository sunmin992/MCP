package com.wastesim.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 팬 <b>배열</b> 층 검증(실험 설계 §14). 단일 {@link FanSpec}은 {@link FanModelTest}가
 * 다루고, 여기서는 배열이 추가하는 것 — 여러 팬의 전력 합산(이중 계산 방지), RPM 출처
 * 우선순위, 기동 전력, 불확실성 경고, 그리고 <b>실측 전에는 위치별 냉각 차이를 만들지
 * 않는다</b>는 규칙을 회귀로 고정한다.
 */
class FanArraySpecTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ThermalSimulator sim = new ThermalSimulator();

    private EdgeArgs args(String json) {
        try { return new EdgeArgs(om.readTree(json)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ── §14-1 배열이 2개로 생성되고 fanCount는 fans.length에서 센다 ──────────
    @Test
    @DisplayName("Pi5 프리셋은 팬 2개 배열이고 fanCount는 입력이 아니라 fans.length에서 센다")
    void presetCreatesTwoFanArray() {
        FanArraySpec fa = FanArraySpec.pi5DualPreliminary(100);
        assertEquals(2, fa.fanCount());
        assertEquals(2, fa.fans().size());
        assertEquals("PI5_DUAL_40MM_PRELIMINARY", fa.presetId());
        assertFalse(fa.verified(), "검증 전 임시 사양이어야 한다");
        assertEquals(FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE, fa.sourceStatus());
    }

    // ── §14-2 5V × 전류 = 전력 ──────────────────────────────────────────────
    @Test
    @DisplayName("실측 전류가 있으면 전력 = 공급전압 × 전류")
    void measuredCurrentGivesVoltageTimesCurrent() {
        FanArraySpec fa = EdgeToolSupport.fanArray(args(
                "{\"fanArray\":{\"fans\":[{},{}],\"measuredCurrentA\":0.1,\"measurementScope\":\"DUAL_FAN_TOTAL\"}}"));
        assertNotNull(fa);
        assertEquals(0.5, fa.arrayPowerW(), 1e-9, "5V × 0.1A = 0.5W");
    }

    // ── §14-3 TACH 실측이 PWM 추정보다 우선 ─────────────────────────────────
    @Test
    @DisplayName("TACH 실측 RPM이 있으면 PWM 추정값을 이긴다")
    void tachMeasuredWinsOverPwmEstimate() {
        FanArraySpec pwm = FanArraySpec.pi5DualPreliminary(100);
        assertEquals(7750, pwm.effectiveRpm(), 1e-9, "TACH 없으면 PWM 추정 = 정격×100%");
        assertEquals(FanArraySpec.RpmSource.PWM_ESTIMATE, pwm.rpmSource());

        FanArraySpec tach = EdgeToolSupport.fanArray(args(
                "{\"fanArray\":{\"fans\":[{},{}],\"commandedPwmPercent\":100,\"ratedRpm\":7750,\"measuredArrayRpm\":4000}}"));
        assertEquals(4000, tach.effectiveRpm(), 1e-9, "TACH 실측이 이겨야 한다");
        assertEquals(FanArraySpec.RpmSource.TACH_MEASURED, tach.rpmSource());
    }

    // ── §14-4 RPM 증가 시 전력은 3승으로 ────────────────────────────────────
    @Test
    @DisplayName("RPM을 2배(50%→100%)로 올리면 전력은 8배")
    void powerRisesWithCubeOfRpm() {
        double half = FanArraySpec.pi5DualPreliminary(50).arrayPowerW();
        double full = FanArraySpec.pi5DualPreliminary(100).arrayPowerW();
        assertEquals(8.0, full / half, 1e-6, "전력은 회전수의 3승");
    }

    // ── §14-7 배열 정격을 팬 개수로 다시 곱하지 않는다 ──────────────────────
    @Test
    @DisplayName("배열 전체 정격(DUAL_FAN_ARRAY_ASSUMED)은 팬 개수로 다시 곱하지 않는다")
    void arrayTotalRatedIsNotMultipliedByCount() {
        // 프리셋은 0.625W가 이미 2연팬 전체 가정 → 100%에서 그대로 0.625W여야 한다.
        assertEquals(0.625, FanArraySpec.pi5DualPreliminary(100).arrayPowerW(), 1e-9);

        // 반대로 정격이 팬별(PER_FAN)이면 개수만큼 합산해야 한다.
        FanArraySpec perFan = EdgeToolSupport.fanArray(args(
                "{\"fanArray\":{\"fans\":[{},{}],\"ratedPowerW\":0.3,\"ratedCurrentA\":0.06,"
                + "\"ratedValueScope\":\"PER_FAN\"}}"));
        assertEquals(0.6, perFan.arrayPowerW(), 1e-9, "팬별 0.3W × 2개 = 0.6W");
    }

    // ── §14-8 기동 피크는 전체 실행 동안 지속되지 않는다 ────────────────────
    @Test
    @DisplayName("기동 지속시간을 모르면 기동 에너지는 총합에 넣지 않고 경고만 남긴다")
    void startupPeakIsNotSustained() {
        FanArraySpec noDur = FanArraySpec.pi5DualPreliminary(100);
        assertNull(noDur.startupEnergyJ(), "지속시간이 없으면 기동 에너지는 null");
        assertTrue(noDur.warnings().contains("STARTUP_ENERGY_NOT_INCLUDED"));

        // 지속시간이 있으면 그때만 피크×시간으로 계산한다.
        FanArraySpec withDur = EdgeToolSupport.fanArray(args(
                "{\"fanArray\":{\"fans\":[{},{}],\"startup\":{\"peakPowerW\":1.0,\"durationSec\":3.0}}}"));
        assertEquals(3.0, withDur.startupEnergyJ(), 1e-9, "피크 1.0W × 3초 = 3.0J");
    }

    // ── §14-9 불명확한 기준에 경고가 생성된다 ───────────────────────────────
    @Test
    @DisplayName("검증 전·PWM 추정·측정범위 불명확이면 각각 경고가 붙는다")
    void warningsForUnverifiedAndUnknownScope() {
        var w = FanArraySpec.pi5DualPreliminary(80).warnings();
        assertTrue(w.contains("FAN_SPEC_NOT_VERIFIED"), w.toString());
        assertTrue(w.contains("RPM_ESTIMATED_FROM_PWM"), w.toString());
        assertTrue(w.contains("FAN_POWER_SCOPE_UNKNOWN"), w.toString());
    }

    // ── §14-11 위치만 바꾸면 실측 전에는 냉각이 달라지지 않는다 ──────────────
    @Test
    @DisplayName("팬 위치·송풍 방향만 바꾸면 실측 보정 전에는 온도가 동일하다(메타데이터일 뿐)")
    void placementIsMetadataOnlyBeforeCalibration() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 30.0);
        FanArraySpec centered = EdgeToolSupport.fanArray(args(
                "{\"fanArray\":{\"fans\":[{\"offsetXmm\":0,\"flowDirection\":\"SUPPLY_DOWNWARD\"},{}],\"ratedRpm\":7750,\"commandedPwmPercent\":100}}"));
        FanArraySpec shifted = EdgeToolSupport.fanArray(args(
                "{\"fanArray\":{\"fans\":[{\"offsetXmm\":15,\"flowDirection\":\"EXHAUST_UPWARD\"},{}],\"ratedRpm\":7750,\"commandedPwmPercent\":100}}"));

        assertEquals(centered.coolingFan().effectiveRJa(3.9, 2.6, 1.5),
                shifted.coolingFan().effectiveRJa(3.9, 2.6, 1.5), 1e-9,
                "위치가 달라도 냉각(열저항)은 같아야 한다 — 실측 전 위치별 계수를 만들지 않는다");

        ThermalRun a = sim.run(BoardType.PI5, spec(p, centered));
        ThermalRun b = sim.run(BoardType.PI5, spec(p, shifted));
        assertEquals(a.peakTempC(), b.peakTempC(), 1e-9);
    }

    // ── 동시 입력 거부 (§13) ────────────────────────────────────────────────
    @Test
    @DisplayName("새 fanArray와 기존 fanRpm을 동시에 넣으면 거부한다(fail-closed)")
    void simultaneousLegacyAndArrayRejected() {
        EdgeArgs a = args("{\"fanArray\":{\"fans\":[{},{}]},\"fanRpm\":5000}");
        EdgeToolSupport.fanArray(a);
        assertTrue(a.hasErrors(), "동시 입력은 모호하므로 거부돼야 한다");
    }

    // ── FR-96 (§12) ─────────────────────────────────────────────────────────
    @Test
    @DisplayName("FR-96 — 냉각판(방열판) 없이 팬만 지정하면 거부한다")
    void fr96RejectsFanWithoutCoolingPlate() {
        // cooling=bare + 방열판 없음 + 팬 → 거부
        EdgeArgs bare = args("{\"cooling\":\"bare\",\"fanArray\":{\"presetId\":\"PI5_DUAL_40MM_PRELIMINARY\"}}");
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.BARE, 30.0);
        EdgeToolSupport.spec(bare, BoardType.PI5, p, 600, WorkloadMode.MAX_THROUGHPUT);
        assertTrue(bare.hasErrors(), "방열판 없는 팬은 FR-96으로 거부돼야 한다");

        // cooling=passive(방열판 있음) + 팬 → 통과
        EdgeArgs withPlate = args("{\"cooling\":\"passive\",\"fanArray\":{\"presetId\":\"PI5_DUAL_40MM_PRELIMINARY\"}}");
        ThermalParams p2 = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 30.0);
        EdgeToolSupport.spec(withPlate, BoardType.PI5, p2, 600, WorkloadMode.MAX_THROUGHPUT);
        assertFalse(withPlate.hasErrors(), "방열판이 있으면 팬은 정상이다: " + withPlate.errors());
    }

    // ── 배열 전력이 온도 적분에 안 들어가고 총합에는 들어간다 (§14-5·6 배열판) ──
    @Test
    @DisplayName("배열 팬 전력도 SoC 온도에는 안 들어가고 총 에너지에는 들어간다")
    void arrayFanPowerSeparatedFromTemperature() {
        ThermalParams p = ThermalParams.preset(BoardType.PI5, CoolingPreset.PASSIVE, 30.0);
        FanArraySpec fa = FanArraySpec.pi5DualPreliminary(100);
        ThermalRun run = sim.run(BoardType.PI5, spec(p, fa));

        assertTrue(run.fanEnergyJ() > 0, "배열 팬 에너지가 집계돼야 한다");
        assertEquals(run.energyJ() + run.fanEnergyJ(), run.totalEnergyJ(), 0.2,
                "기동시간 미지정이면 총합 = SoC + 팬(기동 제외)");
        assertNotNull(run.fanReport());
        assertEquals(2, run.fanReport().fanCount());
        assertFalse(run.fanReport().fanSpecVerified());
    }

    private ThermalSimulator.Spec spec(ThermalParams p, FanArraySpec fan) {
        return new ThermalSimulator.Spec(p, WorkloadMode.MAX_THROUGHPUT, 30.0, 600,
                RecoveryPolicy.NONE, 0.0, p.rJaKPerW(), 0.2, 5.0, true, null, null, fan);
    }
}
