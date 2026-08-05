package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 팬 RPM 스윕·최적점 탐색 회귀(FAN_RPM_SWEEP_DESIGN.md §14).
 *
 * <p>이 도구의 결론("정격보다 낮은 어딘가가 가장 싸다")은 <b>제약 판정과 목적함수가 정확히
 * 맞물릴 때만</b> 성립한다. 어느 한쪽이 어긋나면 결과는 여전히 그럴듯한 숫자로 나오되
 * 가리키는 운전점이 달라지므로, 여기서 고정하는 것은 값이 아니라 그 맞물림이다.
 */
class FanRpmSweepTest {

    private final ObjectMapper om = new ObjectMapper();
    private final EdgeThermalProfileStore store = new EdgeThermalProfileStore();
    private final SweepFanRpmTool sweep = new SweepFanRpmTool(store, new AiLoadProfileService());
    private final SimulateEdgeThrottlingTool single = new SimulateEdgeThrottlingTool(store, new AiLoadProfileService());

    private JsonNode json(String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String argsJson) {
        ToolResult r = sweep.call(json(argsJson));
        assertTrue(r.ready(), () -> "실행이 거부됐다: " + r.errors());
        return (Map<String, Object>) r.result();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("points");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> optimal(Map<String, Object> out) {
        return (Map<String, Object>) out.get("optimal");
    }

    private double num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).doubleValue();
    }

    /**
     * 팬을 끄면 하드 스로틀링(0x4)까지 가는 조건.
     *
     * <p>주변 온도를 50℃로 잡은 이유: 그보다 낮으면 소프트 제한(80℃)에서 클럭이 깎이며
     * 온도가 눌러앉아 <b>0x4가 아예 뜨지 않는다</b>(실제 라즈베리파이도 그렇다). 스로틀링
     * 탈락 판정을 검증하려면 하드 제한에 실제로 닿는 조건이어야 한다.
     */
    private static final String HOT_CASE = """
            {"board":"pi5","cooling":"passive","ambientTempC":50,"workloadMode":"max_throughput",
             "loadSeconds":1800,"objective":"min_energy_per_frame",
             "sweep":{"minPwmPercent":0,"maxPwmPercent":100,"steps":11}}
            """;

    // ── §14.1 스윕 생성 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("0~100%, 11단계는 정확히 11개 지점을 만들고 시작·종료를 포함한다")
    void sweepGeneratesInclusivePoints() {
        List<Map<String, Object>> pts = points(run(HOT_CASE));
        assertEquals(11, pts.size());
        assertEquals(0.0, num(pts.get(0), "commandedPwmPercent"), 1e-9, "팬 정지(대조군)가 빠지면 곡선의 왼쪽 끝을 읽을 수 없다");
        assertEquals(100.0, num(pts.get(10), "commandedPwmPercent"), 1e-9, "정격이 빠지면 '최대로 돌려도 안 되는 조건'을 판정할 수 없다");
        for (int i = 1; i < pts.size(); i++) {
            assertTrue(num(pts.get(i), "commandedPwmPercent") > num(pts.get(i - 1), "commandedPwmPercent"));
        }
    }

    @Test
    @DisplayName("잘못된 범위·단계 수·중복 지점은 실행 전에 거부한다")
    void rejectsInvalidSweepRanges() {
        assertFalse(sweep.call(json("""
                {"board":"pi5","sweep":{"minPwmPercent":80,"maxPwmPercent":20,"steps":5}}""")).ready(),
                "시작이 종료보다 크면 거부");
        assertFalse(sweep.call(json("""
                {"board":"pi5","sweep":{"steps":1}}""")).ready(), "단계 수 2 미만은 거부");
        assertFalse(sweep.call(json("""
                {"board":"pi5","sweep":{"minPwmPercent":50,"maxPwmPercent":50,"steps":3}}""")).ready(),
                "같은 지점을 여러 번 도는 스윕은 곡선에 아무것도 더하지 않는다");
        assertFalse(sweep.call(json("""
                {"board":"pi5","sweep":{"steps":5},"rpmPoints":[0,3000]}""")).ready(),
                "PWM 범위와 회전수 직접 지정을 동시에 주면 어느 쪽이 실행 지점인지 모호하다");
        assertFalse(sweep.call(json("""
                {"board":"pi5","rpmPoints":[0,99999]}""")).ready(), "정격을 넘는 회전수는 낼 수 없다");
    }

    @Test
    @DisplayName("회전수를 직접 지정하면 정격 대비 비율로 PWM에 대응된다")
    void rpmPointsMapToPwmByRatedRatio() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","rpmPoints":[0,3875,7750]}""");
        List<Map<String, Object>> pts = points(out);
        assertEquals(3, pts.size());
        assertEquals(50.0, num(pts.get(1), "commandedPwmPercent"), 0.01, "정격 7750의 절반은 PWM 50%");
        assertEquals(3875.0, num(pts.get(1), "effectiveRpm"), 1.0);
    }

    // ── §14.2 제약 판정 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("스로틀링이 걸린 지점은 탈락하고 사유가 남는다")
    void throttledPointsAreRejectedWithReason() {
        List<Map<String, Object>> pts = points(run(HOT_CASE));
        List<Map<String, Object>> throttled = pts.stream()
                .filter(p -> p.get("tttSec") != null).toList();
        assertFalse(throttled.isEmpty(), "이 조건은 저RPM에서 스로틀링이 걸려야 실험이 성립한다");
        for (Map<String, Object> p : throttled) {
            assertEquals(Boolean.FALSE, p.get("feasible"));
            assertTrue(reasons(p).contains("HARD_THROTTLED"));
        }
    }

    @Test
    @DisplayName("TTT가 없어도 처리량 손실이 한도를 넘으면 탈락한다 — TTT만 보면 안 되는 이유")
    void throughputLossRejectsEvenWithoutThrottling() {
        // 소프트 제한(80℃)에 눌러앉아 클럭이 깎이는 조건 — 0x4는 뜨지 않는다.
        Map<String, Object> out = run("""
                {"board":"pi4","cooling":"passive","ambientTempC":45,"workloadMode":"max_throughput",
                 "loadSeconds":1800,"objective":"min_energy_per_frame",
                 "sweep":{"minPwmPercent":0,"maxPwmPercent":20,"steps":3}}
                """);
        List<Map<String, Object>> softOnly = points(out).stream()
                .filter(p -> p.get("tttSec") == null && num(p, "throughputLossPercent") > 1.0).toList();
        assertFalse(softOnly.isEmpty(), "소프트 제한만으로 처리량을 잃는 지점이 있어야 이 회귀가 의미 있다");
        for (Map<String, Object> p : softOnly) {
            assertEquals(Boolean.FALSE, p.get("feasible"), "TTT가 없다고 정상으로 뽑으면 성능을 포기한 지점이 최적점이 된다");
            assertTrue(reasons(p).contains("THROUGHPUT_LOSS"));
        }
    }

    @Test
    @DisplayName("어떤 회전수로도 제약을 못 지키면 최적점을 억지로 만들지 않는다")
    void noFeasiblePointYieldsNullOptimal() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","ambientTempC":55,"workloadMode":"max_throughput",
                 "loadSeconds":1800,"objective":"min_energy_per_frame",
                 "constraints":{"maxPeakTempC":60},
                 "sweep":{"minPwmPercent":0,"maxPwmPercent":100,"steps":5}}
                """);
        assertEquals("NO_FEASIBLE_POINT", out.get("status"));
        assertNull(out.get("optimal"));
        assertNotNull(out.get("recommendation"), "무엇을 바꾸면 되는지 알려줘야 한다");
        assertTrue(points(out).stream().noneMatch(p -> Boolean.TRUE.equals(p.get("feasible"))));
    }

    // ── §14.3 목적함수 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("목표 FPS 모드에서는 적합 지점 중 총에너지가 가장 작은 지점을 고른다")
    void targetFpsPicksMinTotalEnergy() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","ambientTempC":35,"workloadMode":"target_fps",
                 "targetFps":20,"loadSeconds":1800,
                 "sweep":{"minPwmPercent":0,"maxPwmPercent":100,"steps":11}}
                """);
        assertEquals("min_total_energy", out.get("objective"), "목표 FPS 모드의 기본 목적함수");
        Map<String, Object> opt = optimal(out);
        assertNotNull(opt);

        double best = num(opt, "totalEnergyJ");
        for (Map<String, Object> p : points(out)) {
            if (!Boolean.TRUE.equals(p.get("feasible"))) continue;
            assertTrue(num(p, "totalEnergyJ") >= best - 1e-6,
                    "적합한데 더 싼 지점이 있으면 최적점 선택이 틀린 것이다");
        }
        assertTrue(num(opt, "commandedPwmPercent") < 100.0,
                "팬 전력이 회전수의 3승이므로 최적점은 정격보다 아래여야 한다 — 정격이 답이면 비용이 반영되지 않은 것이다");
    }

    @Test
    @DisplayName("최대 처리량 모드의 기본 목적함수는 프레임당 에너지다 — 덜 일한 지점이 이기면 안 된다")
    void maxThroughputDefaultsToEnergyPerFrame() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","ambientTempC":35,"workloadMode":"max_throughput",
                 "loadSeconds":1800,"sweep":{"minPwmPercent":0,"maxPwmPercent":100,"steps":6}}
                """);
        assertEquals("min_energy_per_frame", out.get("objective"));
        Map<String, Object> opt = optimal(out);
        if (opt == null) return;                    // 제약을 아무도 못 지키는 조건이면 이 검증은 해당 없음
        double best = num(opt, "energyPerFrameJ");
        for (Map<String, Object> p : points(out)) {
            if (!Boolean.TRUE.equals(p.get("feasible"))) continue;
            assertTrue(num(p, "energyPerFrameJ") >= best - 1e-9);
        }
    }

    @Test
    @DisplayName("최대 처리량 모드에서 총에너지로 고르려면 명시적 허용이 필요하다")
    void maxThroughputRequiresExplicitOptInForTotalEnergy() {
        String args = """
                {"board":"pi5","cooling":"passive","workloadMode":"max_throughput",
                 "objective":"min_total_energy","sweep":{"steps":3}}""";
        assertFalse(sweep.call(json(args)).ready(),
                "지점마다 처리한 프레임 수가 다른데 총에너지로 비교하면 덜 일한 지점이 이긴다");
        assertTrue(sweep.call(json(args.replace("\"sweep\"",
                "\"allowTotalEnergyInMaxThroughput\":true,\"sweep\""))).ready());
    }

    @Test
    @DisplayName("동률이면 낮은 PWM을 고르고, 처리량이 부족한 지점은 아무리 싸도 고르지 않는다")
    void tieBreaksToLowerPwmAndSkipsInfeasible() {
        FanSweepPoint cheapButThrottled = point(20, 1550, 100.0, false, "HARD_THROTTLED");
        FanSweepPoint tieHigh = point(80, 6200, 200.0, true);
        FanSweepPoint tieLow = point(40, 3100, 200.0, true);

        FanSweepPoint chosen = FanSweepResult.select(
                List.of(cheapButThrottled, tieHigh, tieLow), FanSweepResult.Objective.MIN_TOTAL_ENERGY);
        assertNotNull(chosen);
        assertEquals(40.0, chosen.commandedPwmPercent(), 1e-9,
                "값이 같으면 더 조용하고 덜 마모되는 낮은 회전수를 고른다");

        assertNull(FanSweepResult.select(List.of(cheapButThrottled), FanSweepResult.Objective.MIN_TOTAL_ENERGY),
                "적합 지점이 하나도 없으면 최적점은 없다");
    }

    // ── §14.4 에너지와 팬 사양 ───────────────────────────────────────────────

    @Test
    @DisplayName("팬 전력은 SoC 온도 적분에 들어가지 않는다 — 스로틀링이 없으면 SoC 에너지는 회전수와 무관하다")
    void fanPowerNeverEntersSocEnergy() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","ambientTempC":20,"workloadMode":"target_fps",
                 "targetFps":5,"loadSeconds":900,"sweep":{"minPwmPercent":0,"maxPwmPercent":100,"steps":3}}
                """);
        List<Map<String, Object>> pts = points(out);
        double soc0 = num(pts.get(0), "socEnergyJ");
        for (Map<String, Object> p : pts) {
            assertEquals(soc0, num(p, "socEnergyJ"), soc0 * 1e-6,
                    "팬을 돌린다고 SoC가 더 먹으면 온도 적분에 팬 전력이 섞인 것이다");
        }
        assertEquals(0.0, num(pts.get(0), "fanEnergyJ"), 1e-9, "팬 정지는 전력 0");
        // 전력은 회전수의 3승 — 절반이면 1/8이라 총에너지 곡선이 오른쪽 끝에서 급격히 솟는다.
        assertTrue(num(pts.get(2), "fanEnergyJ") > num(pts.get(1), "fanEnergyJ") * 4);
        for (Map<String, Object> p : pts) {
            assertEquals(num(p, "socEnergyJ") + num(p, "fanEnergyJ"), num(p, "totalEnergyJ"), 1.0);
        }
    }

    @Test
    @DisplayName("한 운전점의 실측값(TACH·전류)은 스윕에서 거부한다 — 곡선이 평평해지기 때문")
    void singlePointMeasurementsAreRejectedInSweep() {
        ToolResult tach = sweep.call(json("""
                {"board":"pi5","cooling":"passive","fanArray":{"fans":[{},{}],"measuredArrayRpm":4000}}"""));
        assertFalse(tach.ready());
        assertTrue(tach.errors().get(0).message().contains("스윕"));

        assertFalse(sweep.call(json("""
                {"board":"pi5","cooling":"passive","fanArray":{"fans":[{},{}],"measuredCurrentA":0.1}}""")).ready(),
                "실측 전류를 그대로 쓰면 모든 회전수에서 전력이 같아진다");
    }

    @Test
    @DisplayName("팬 사양을 지정하지 않으면 임시 프리셋으로 돌리고 잠정 결과임을 표시한다")
    void defaultsToPreliminaryPresetAndSaysSo() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","sweep":{"steps":3}}""");
        @SuppressWarnings("unchecked")
        Map<String, Object> fanSpec = (Map<String, Object>) out.get("fanSpec");
        assertEquals("PI5_DUAL_40MM_PRELIMINARY", fanSpec.get("presetId"));
        assertEquals(Boolean.FALSE, fanSpec.get("verified"));
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) out.get("notes");
        assertTrue(notes.stream().anyMatch(n -> n.contains("잠정")), "검증 전 사양으로 찾은 최적점은 확정값이 아니다");
        assertTrue(notes.contains("FAN_SPEC_NOT_VERIFIED"));
    }

    // ── §14.5 재현성과 호환성 ────────────────────────────────────────────────

    @Test
    @DisplayName("같은 입력은 같은 최적점을 준다")
    void sameInputSameOptimum() {
        Map<String, Object> a = run(HOT_CASE);
        Map<String, Object> b = run(HOT_CASE);
        assertEquals(a.get("status"), b.get("status"));
        assertEquals(optimal(a), optimal(b));
        assertEquals(points(a), points(b));
    }

    @Test
    @DisplayName("스윕의 한 지점은 같은 조건으로 단일 실행한 결과와 일치한다")
    void sweepPointMatchesSingleRun() {
        Map<String, Object> swept = run("""
                {"board":"pi5","cooling":"passive","ambientTempC":35,"workloadMode":"target_fps",
                 "targetFps":15,"loadSeconds":1200,"sweep":{"minPwmPercent":50,"maxPwmPercent":100,"steps":2}}
                """);
        Map<String, Object> at50 = points(swept).get(0);

        ToolResult r = single.call(json("""
                {"board":"pi5","cooling":"passive","ambientTempC":35,"workloadMode":"target_fps",
                 "targetFps":15,"loadSeconds":1200,"recoveryPolicy":"none","applyRecoveryOnThrottle":false,
                 "fanArray":{"presetId":"PI5_DUAL_40MM_PRELIMINARY","commandedPwmPercent":50},
                 "includeSeries":false}
                """));
        assertTrue(r.ready(), () -> "단일 실행이 거부됐다: " + r.errors());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.result();
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) out.get("metrics");

        assertEquals(num(metrics, "peakTempC"), num(at50, "peakTempC"), 1e-9,
                "같은 조건을 두 도구로 물었는데 답이 다르면 계산 경로가 갈라진 것이다");
        assertEquals(num(metrics, "totalEnergyJ"), num(at50, "totalEnergyJ"), 1e-9);
        assertEquals(num(metrics, "throughputLossPercent"), num(at50, "throughputLossPercent"), 1e-9);
    }

    @Test
    @DisplayName("기존 단일 팬 입력(fanRpm 계열)도 팬 1개 배열로 스윕된다")
    void legacySingleFanInputStillSweeps() {
        Map<String, Object> out = run("""
                {"board":"pi5","cooling":"passive","fanRatedRpm":5000,"fanRatedPowerW":0.5,
                 "sweep":{"minPwmPercent":0,"maxPwmPercent":100,"steps":3}}
                """);
        @SuppressWarnings("unchecked")
        Map<String, Object> fanSpec = (Map<String, Object>) out.get("fanSpec");
        assertEquals(1, ((Number) fanSpec.get("fanCount")).intValue());
        assertEquals(5000.0, ((Number) fanSpec.get("ratedRpm")).doubleValue(), 1e-9);
        assertEquals(5000.0, num(points(out).get(2), "effectiveRpm"), 1.0, "PWM 100%는 정격 회전수");
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> reasons(Map<String, Object> p) {
        return (List<String>) p.get("rejectionReasons");
    }

    /** 선택 로직만 떼어 검증하기 위한 합성 지점 — 열 계산과 무관하게 규칙만 본다. */
    private FanSweepPoint point(double pwm, double rpm, double totalEnergyJ, boolean feasible,
                                String... reasons) {
        return new FanSweepPoint(pwm, rpm, "PWM_ESTIMATE", 3.0, 0.1, 50.0,
                totalEnergyJ - 50.0, totalEnergyJ, 70.0, null, null, null, 0.0, 15.0, 0.0,
                1000.0, totalEnergyJ / 1000.0, feasible, new ArrayList<>(List.of(reasons)));
    }
}
