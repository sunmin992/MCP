package com.wastesim.edge;

import java.util.ArrayList;
import java.util.List;

/**
 * 방열판 <b>형상·배치·기류</b>에서 전체 열저항 R_ja(K/W)를 계산하는 준-해석 모델.
 * 이 R_ja를 {@link ThermalParams#withRJa(double)}로 갈아끼워 {@link ThermalSimulator}에
 * 넣으면 "이 배치로 붙이면 몇 초 만에 스로틀링이 걸리는가"까지 이어진다 —
 * 즉 "방열판을 어떻게 배치해야 효율이 좋은가"라는 질문에 온도·TTT 숫자로 답한다.
 *
 * <h3>열저항 회로</h3>
 * <pre>
 *   SoC 다이 ──R_jc── 패키지 표면 ─┬─ R_tim + R_misalign + R_spread + R_base + R_conv ─┬─ 공기
 *                                  └────────────── R_rest (PCB·패키지 자연대류) ────────┘
 *
 *   R_ja = R_jc + (R_top ∥ R_rest)
 * </pre>
 * 방열판 경로가 아무리 좋아져도 R_jc는 남고, 방열판이 없어도 R_rest 경로는 남는다 —
 * 그래서 "방열판을 두 배 좋게 해도 온도가 절반이 되지 않는" 수확체감이 자연히 나온다.
 *
 * <h3>각 항</h3>
 * <ul>
 *   <li><b>R_tim</b> = t/(k·A_contact) — 접촉면적은 방열판 베이스와 SoC 패키지 사각형의
 *       <i>겹친 넓이</i>다. 오프셋을 주면 이 값이 줄어 바로 악화된다.</li>
 *   <li><b>R_misalign</b> = MISALIGN_K·(1/coverage − 1) — 방열판이 덮지 못한 다이 면적의
 *       열이 패키지 안에서 옆으로 퍼져 나가야 하는 추가 저항. 경험 상수라 실측 캘리브레이션 대상.</li>
 *   <li><b>R_spread</b> — 작은 다이 면적에서 넓은 베이스로 퍼질 때의 확산 저항
 *       (Lee/Song 근사식 {@code (1−√(As/Ap))^1.5 / (k·√(π·As))}).</li>
 *   <li><b>R_base</b> = t_base/(k·A_base) — 베이스 두께 방향 전도.</li>
 *   <li><b>R_conv</b> = 1/(h·A_eff) — 핀 효율 η=tanh(mL)/(mL) 을 반영한 유효 방열 면적 기준.</li>
 * </ul>
 *
 * <p><b>모델의 한계(학생에게 반드시 알릴 것)</b>: 대류 열전달계수 h와 오정렬 상수는
 * 경험식이다. 절대값은 ±30% 수준의 오차를 가질 수 있으므로, 이 도구의 신뢰 구간은
 * "후보 A와 B 중 어느 쪽이 몇 ℃ 유리한가"라는 <i>상대 비교</i>다. 절대 예측이
 * 필요하면 실측 한 점으로 {@code calibrate_edge_thermal_model}을 돌려 보정한 뒤 쓴다.
 */
public class HeatsinkThermalModel {

    /** 자연대류 기준 열전달계수(W/m²·K). */
    static final double H_NATURAL = 11.0;
    /** 강제대류 증가분 계수 — h = H_NATURAL + H_FORCED_COEF·v^0.8. */
    static final double H_FORCED_COEF = 34.0;
    /** 오정렬 페널티 상수(K/W) — 실측으로 반드시 캘리브레이션할 경험값. */
    static final double MISALIGN_K = 5.0;
    /** 편심(다이가 베이스 중앙에서 벗어남) 확산저항 증가 계수. */
    static final double ECCENTRICITY_K = 1.0;
    /** 팬 RPM → 풍속(m/s) 환산 계수(2500rpm ≈ 2m/s 가정). */
    static final double RPM_TO_MPS = 0.0008;
    /** 방열판이 덮지 못한 부수 발열칩의 대략적 열저항(K/W). */
    static final double UNCOVERED_HOTSPOT_R = 30.0;
    /**
     * 모델 유효 하한 덮임률(1%). 이 아래는 {@link #MISALIGN_K} 경험식과 Lee/Song 확산저항
     * 근사가 모두 캘리브레이션 범위 밖이라 값을 그대로 믿을 수 없다(E-08).
     *
     * <p><b>결정: 거부하지 않고 하한을 적용해 계산하되, 하한을 적용했다는 사실을 결과에
     * 남긴다.</b> 근거는 셋이다. (1) 방열판을 옆으로 밀어 붙인 배치는 <i>실제로 만들 수 있는</i>
     * 형상이다 — "핀 두께 합이 베이스 폭보다 크다" 같은 존재 불가 형상과 달리 거부할 물리적
     * 근거가 없다. (2) 이 도구의 질문 자체가 "오프셋을 주면 얼마나 나빠지는가"인데, 곡선의
     * 끝을 거부하면 그 질문에 끝까지 답할 수 없다. (3) 하한을 적용한 결과는 "방열판이 없는
     * 것과 다름없다"는 답으로 수렴하고, 그것이 이 구간에서 물리적으로 맞는 답이다.
     *
     * <p>대신 하한은 <b>세 저항 항 전부에</b> 같은 값으로 적용한다. 예전에는 R_misalign만
     * 하한 1%를 쓰고 R_tim·R_spread는 실제 면적을 써서, 같은 "접촉 면적" 개념이 항마다 다른
     * 값을 갖고 0.1% 구간에서 R_misalign은 멈춘 채 R_tim만 10배로 뛰었다.
     *
     * <p><b>겹침이 정확히 0인 배치에는 적용하지 않는다.</b> "아주 조금 닿았다"와 "아예 안
     * 닿았다"는 다른 상태다 — 접촉이 없으면 방열판 경로 자체가 없어 R_ja가 무냉각과 같아야
     * 하는데, 하한을 먹이면 없는 접촉면을 만들어 내 무냉각보다 좋은 값이 나온다.
     */
    static final double MIN_EFFECTIVE_COVERAGE = 0.01;

    /**
     * 계산 결과와 그 분해 — 학생이 "무엇 때문에 나빠졌는지" 볼 수 있게 항별로 전부 노출한다.
     *
     * <p>덮임률은 <b>보고용과 계산용을 나눠</b> 싣는다(E-08). {@code reportedCoverage}는 실제로
     * 겹친 넓이 그대로라 "얼마나 어긋났는가"를 답하고, {@code effectiveCoverage}는 모델 유효
     * 하한({@link #MIN_EFFECTIVE_COVERAGE})을 적용한 값이라 R_tim·R_misalign·R_spread가 전부
     * 이 하나를 쓴다. 두 값이 다르면 {@code coverageFloorApplied}가 참이고, 그때 R_ja는
     * "이보다 나쁘지는 않다"는 하한선으로 읽어야 한다.
     *
     * @param coverage <b>@deprecated</b> — {@code effectiveCoverage}와 같은 값이다(기존 필드가
     *                 하한 적용 후 값을 담고 있었으므로 그대로 뒀다). 새 코드는 두 필드를 나눠 쓴다
     */
    public record Result(
            String name,
            double rJaKPerW,
            double rTopKPerW,
            Breakdown breakdown,
            double coverage,
            double reportedCoverage,
            double effectiveCoverage,
            double contactAreaMm2,
            double effectiveContactAreaMm2,
            boolean coverageFloorApplied,
            double finSurfaceAreaCm2,
            double finEfficiency,
            double hEffWm2K,
            double airSpeedMps,
            double finGapMm,
            List<HotspotReport> hotspots,
            List<String> warnings) {}

    public record Breakdown(double rJc, double rTim, double rMisalign, double rSpread,
                            double rBase, double rConv, double rRestParallelPath) {}

    public record HotspotReport(String name, boolean coveredByHeatsink, double estimatedTempC) {}

    /**
     * 배치 하나를 평가해 R_ja와 분해 내역을 낸다.
     *
     * @param board   보드(패키지 크기·R_jc·R_rest 제공)
     * @param layout  방열판 형상·배치·기류
     * @param ambientC 주변 온도(부수 발열점 추정 온도 계산에만 사용)
     * @param socTempC SoC 예상 온도(부수 발열점 추정용). 아직 모르면 ambient를 넣어도 된다
     */
    public Result evaluate(BoardType board, HeatsinkLayout layout, double ambientC, double socTempC) {
        List<String> warnings = new ArrayList<>();
        var hs = layout.heatsink();
        var pl = layout.placement();
        var air = layout.airflow();

        // ── 1. 접촉면 겹침(배치의 핵심) ──────────────────────────────────
        double dieSide = board.packageSideMm();
        double overlapMm2 = rectOverlapMm2(dieSide, dieSide, hs.baseLengthMm(), hs.baseWidthMm(),
                pl.offsetXMm(), pl.offsetYMm());
        double dieAreaMm2 = dieSide * dieSide;
        // 보고용 덮임률은 실제 겹침 그대로다 — "얼마나 어긋났는가"를 답한다.
        double reportedCoverage = overlapMm2 / dieAreaMm2;
        // 계산용 덮임률에는 모델 유효 하한을 적용한다 — 아래 세 저항 항이 <b>전부</b> 이 하나를
        // 쓴다(E-08). 하한 위 구간에서는 두 값이 같으므로 결과가 달라지지 않는다.
        //
        // 겹침이 <b>정확히 0</b>인 배치는 예외다. "아주 조금 닿았다"와 "아예 안 닿았다"는 다른
        // 상태이고, 접촉이 없으면 방열판 경로 자체가 없어 R_ja는 무냉각과 같아야 한다. 여기에
        // 하한을 먹이면 없는 접촉면을 만들어 내 무냉각보다 좋은 값이 나온다.
        boolean noContact = overlapMm2 <= 0.0;
        double effectiveCoverage = noContact
                ? 0.0 : Math.max(reportedCoverage, MIN_EFFECTIVE_COVERAGE);
        boolean coverageFloorApplied = effectiveCoverage > reportedCoverage;
        if (noContact) {
            warnings.add("방열판이 SoC 패키지와 전혀 겹치지 않는다 — 접촉 경로가 없으므로 무냉각(BARE)과 같다.");
        } else if (coverageFloorApplied) {
            warnings.add(String.format(
                    "방열판이 SoC 패키지를 사실상 덮지 못한다(실제 겹침 %.2f%%) — 이 배치는 방열판이 없는 것과 "
                    + "다름없다. 덮임률 %.0f%% 미만은 오정렬·확산저항 경험식의 유효 범위 밖이라 %.0f%%를 하한으로 "
                    + "적용해 계산했다. 아래 R_ja는 '적어도 이만큼은 나쁘다'는 하한선으로 읽고, 절대값이 필요하면 "
                    + "무냉각(BARE) 프리셋과 비교할 것.",
                    reportedCoverage * 100, MIN_EFFECTIVE_COVERAGE * 100, MIN_EFFECTIVE_COVERAGE * 100));
        } else if (reportedCoverage < 0.9) {
            warnings.add(String.format("SoC 덮임률 %.0f%% — 오프셋(%.1f, %.1f)mm 때문에 접촉면이 줄었다. 오프셋을 0에 가깝게 하면 개선된다.",
                    reportedCoverage * 100, pl.offsetXMm(), pl.offsetYMm()));
        }
        // 접촉 면적도 같은 덮임률에서 파생시킨다 — 예전에는 여기만 실제 겹침을 써서, 하한이
        // 걸린 구간에서 R_misalign은 멈춘 채 R_tim·R_spread만 계속 커졌다. 접촉이 0이면
        // 0으로 나누지 않도록 남겨 둔 절대 하한(1e-3mm²)이 R_tim을 사실상 무한대로 만들어
        // 방열판 경로를 자연히 끊는다.
        double effectiveContactMm2 = effectiveCoverage * dieAreaMm2;
        double contactM2 = Math.max(effectiveContactMm2, 1e-3) * 1e-6;

        // ── 2. TIM · 오정렬 ─────────────────────────────────────────────
        double rTim = (layout.tim().thicknessMm() / 1000.0)
                / (layout.tim().effectiveConductivity() * contactM2);
        double rMisalign = MISALIGN_K
                * (1.0 / Math.max(effectiveCoverage, MIN_EFFECTIVE_COVERAGE) - 1.0);

        // ── 3. 확산 · 베이스 전도 ────────────────────────────────────────
        double k = hs.material().conductivity();
        double baseAreaM2 = hs.baseLengthMm() * hs.baseWidthMm() * 1e-6;
        double as = Math.min(contactM2, baseAreaM2);
        double rSpread = Math.pow(1.0 - Math.sqrt(as / baseAreaM2), 1.5)
                / (k * Math.sqrt(Math.PI * as));
        // 편심 보정: 다이가 베이스 정중앙에서 벗어나면 열이 한쪽으로만 퍼질 수 있어
        // 확산 저항이 커진다. 덮임률이 100%로 유지되는 작은 오프셋(예: 40mm 방열판에
        // 5mm 이동)에서도 성능이 아주 조금은 나빠져야 하는데, 겹침 면적만 보면 그 차이가
        // 0으로 나온다 — 그 구간을 메우는 항이다.
        double halfMin = Math.min(hs.baseLengthMm(), hs.baseWidthMm()) / 2.0;
        double ecc = halfMin > 0
                ? Math.min(1.0, Math.hypot(pl.offsetXMm(), pl.offsetYMm()) / halfMin) : 0.0;
        rSpread *= (1.0 + ECCENTRICITY_K * ecc * ecc);
        double rBase = (hs.baseThicknessMm() / 1000.0) / (k * baseAreaM2);

        // ── 4. 대류(기류·핀 방향·핀 간격) ────────────────────────────────
        double v = effectiveAirSpeed(air, warnings);
        double h = H_NATURAL + H_FORCED_COEF * Math.pow(Math.max(v, 0.0), 0.8);

        double alignFactor = pl.finAlignment() == HeatsinkLayout.FinAlignment.ALIGNED ? 1.0
                : (air.type() == HeatsinkLayout.AirflowType.FORCED ? 0.60 : 0.72);
        if (pl.finAlignment() == HeatsinkLayout.FinAlignment.CROSS) {
            warnings.add(air.type() == HeatsinkLayout.AirflowType.FORCED
                    ? "핀이 팬 바람을 가로막는 방향이다 — 90° 돌려 채널을 바람과 나란히 두면 유효 대류가 약 40% 개선된다."
                    : "자연대류에서 핀 채널이 수평이다 — 채널이 위로 열려야 더운 공기가 빠져나간다(보드를 세우거나 방열판을 돌릴 것).");
        }

        double finGapMm = hs.finCount() > 1
                ? (hs.baseWidthMm() - hs.finCount() * hs.finThicknessMm()) / (hs.finCount() - 1)
                : hs.baseWidthMm();
        double gapFactor = 1.0;
        if (hs.finCount() > 1) {
            if (finGapMm <= 0) {
                warnings.add("핀 개수×두께가 베이스 폭보다 크다 — 물리적으로 불가능한 형상이다.");
                finGapMm = 0.1;
            }
            // 핀 간격이 좁아지면 경계층이 겹쳐 채널 안 공기가 사실상 정체한다 — 면적이
            // 늘어난 만큼 성능이 따라오지 않고, 심하면 오히려 나빠진다. 자연대류가 훨씬 민감하다.
            double gapRef = air.type() == HeatsinkLayout.AirflowType.NATURAL ? 2.5 : 1.2;
            double floor = air.type() == HeatsinkLayout.AirflowType.NATURAL ? 0.15 : 0.30;
            gapFactor = Math.max(floor, Math.min(1.0, Math.pow(finGapMm / gapRef, 1.5)));
            if (gapFactor < 0.9) {
                warnings.add(String.format("핀 간격 %.1fmm가 좁아 경계층이 겹친다 — 핀 수를 줄이거나 팬을 붙여야 면적 증가분이 실제 성능으로 이어진다.", finGapMm));
            }
        }
        double hEff = h * alignFactor * gapFactor;

        double finLenM = hs.baseLengthMm() / 1000.0;
        double finHM = hs.finHeightMm() / 1000.0;
        double finTM = hs.finThicknessMm() / 1000.0;
        double finAreaM2 = hs.finCount() * (2.0 * finLenM * finHM + finLenM * finTM);
        double exposedBaseM2 = Math.max(0.0, baseAreaM2 - hs.finCount() * finLenM * finTM);
        double eta = 1.0;
        if (hs.finCount() > 0 && finHM > 0 && finTM > 0) {
            double m = Math.sqrt(2.0 * hEff / (k * finTM));
            double lc = finHM + finTM / 2.0;
            eta = Math.tanh(m * lc) / (m * lc);
        }
        double aEff = eta * finAreaM2 + exposedBaseM2;
        double rConv = 1.0 / (hEff * Math.max(aEff, 1e-6));

        // ── 5. 합성 ────────────────────────────────────────────────────
        double rTop = rTim + rMisalign + rSpread + rBase + rConv;
        double rRest = board.rRestKPerW();
        double rParallel = (rTop * rRest) / (rTop + rRest);
        double rJa = board.rJcKPerW() + rParallel;

        if (rJa > board.rJaKPerW(CoolingPreset.BARE)) {
            warnings.add("계산된 열저항이 무냉각 상태보다 나쁘다 — 배치나 형상 입력을 다시 확인할 것.");
        }

        // ── 6. 부수 발열점 ──────────────────────────────────────────────
        List<HotspotReport> reports = new ArrayList<>();
        for (var spot : layout.hotspots()) {
            boolean covered = Math.abs(spot.xMm() - pl.offsetXMm()) <= hs.baseLengthMm() / 2.0
                    && Math.abs(spot.yMm() - pl.offsetYMm()) <= hs.baseWidthMm() / 2.0;
            double temp = covered
                    ? ambientC + spot.powerW() * (rJa + 2.0)
                    : ambientC + spot.powerW() * UNCOVERED_HOTSPOT_R;
            reports.add(new HotspotReport(spot.name(), covered, ThermalSimulator.round(temp, 1)));
            if (!covered && spot.powerW() >= 0.8) {
                warnings.add(spot.name() + "이(가) 방열판 밑에 들어오지 않는다 — 열화상에서 이 지점이 국소 hot spot으로 보일 것이다(추정 "
                        + String.format("%.0f", temp) + "℃).");
            }
        }

        return new Result(
                layout.name(),
                ThermalSimulator.round(rJa, 3),
                ThermalSimulator.round(rTop, 3),
                new Breakdown(board.rJcKPerW(), ThermalSimulator.round(rTim, 3),
                        ThermalSimulator.round(rMisalign, 3), ThermalSimulator.round(rSpread, 3),
                        ThermalSimulator.round(rBase, 4), ThermalSimulator.round(rConv, 3),
                        ThermalSimulator.round(rRest, 3)),
                // coverage(구 이름)는 하한 적용 후 값을 담고 있었으므로 effectiveCoverage와
                // 같은 값을 그대로 둔다 — 기존 클라이언트의 숫자가 바뀌지 않는다.
                ThermalSimulator.round(effectiveCoverage, 3),
                ThermalSimulator.round(reportedCoverage, 4),
                ThermalSimulator.round(effectiveCoverage, 3),
                ThermalSimulator.round(overlapMm2, 1),
                ThermalSimulator.round(effectiveContactMm2, 1),
                coverageFloorApplied,
                ThermalSimulator.round((finAreaM2 + exposedBaseM2) * 1e4, 1),
                ThermalSimulator.round(eta, 3),
                ThermalSimulator.round(hEff, 1),
                ThermalSimulator.round(v, 2),
                ThermalSimulator.round(finGapMm, 2),
                reports, warnings);
    }

    /** 팬 거리에 따른 풍속 감쇠까지 반영한 유효 풍속(m/s). */
    private double effectiveAirSpeed(HeatsinkLayout.Airflow air, List<String> warnings) {
        if (air.type() == HeatsinkLayout.AirflowType.NATURAL) return 0.0;
        double v = air.airSpeedMps() > 0 ? air.airSpeedMps() : air.fanRpm() * RPM_TO_MPS;
        if (v <= 0) {
            warnings.add("강제대류인데 풍속(airSpeedMps)도 팬 회전수(fanRpm)도 없다 — 자연대류로 계산했다.");
            return 0.0;
        }
        double d = air.fanDistanceMm();
        if (d > 10) {
            double atten = 1.0 / (1.0 + (d - 10) / 60.0);
            if (atten < 0.75) {
                warnings.add(String.format("팬이 방열판에서 %.0fmm 떨어져 유효 풍속이 %.0f%%로 줄었다 — 가까이 붙이는 것이 RPM을 올리는 것보다 싸게 먹힌다.",
                        d, atten * 100));
            }
            v *= atten;
        }
        return v;
    }

    /** 원점 중심 사각형(w1×h1)과 (dx,dy) 중심 사각형(w2×h2)의 겹친 넓이(mm²). */
    static double rectOverlapMm2(double w1, double h1, double w2, double h2, double dx, double dy) {
        double ox = Math.min(w1 / 2, dx + w2 / 2) - Math.max(-w1 / 2, dx - w2 / 2);
        double oy = Math.min(h1 / 2, dy + h2 / 2) - Math.max(-h1 / 2, dy - h2 / 2);
        return Math.max(0, ox) * Math.max(0, oy);
    }
}
