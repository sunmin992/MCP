package com.wastesim.edge;

import com.wastesim.util.Round;

import java.util.ArrayList;
import java.util.List;

/**
 * 라즈베리파이에서 실제로 측정한 시계열(온도·클럭·FPS·throttled 비트·전력)에서
 * 열 모델 파라미터를 역추정한다 — 이 실험의 "실측 → 모델" 연결 고리다.
 *
 * <h3>추정 원리</h3>
 * 부하 구간에서 온도는 {@code T(t) = T∞ − (T∞ − T0)·e^(−t/τ_h)} 로 오른다. 양변을 정리하면
 * {@code ln(T∞ − T) = ln(T∞ − T0) − t/τ_h} 로 <b>직선</b>이 된다. 문제는 T∞(도달했을 정상상태
 * 온도)를 모른다는 것 — 그래서 T∞를 0.2℃ 격자로 훑으며 매번 직선회귀를 해보고,
 * 실제 온도 영역에서의 RMSE가 가장 작은 T∞를 고른다(1차원 그리드 서치 + 선형회귀).
 * 외부 수치 라이브러리 없이도 안정적이고, 학생이 손으로 검산할 수 있는 방법이다.
 *
 * <p>그 다음은 정의식 그대로다.
 * <pre>
 *   R_ja = (T∞ − T_ambient) / P_load        C_th = τ_h / R_ja
 * </pre>
 * 냉각 구간이 있으면 같은 방법으로 τ_c를 따로 구한다(가열과 냉각의 시정수가 다르면
 * 그 자체가 결과다 — 예: 팬이 켜진 채 식으면 τ_c가 작아진다).
 *
 * <p>throttled 비트가 함께 기록돼 있으면 TTT/TED/TRT를 <b>실측값</b>으로도 뽑아낸다.
 * 시뮬레이션 예측값과 이 실측값을 나란히 놓는 것이 R&E의 핵심 검증 표가 된다.
 */
public class ThermalCalibrator {

    /** 측정 한 점. 온도 외 필드는 없으면 null. {@code throttled == null}은 <b>미측정</b>이다. */
    public record Sample(double tSec, double socTempC, Double powerW, Double clockMhz,
                         Double fps, Boolean throttled) {

        /**
         * 스로틀 비트를 3상태로 읽는다.
         *
         * <p>예전에는 {@code Boolean.TRUE.equals(throttled)} 한 줄로 읽어서 결측값이 곧
         * "해제됨"이 됐다. 그러면 하나의 에피소드가 둘로 쪼개져 TED가 짧아지고, 회복 구간의
         * 결측이 스로틀링 해제로 오인되어 TRT가 실제보다 빨리 잡힌다 — <b>측정하지 않은 것을
         * 측정한 것으로 바꾸는</b> 전형적인 오류다.
         */
        ThrottleState throttleState() {
            if (throttled == null) return ThrottleState.UNKNOWN;
            return throttled ? ThrottleState.THROTTLED : ThrottleState.CLEAR;
        }
    }

    /** 스로틀 비트의 3상태 — 관측된 ON / 관측된 OFF / 판단 불가. */
    enum ThrottleState { THROTTLED, CLEAR, UNKNOWN }

    /**
     * 결측 때문에 지표에서 제외된 정보 — 결과의 신뢰도를 사용자가 직접 판단할 수 있게 한다.
     *
     * @param missingSamples     throttled가 결측인 샘플 수
     * @param missingRatio       전체 샘플 대비 결측 비율(0~1)
     * @param longestGapSec      연속 결측 구간의 최대 길이(초)
     * @param longestGapStartSec 그 구간의 시작 시각(초). 결측이 없으면 null
     * @param longestGapEndSec   그 구간의 종료 시각(초). 결측이 없으면 null
     * @param incompleteEpisodes 결측이 섞여 TED 중앙값에서 제외한 에피소드 수
     */
    public record ThrottleDataQuality(int missingSamples, double missingRatio,
                                      double longestGapSec, Double longestGapStartSec,
                                      Double longestGapEndSec, int incompleteEpisodes) {}

    /** 지수 적합 결과. */
    public record Fit(double asymptoteC, double tauSec, double startTempC,
                      double rSquared, double rmseC, int pointsUsed) {}

    /**
     * @param heating          가열 구간 적합(항상 존재)
     * @param cooling          냉각 구간 적합(loadEndSec 이후 데이터가 있을 때만)
     * @param rJaKPerW         역추정 열저항
     * @param cThJPerK         역추정 열용량
     * @param loadPowerW       사용한 부하 전력(실측 평균 또는 보드 기본값)
     * @param powerSource      전력 출처("measured" | "board-default")
     * @param measuredTttSec   실측 TTT
     * @param measuredTeds     실측 TED 목록
     * @param measuredTrtStateSec 실측 TRT_state
     * @param thermalOverride  이 결과를 그대로 시뮬레이션 도구 인자로 넣을 수 있게 만든 객체
     * @param throttleQuality  throttled 열의 결측 현황. throttled 열이 아예 없으면 null
     * @param quality          적합 품질 요약 문장
     * @param warnings         해석 주의사항
     */
    public record Calibration(
            Fit heating, Fit cooling,
            double rJaKPerW, double cThJPerK,
            double loadPowerW, String powerSource,
            Double measuredTttSec, List<Double> measuredTeds, Double measuredTrtStateSec,
            Double measuredPeakTempC, Double measuredFpsDropPercent,
            ThermalOverride thermalOverride,
            ThrottleDataQuality throttleQuality,
            String quality, List<String> warnings) {}

    /** 시뮬레이션 도구의 {@code thermalOverride} 인자와 필드명이 1:1로 같다 — 복사해 붙이면 된다. */
    public record ThermalOverride(double rJaKPerW, double cThJPerK, double ambientC,
                                  double idlePowerW, double dynamicPowerW, double startTempC,
                                  Double tauCoolingSec) {}

    /**
     * @param samples    시간 오름차순 측정 시계열
     * @param ambientC   주변 온도(℃)
     * @param board      보드(전력 기본값·클럭 정보)
     * @param loadEndSec 부하 종료 시각(초). null이면 최고온도 시점을 경계로 자동 판정
     */
    public Calibration calibrate(List<Sample> samples, double ambientC, BoardType board, Double loadEndSec) {
        List<String> warnings = new ArrayList<>();

        double peak = samples.stream().mapToDouble(Sample::socTempC).max().orElse(0);
        double peakAt = samples.stream().filter(s -> s.socTempC() >= peak - 1e-9)
                .mapToDouble(Sample::tSec).min().orElse(0);
        double boundary = loadEndSec != null ? loadEndSec : peakAt;
        if (loadEndSec == null) {
            warnings.add(String.format("loadEndSeconds를 주지 않아 최고온도 시점(%.0f초)을 가열/냉각 경계로 삼았다 — 정확한 실험 기록이 있으면 넣는 편이 좋다.", boundary));
        }

        List<Sample> heat = samples.stream().filter(s -> s.tSec() <= boundary).toList();
        List<Sample> cool = samples.stream().filter(s -> s.tSec() > boundary).toList();

        if (heat.size() < 5) {
            throw new IllegalArgumentException("가열 구간 샘플이 5개 미만이라 시정수를 추정할 수 없다.");
        }

        Fit heating = fitRising(heat);
        Fit cooling = cool.size() >= 5 ? fitFalling(cool, ambientC) : null;
        if (cooling == null && !cool.isEmpty()) {
            warnings.add("냉각 구간 샘플이 5개 미만이라 τ_c를 추정하지 못했다 — 회복 실험은 부하 종료 후에도 계속 로깅해야 한다.");
        }

        // 부하 전력: 실측 평균이 있으면 그걸, 없으면 보드 기본값
        double measuredPower = heat.stream().filter(s -> s.powerW() != null)
                .mapToDouble(Sample::powerW).average().orElse(Double.NaN);
        boolean hasPower = !Double.isNaN(measuredPower);
        double loadPower = hasPower ? measuredPower : board.fullLoadPowerW();
        if (!hasPower) {
            warnings.add(String.format("소비전력 측정값이 없어 보드 기본값 %.1fW로 R_ja를 계산했다 — 전력계를 붙이면 R_ja 추정 정확도가 크게 올라간다.",
                    loadPower));
        }

        double rJa = (heating.asymptoteC() - ambientC) / loadPower;
        double cTh = heating.tauSec() / rJa;
        if (rJa <= 0) {
            throw new IllegalArgumentException("추정 R_ja가 0 이하다 — ambientTempC가 측정 온도보다 높지 않은지 확인할 것.");
        }

        // ── throttled 비트에서 실측 지표 추출 ────────────────────────────
        boolean hasThrottleColumn = samples.stream().anyMatch(s -> s.throttled() != null);
        ThrottleMetrics tm = hasThrottleColumn
                ? extractThrottleMetrics(samples, boundary, warnings)
                : null;
        if (!hasThrottleColumn) {
            warnings.add("throttled 열이 없어 실측 TTT/TED/TRT를 뽑지 못했다 — vcgencmd get_throttled를 함께 로깅할 것.");
        }
        Double ttt = tm == null ? null : tm.ttt;
        Double trtState = tm == null ? null : tm.trtState;
        List<Double> teds = tm == null ? new ArrayList<>() : tm.teds;

        Double fpsDrop = null;
        List<Sample> withFps = samples.stream().filter(s -> s.fps() != null && s.tSec() <= boundary).toList();
        if (withFps.size() >= 10) {
            int n = withFps.size();
            double early = withFps.subList(0, Math.max(1, n / 10)).stream().mapToDouble(Sample::fps).average().orElse(0);
            double late = withFps.subList(n - Math.max(1, n / 10), n).stream().mapToDouble(Sample::fps).average().orElse(0);
            if (early > 1e-9) fpsDrop = round1((early - late) / early * 100.0);
        }

        String quality;
        if (heating.rSquared() >= 0.98 && heating.rmseC() <= 1.0) {
            quality = "양호 — 1차 RC 모델이 실측 곡선을 잘 설명한다(R²=" + round3(heating.rSquared()) + ").";
        } else if (heating.rSquared() >= 0.9) {
            quality = "보통 — R²=" + round3(heating.rSquared()) + ". 부하가 중간에 변했거나 스로틀링으로 곡선이 꺾였을 수 있다(스로틀링 이후 구간을 빼고 다시 적합해 볼 것).";
        } else {
            quality = "불량 — R²=" + round3(heating.rSquared()) + ". 단일 시정수 모델로 설명되지 않는다. 주변 온도 변동·부하 불안정·샘플링 누락을 의심할 것.";
            warnings.add("적합 품질이 낮아 이 파라미터로 예측하면 오차가 크다.");
        }

        ThermalOverride override = new ThermalOverride(
                round3(rJa), round3(cTh), ambientC,
                board.idlePowerW(), round3(Math.max(0.1, loadPower - board.idlePowerW())),
                round1(heating.startTempC()),
                cooling == null ? null : round1(cooling.tauSec()));

        return new Calibration(heating, cooling, round3(rJa), round3(cTh),
                round3(loadPower), hasPower ? "measured" : "board-default",
                ttt, teds, trtState, round1(peak), fpsDrop, override,
                tm == null ? null : tm.quality, quality, warnings);
    }

    /** 스로틀 비트 해석 결과 묶음(내부 전용). */
    private static final class ThrottleMetrics {
        Double ttt, trtState;
        List<Double> teds = new ArrayList<>();
        ThrottleDataQuality quality;
    }

    /**
     * 결측을 "해제됨"으로 바꾸지 않고 TTT·TED·TRT를 뽑는다.
     *
     * <p>규칙은 하나로 요약된다 — <b>공식 지표는 명시적으로 관측된 값으로만 만든다.</b>
     * 화면에 이어 그리기 위해 직전 상태로 보간할 수는 있어도, 그 보간값이 지표가 되면
     * 결측이 많은 실험도 정상 데이터처럼 보이게 된다.
     *
     * <ul>
     *   <li>TTT — 최초의 명시적 THROTTLED에서 잡되, <b>그 직전이 결측이면</b> 확정하지 않는다.
     *       발생 시점이 결측 구간 안 어딘가일 수 있어 "언제 걸렸나"에 답할 수 없기 때문이다.</li>
     *   <li>TED — 시작(THROTTLED)과 끝(CLEAR)이 모두 명시적이고 중간에 결측이 없는
     *       에피소드만 완결로 본다. 결측이 섞인 에피소드는 중앙값 계산에서 뺀다.</li>
     *   <li>TRT_state — 회복 구간에서 명시적 CLEAR로 끝난 경우만 인정한다. 결측이 섞이면
     *       해제 시점을 알 수 없으므로 확정하지 않는다.</li>
     * </ul>
     */
    private ThrottleMetrics extractThrottleMetrics(List<Sample> samples, double boundary,
                                                   List<String> warnings) {
        ThrottleMetrics out = new ThrottleMetrics();

        int missing = 0, incomplete = 0;
        double longestGap = 0.0;
        Double gapStart = null, gapEnd = null, runStart = null, lastKnownT = null;

        Double epStart = null;              // 진행 중인 에피소드 시작 시각
        boolean epHasUnknown = false;       // 그 에피소드 안에 결측이 섞였는가
        boolean unknownBeforeFirst = false; // 최초 THROTTLED 직전이 결측이었는가

        for (Sample s : samples) {
            ThrottleState st = s.throttleState();

            // ── 결측 구간 추적 ──────────────────────────────────────────
            if (st == ThrottleState.UNKNOWN) {
                missing++;
                if (runStart == null) runStart = s.tSec();
                // 진행 중인 에피소드에 결측이 섞이면 그 에피소드는 완결로 볼 수 없다.
                if (epStart != null) epHasUnknown = true;
            } else if (runStart != null) {
                // 공백의 폭은 결측 샘플들의 시각 차이가 아니라 <b>마지막 관측 지점부터 다음
                // 관측 지점까지</b>다 — 상태 변화가 숨어 있을 수 있는 구간이 그 전체이기 때문이다.
                double from = lastKnownT != null ? lastKnownT : runStart;
                if (s.tSec() - from > longestGap) {
                    longestGap = s.tSec() - from;
                    gapStart = from;
                    gapEnd = s.tSec();
                }
                runStart = null;
            }

            // ── 에피소드 경계 ──────────────────────────────────────────
            if (st == ThrottleState.THROTTLED) {
                if (epStart == null) {
                    epStart = s.tSec();
                    epHasUnknown = false;
                    if (out.ttt == null && !unknownBeforeFirst && s.tSec() <= boundary) {
                        out.ttt = s.tSec();
                    }
                }
            } else if (st == ThrottleState.CLEAR && epStart != null) {
                if (epHasUnknown) {
                    incomplete++;                       // 지표에서 제외
                } else {
                    if (epStart <= boundary) out.teds.add(round1(s.tSec() - epStart));
                    if (out.trtState == null && epStart <= boundary && s.tSec() > boundary) {
                        out.trtState = round1(s.tSec() - boundary);
                    }
                }
                epStart = null;
                epHasUnknown = false;
            }

            // 최초 THROTTLED를 아직 못 봤는데 결측이 나오면, 그 뒤의 첫 THROTTLED는
            // "처음"인지 확신할 수 없다 — 결측 구간에서 이미 걸렸다 풀렸을 수 있다.
            if (out.ttt == null && epStart == null && st == ThrottleState.UNKNOWN) {
                unknownBeforeFirst = true;
            }
            if (st != ThrottleState.UNKNOWN) lastKnownT = s.tSec();
        }
        // 측정이 끝날 때까지 열려 있던 에피소드는 종료 시각을 모른다 — 완결로 세지 않는다.
        if (epStart != null) incomplete++;
        if (runStart != null) {                         // 마지막까지 결측으로 끝난 경우
            double from = lastKnownT != null ? lastKnownT : runStart;
            double to = samples.get(samples.size() - 1).tSec();
            if (to - from > longestGap) { longestGap = to - from; gapStart = from; gapEnd = to; }
        }

        double ratio = samples.isEmpty() ? 0.0 : (double) missing / samples.size();
        out.quality = new ThrottleDataQuality(missing, round3(ratio), round1(longestGap),
                round1(gapStart), round1(gapEnd), incomplete);
        addThrottleWarnings(out, samples, warnings, missing, ratio, longestGap, gapStart, gapEnd, incomplete);
        return out;
    }

    /** 결측률이 높을수록 지표를 그대로 믿으면 안 된다는 사실을 문장으로 알린다(§2.4). */
    private static void addThrottleWarnings(ThrottleMetrics out, List<Sample> samples,
                                            List<String> warnings, int missing, double ratio,
                                            double longestGap, Double gapStart, Double gapEnd,
                                            int incomplete) {
        if (missing > 0) {
            warnings.add(String.format("throttled 측정값 %d개 중 %d개(%.1f%%)가 누락됐다.",
                    samples.size(), missing, ratio * 100));
            if (ratio >= 0.05) {
                warnings.add("결측률이 5%를 넘어 실측 TTT·TED·TRT의 신뢰도가 낮다 — 로깅 주기와 스크립트 예외 처리를 점검할 것.");
            }
            if (gapStart != null && longestGap > medianInterval(samples) * 2) {
                warnings.add(String.format("가장 긴 미확정 구간은 %.1f~%.1f초(%.1f초)다 — 이 구간의 스로틀 상태는 지표에 반영하지 않았다.",
                        gapStart, gapEnd, longestGap));
            }
        }
        if (incomplete > 0) {
            warnings.add(String.format("결측이 포함되거나 끝나지 않은 스로틀링 에피소드 %d개를 TED 중앙값 계산에서 제외했다.",
                    incomplete));
        }
        if (out.ttt == null) {
            warnings.add("측정 구간에서 스로틀링(0x4) 시작 시점을 확정하지 못했다 — 한 번도 관측되지 않았거나, 최초 관측 직전이 결측이라 발생 시점을 특정할 수 없다.");
        }
    }

    /** 샘플 간격의 중앙값 — "공백이 비정상적으로 길다"의 기준선. */
    private static double medianInterval(List<Sample> samples) {
        if (samples.size() < 2) return 0.0;
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) gaps.add(samples.get(i).tSec() - samples.get(i - 1).tSec());
        gaps.sort(Double::compare);
        return gaps.get(gaps.size() / 2);
    }

    /** 상승 지수곡선 T(t)=T∞−(T∞−T0)e^(−t/τ) 적합. */
    static Fit fitRising(List<Sample> pts) {
        Fit f = fitExponential(pts, true);
        if (f == null) throw new IllegalArgumentException(
                "가열 곡선 적합에 실패했다 — 온도가 실제로 상승하는 구간인지, 샘플이 시간순인지 확인할 것.");
        return f;
    }

    /** 하강 지수곡선 T(t)=T∞+(T0−T∞)e^(−t/τ) 적합. */
    static Fit fitFalling(List<Sample> pts, double ambientC) {
        return fitExponential(pts, false);
    }

    /**
     * τ를 로그 격자로 훑으면서, 각 τ에 대해 <b>온도 영역에서</b> 2변수 최소제곱을 닫힌 형태로 푼다.
     *
     * <p>모델 {@code T(t) = b0 + b1·e^(−t/τ)} 는 τ를 고정하면 b0(=T∞)와 b1(=T0−T∞)에 대해
     * <b>선형</b>이다. 그래서 τ만 1차원으로 훑으면 나머지는 정규방정식으로 한 번에 풀린다.
     * 예전 방식(로그를 취해 직선회귀)은 온도가 점근값에 가까워질수록 ln(T∞−T)가 측정 잡음에
     * 폭발적으로 민감해져 τ를 크게 빗나가게 했다 — 실측 데이터에는 반드시 ±0.3℃ 수준의
     * 양자화·잡음이 있으므로 이 방식이 아니면 못 쓴다.
     */
    static Fit fitExponential(List<Sample> pts, boolean rising) {
        double t0 = pts.get(0).tSec();
        double span = pts.get(pts.size() - 1).tSec() - t0;
        if (span <= 0) return null;
        double tauMin = Math.max(1.0, span / 500.0);
        double tauMax = Math.max(tauMin * 2, span * 20.0);
        int steps = 400;

        double mean = pts.stream().mapToDouble(Sample::socTempC).average().orElse(0);
        double sst = pts.stream().mapToDouble(s -> Math.pow(s.socTempC() - mean, 2)).sum();

        Fit best = null;
        double bestSse = Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            double tau = tauMin * Math.pow(tauMax / tauMin, (double) i / steps);
            double n = pts.size(), sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (Sample s : pts) {
                double x = Math.exp(-(s.tSec() - t0) / tau);
                sx += x; sy += s.socTempC(); sxx += x * x; sxy += x * s.socTempC();
            }
            double denom = n * sxx - sx * sx;
            if (Math.abs(denom) < 1e-12) continue;
            double b1 = (n * sxy - sx * sy) / denom;   // T0 − T∞
            double b0 = (sy - b1 * sx) / n;            // T∞
            // 물리적 타당성: 상승 곡선이면 시작이 점근값보다 낮아야(b1<0), 하강이면 높아야(b1>0)
            if (rising ? b1 >= -1e-6 : b1 <= 1e-6) continue;

            double sse = 0;
            for (Sample s : pts) {
                double pred = b0 + b1 * Math.exp(-(s.tSec() - t0) / tau);
                sse += Math.pow(s.socTempC() - pred, 2);
            }
            if (sse < bestSse) {
                bestSse = sse;
                double rmse = Math.sqrt(sse / pts.size());
                double r2 = sst > 1e-12 ? 1.0 - sse / sst : 0.0;
                best = new Fit(round1(b0), round1(tau), round1(b0 + b1), round3(r2), round3(rmse), pts.size());
            }
        }
        return best;
    }

    private static double round1(double v) { return Round.round1(v); }
    private static Double round1(Double v) { return v == null ? null : Round.round1(v.doubleValue()); }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
