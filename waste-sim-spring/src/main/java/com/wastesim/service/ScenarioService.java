package com.wastesim.service;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TruckType;
import com.wastesim.model.WasteType;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.simulation.TravelTimeCalculator;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 시나리오 실험 — 논문의 분석 축들을 sweep/그리드로 구동한다.
 *
 *  1) occupationMixComparison : 지역 성격(구성)별 × 수거시각 → 구성마다 최적 수거시각이 달라짐
 *  2) collectionSweep         : 하루 중 수거시각 sweep(06:00~18:00) 곡선
 *  3) behaviorGrid            : 외출분산 α × 배출변동 β 민감도
 *  4) infraGrid               : 수거장 용량 C × 민원 임계 θ 트레이드오프
 *  5) densityComparison       : 저밀도 빌라촌 vs 고밀도 원룸촌
 *  …
 * 12) truckRouteSearch        : 차종 × 방문 순서 격자 → 민원 최소 조합
 */
@Service
public class ScenarioService {

    private final SimulationService sim;

    public ScenarioService(SimulationService sim) {
        this.sim = sim;
    }

    /** 설정으로 다중 시드 실험을 돌려 평균/표준편차만 추려 반환 */
    private double[] meanStd(SimulationConfig cfg) {
        SimulationResult r = sim.runExperiment(cfg);
        return new double[]{r.getMeanComplaints(), r.getStdComplaints()};
    }

    // ── 1. 거주민 구성 × 수거시각 ──────────────────────────────────────────
    public ScenarioResponse occupationMixComparison(SimulationConfig base, List<String> times) {
        if (times == null || times.isEmpty()) times = Arrays.asList("08:00", "10:00", "12:00", "14:00", "16:00");
        ScenarioResponse resp = new ScenarioResponse(
                "OCCUPATION_MIX", "거주민 구성별 최적 수거시각 비교", "수거 시각");
        resp.setXCategories(new ArrayList<>(times));

        for (ScenarioPreset preset : ScenarioPreset.values()) {
            ScenarioResponse.Series s = resp.newSeries(preset.labelKo);
            double bestMean = Double.MAX_VALUE;
            String bestTime = null;
            for (String t : times) {
                SimulationConfig cfg = base.copy();
                cfg.setCollectionTimeLabel(t);
                cfg.setOccupationMix(preset.mix);
                double[] ms = meanStd(cfg);
                s.add(ms[0], ms[1]);
                if (ms[0] < bestMean) { bestMean = ms[0]; bestTime = t; }
            }
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("scenario", preset.labelKo);
            ins.put("desc", preset.desc);
            ins.put("ratio", preset.ratioPercent());
            ins.put("bestTime", bestTime);
            ins.put("bestMean", Math.round(bestMean * 10) / 10.0);
            resp.addInsight(ins);
        }
        return resp;
    }

    // ── 2. 수거시각 sweep (곡선) ──────────────────────────────────────────
    public ScenarioResponse collectionSweep(SimulationConfig base,
                                            int startMin, int endMin, int stepMin) {
        checkSweepRange(startMin, endMin, stepMin);
        ScenarioResponse resp = new ScenarioResponse(
                "COLLECTION_SWEEP", "수거 시각 sweep (하루 중 최적 시각)", "수거 시각");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");

        double bestMean = Double.MAX_VALUE, worstMean = -1;
        String bestTime = null, worstTime = null;
        List<String> cats = new ArrayList<>();
        for (int m = startMin; m <= endMin; m += stepMin) {
            String t = SimulationConfig.minutesToHhmm(m);
            cats.add(t);
            SimulationConfig cfg = base.copy();
            cfg.setCollectionTimeLabel(t);
            double[] ms = meanStd(cfg);
            s.add(ms[0], ms[1]);
            if (ms[0] < bestMean) { bestMean = ms[0]; bestTime = t; }
            if (ms[0] > worstMean) { worstMean = ms[0]; worstTime = t; }
        }
        resp.setXCategories(cats);
        resp.addInsight("최적 수거시각", bestTime + " (" + Math.round(bestMean * 10) / 10.0 + "건)");
        resp.addInsight("최악 수거시각", worstTime + " (" + Math.round(worstMean * 10) / 10.0 + "건)");
        resp.addInsight("개선 폭", Math.round((worstMean - bestMean) * 10) / 10.0 + "건");
        return resp;
    }

    /**
     * 스윕 후보 수 상한 — 24시간을 10분 간격으로 훑은 개수(1440/10 + 1).
     *
     * <p>후보 하나가 곧 다중 시드 시뮬레이션 한 벌이라, 이 값이 커지면 요청 하나가
     * 서버 스레드를 오래 붙잡는다. 사용자가 실수로 1분 간격을 넣으면 1441회를 돌게 되는데,
     * 그건 요청자가 의도한 분석이 아니라 사고다 — 조용히 오래 도는 대신 거부한다.
     */
    public static final int MAX_SWEEP_POINTS = 145;

    /**
     * 스윕 범위를 <b>루프에 들어가기 전에</b> 검사한다.
     *
     * <p>{@code stepMin <= 0}이면 {@code m += stepMin}이 전진하지 않아 루프가 끝나지
     * 않는다. 그 안에서 매 회 실제 시뮬레이션까지 돌리므로, 잘못된 값 하나로 워커 스레드가
     * 영구히 묶인다. 검증을 호출측에만 두면 새 호출 경로가 생길 때마다 같은 구멍이 다시
     * 열리므로, 불변식은 루프를 가진 이 자리에서 지킨다.
     *
     * <p>시작이 종료보다 늦은 경우도 여기서 막는다. 예전에는 후보 0개로 조용히 지나갔는데,
     * 그러면 {@code bestTime}이 null, {@code bestMean}이 {@code Double.MAX_VALUE}로 남아
     * "최적 수거시각: null (9.2E17건)" 같은 값이 그대로 사용자에게 나갔다 — 빈 결과보다
     * 나쁜, <b>그럴듯해 보이는 쓰레기</b>다.
     */
    static void checkSweepRange(int startMin, int endMin, int stepMin) {
        if (stepMin <= 0) {
            throw new IllegalArgumentException(
                    "스윕 간격(stepMinutes)은 1분 이상이어야 합니다 (받은 값: " + stepMin + ").");
        }
        if (startMin > endMin) {
            throw new IllegalArgumentException(
                    "스윕 시작 시각이 종료 시각보다 늦습니다 (시작 "
                            + SimulationConfig.minutesToHhmm(startMin)
                            + ", 종료 " + SimulationConfig.minutesToHhmm(endMin) + ").");
        }
        long points = (long) (endMin - startMin) / stepMin + 1;
        if (points > MAX_SWEEP_POINTS) {
            throw new IllegalArgumentException(
                    "스윕 후보가 " + points + "개로 상한 " + MAX_SWEEP_POINTS
                            + "개를 넘습니다. 간격을 넓히거나 범위를 좁혀 주세요.");
        }
    }

    /** 채팅에서 명시한 수거 시각들만 같은 조건으로 실행해 직접 비교한다. */
    public ScenarioResponse collectionTimeComparison(SimulationConfig base, List<Integer> times) {
        ScenarioResponse resp = new ScenarioResponse(
                "COLLECTION_TIME_COMPARISON", "지정 수거 시각 비교", "수거 시각");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");
        List<String> categories = new ArrayList<>();
        double best = Double.MAX_VALUE;
        String bestTime = null;
        for (int minute : times) {
            String time = SimulationConfig.minutesToHhmm(minute);
            categories.add(time);
            SimulationConfig cfg = base.copy();
            cfg.setCollectionTimeLabel(time);
            double[] ms = meanStd(cfg);
            s.add(ms[0], ms[1]);
            if (ms[0] < best) {
                best = ms[0];
                bestTime = time;
            }
        }
        resp.setXCategories(categories);
        resp.addInsight("민원이 가장 적은 수거 시각", bestTime + " (" + Math.round(best * 10) / 10.0 + "건)");
        if (s.getValues().size() >= 2) {
            double min = Collections.min(s.getValues());
            double max = Collections.max(s.getValues());
            resp.addInsight("최대 차이", Math.round((max - min) * 10) / 10.0 + "건");
        }
        return resp;
    }

    // ── 3. 행동 변동: 외출분산 α × 배출변동 β ──────────────────────────────
    public ScenarioResponse behaviorGrid(SimulationConfig base,
                                         double[] alphas, double[] betas) {
        if (alphas == null || alphas.length == 0) alphas = new double[]{10, 30, 60, 90, 120};
        if (betas == null || betas.length == 0) betas = new double[]{0.1, 0.3, 0.5};
        ScenarioResponse resp = new ScenarioResponse(
                "BEHAVIOR_GRID", "거주민 행동 변동 민감도 (α 외출분산 × β 배출변동)",
                "외출 시각 분산 α (분)");
        List<String> cats = new ArrayList<>();
        for (double a : alphas) cats.add(String.valueOf((int) a));
        resp.setXCategories(cats);

        for (double beta : betas) {
            ScenarioResponse.Series s = resp.newSeries("β=" + beta + " kg");
            for (double a : alphas) {
                SimulationConfig cfg = base.copy();
                cfg.setLeaveSigma(a);
                cfg.setWasteSigma(beta);
                double[] ms = meanStd(cfg);
                s.add(ms[0], ms[1]);
            }
        }
        resp.addInsight("해석", "α(외출분산)가 커질수록 배출이 퍼져 수거 정책 민감도가 상승");
        return resp;
    }

    // ── 4. 인프라: 용량 C × 임계 θ ────────────────────────────────────────
    public ScenarioResponse infraGrid(SimulationConfig base,
                                      double[] capacities, double[] thresholds) {
        if (capacities == null || capacities.length == 0) capacities = new double[]{20, 30, 40, 50, 60};
        if (thresholds == null || thresholds.length == 0) thresholds = new double[]{0.7, 0.8, 0.9};
        ScenarioResponse resp = new ScenarioResponse(
                "INFRA_GRID", "인프라 트레이드오프 (용량 C × 민원 임계 θ)", "수거장 용량 C (kg)");
        List<String> cats = new ArrayList<>();
        for (double c : capacities) cats.add(String.valueOf((int) c));
        resp.setXCategories(cats);

        for (double th : thresholds) {
            ScenarioResponse.Series s = resp.newSeries("θ=" + th);
            for (double c : capacities) {
                SimulationConfig cfg = base.copy();
                cfg.setCapacity(c);
                cfg.setThreshold(th);
                double[] ms = meanStd(cfg);
                s.add(ms[0], ms[1]);
            }
        }
        resp.addInsight("해석", "용량을 키우면 민원↓ — '큰 수거장 vs 잦은 수거'의 대안. θ(시민 기대수준)가 높을수록 민원 민감");
        return resp;
    }

    // ── 5. 밀도: 저밀도 빌라촌 vs 고밀도 원룸촌 ────────────────────────────
    public ScenarioResponse densityComparison(SimulationConfig base, List<int[]> densities) {
        if (densities == null || densities.isEmpty()) {
            densities = Arrays.asList(
                    new int[]{4, 10},   // 저밀도 빌라촌 40명
                    new int[]{4, 25},   // 기본 원룸촌 100명
                    new int[]{4, 40},   // 고밀도 원룸촌 160명
                    new int[]{8, 40});  // 대규모 고밀도 320명
        }
        ScenarioResponse resp = new ScenarioResponse(
                "DENSITY", "거주 밀도별 민원 (저밀도 빌라촌 vs 고밀도 원룸촌)", "건물수 × 동당 인원");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");
        List<String> cats = new ArrayList<>();
        for (int[] d : densities) {
            int nB = d[0], rPB = d[1];
            cats.add(nB + "동×" + rPB + "명(" + (nB * rPB) + ")");
            SimulationConfig cfg = base.copy();
            cfg.setNumBuildings(nB);
            cfg.setResidentsPerBuilding(rPB);
            double[] ms = meanStd(cfg);
            s.add(ms[0], ms[1]);
        }
        resp.setXCategories(cats);
        resp.addInsight("해석", "동당 인원이 늘면 같은 용량 수거장이 빨리 차 민원이 급증");
        return resp;
    }

    // ── 라벨 1건을 계산해 막대 계열에 추가하는 헬퍼 ────────────────────────
    private void addBar(ScenarioResponse.Series s, List<String> cats, String label, SimulationConfig cfg) {
        double[] ms = meanStd(cfg);
        s.add(ms[0], ms[1]);
        cats.add(label);
    }

    // ── 6. 수거 스케줄: 다회/격일/평일·주말/공휴일 ─────────────────────────
    public ScenarioResponse collectionSchedule(SimulationConfig base) {
        ScenarioResponse resp = new ScenarioResponse(
                "COLLECTION_SCHEDULE", "수거 정책별 민원 (다회·격일·주말·공휴일)", "수거 정책");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");
        List<String> cats = new ArrayList<>();

        addBar(s, cats, "1일 1회(기준)", base.copy());

        SimulationConfig twice = base.copy();
        twice.setCollectionTimesMinutes(Arrays.asList(9 * 60, 18 * 60));
        addBar(s, cats, "1일 2회(09·18시)", twice);

        SimulationConfig everyOther = base.copy();
        everyOther.setCollectionIntervalDays(2);
        addBar(s, cats, "격일제", everyOther);

        SimulationConfig noWeekend = base.copy();
        noWeekend.setSkipWeekends(true);
        addBar(s, cats, "주말 미수거", noWeekend);

        SimulationConfig holiday = base.copy();
        holiday.setHolidays(Arrays.asList(7, 14, 21));
        addBar(s, cats, "공휴일 3일 미수거", holiday);

        resp.setXCategories(cats);
        resp.addInsight("해석", "수거 빈도를 줄이면(격일·주말 미수거) 수거장이 오래 차 민원↑, 1일 2회는 민원↓");
        return resp;
    }

    // ── 7. 다중 트럭 · 구역 분할 ───────────────────────────────────────────
    public ScenarioResponse multiTruck(SimulationConfig base, int[] truckCounts) {
        if (truckCounts == null || truckCounts.length == 0) truckCounts = new int[]{1, 2, 4};
        ScenarioResponse resp = new ScenarioResponse(
                "MULTI_TRUCK", "다중 트럭 · 구역 분할 효과", "트럭 수(구역)");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");
        List<String> cats = new ArrayList<>();
        for (int n : truckCounts) {
            SimulationConfig cfg = base.copy();
            cfg.setNumTrucks(n);
            cfg.setRouteTravelMinutes(20);   // 이동시간 20분/건물 — 분할 효과 가시화
            addBar(s, cats, n + "대", cfg);
        }
        resp.setXCategories(cats);
        resp.addInsight("해석", "트럭을 늘려 구역을 나누면 경로가 짧아져 늦게 수거되던 건물이 빨리 비워짐 → 민원↓ (이동 20분/건물 가정)");
        return resp;
    }

    // ── 8. 분리배출: 통합 vs 종류별 ────────────────────────────────────────
    public ScenarioResponse wasteSeparation(SimulationConfig base) {
        ScenarioResponse resp = new ScenarioResponse(
                "WASTE_SEPARATION", "분리배출 효과 (통합 vs 일반·음식물·재활용)", "수거 방식");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");
        List<String> cats = new ArrayList<>();

        addBar(s, cats, "통합 수거", base.copy());

        SimulationConfig sep = base.copy();
        sep.setWasteTypes(WasteType.defaultSeparated());
        addBar(s, cats, "분리배출 3종", sep);

        resp.setXCategories(cats);
        resp.addInsight("해석", "분리배출은 일반 적재를 분산하지만, 음식물(낮은 임계 0.7)·재활용(격일 수거)이 새 민원 요인이 될 수 있음");
        return resp;
    }

    // ── 9. 새 거주민 유형: 야간근무·1인직장인 × 수거시각 ───────────────────
    public ScenarioResponse newOccupations(SimulationConfig base, List<String> times) {
        if (times == null || times.isEmpty())
            times = Arrays.asList("08:00", "12:00", "16:00", "20:00", "22:00");
        ScenarioResponse resp = new ScenarioResponse(
                "NEW_OCCUPATIONS", "확장 거주민 유형별 최적 수거시각", "수거 시각");
        resp.setXCategories(new ArrayList<>(times));

        // 라벨 → {구성 설명, 직업 mix}
        Map<String, String> descs = new LinkedHashMap<>();
        Map<String, List<String>> mixes = new LinkedHashMap<>();
        descs.put("기존 3종", "생산직·학생·주부");
        mixes.put("기존 3종",   Arrays.asList("BlueCollar", "Student", "Housewife"));
        descs.put("+야간근무", "야간근무자 추가");
        mixes.put("+야간근무",  Arrays.asList("BlueCollar", "Student", "Housewife", "NightShift"));
        descs.put("+1인직장인", "1인 직장인 추가");
        mixes.put("+1인직장인", Arrays.asList("BlueCollar", "Student", "Housewife", "OfficeWorker"));
        descs.put("야간근무 위주", "야간근무 60%");
        mixes.put("야간근무 위주", Arrays.asList("NightShift", "NightShift", "NightShift", "BlueCollar", "Student"));

        for (Map.Entry<String, List<String>> en : mixes.entrySet()) {
            ScenarioResponse.Series s = resp.newSeries(en.getKey());
            double best = Double.MAX_VALUE;
            String bestTime = null;
            for (String t : times) {
                SimulationConfig cfg = base.copy();
                cfg.setCollectionTimeLabel(t);
                cfg.setOccupationMix(en.getValue());
                double[] ms = meanStd(cfg);
                s.add(ms[0], ms[1]);
                if (ms[0] < best) { best = ms[0]; bestTime = t; }
            }
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("scenario", en.getKey());
            ins.put("desc", descs.get(en.getKey()));
            ins.put("bestTime", bestTime);
            ins.put("bestMean", Math.round(best * 10) / 10.0);
            resp.addInsight(ins);
        }
        resp.addInsight("해석", "야간근무자(21시 배출)가 많을수록 늦은 시각 수거가 유리해져 최적 수거시각이 뒤로 이동");
        return resp;
    }

    // ── 10. 결합모델 변형: 외출/귀가 2회 배출 · 임대인 에이전트 ────────────
    public ScenarioResponse couplingVariants(SimulationConfig base) {
        ScenarioResponse resp = new ScenarioResponse(
                "COUPLING_VARIANTS", "결합모델 변형 (외출/귀가 2회 · 임대인 점검)", "모델 구성");
        ScenarioResponse.Series s = resp.newSeries("월 평균 민원");
        List<String> cats = new ArrayList<>();

        addBar(s, cats, "기본(외출 1회)", base.copy());

        SimulationConfig ret = base.copy();
        ret.setReturnDischarge(true);
        addBar(s, cats, "외출/귀가 2회 배출", ret);

        SimulationConfig land = base.copy();
        land.setLandlordEnabled(true);
        addBar(s, cats, "임대인 점검 추가", land);

        SimulationConfig both = base.copy();
        both.setReturnDischarge(true);
        both.setLandlordEnabled(true);
        addBar(s, cats, "둘 다", both);

        resp.setXCategories(cats);
        resp.addInsight("해석", "귀가 2회 배출은 저녁 적재를 늘리고, 임대인(Check 전용 에이전트)은 별도 민원 채널을 추가함");
        return resp;
    }

    // ── 11. 월별(계절) 배출량: 1년 중 배출 최다 달 ─────────────────────────
    /**
     * 한국 가정 쓰레기의 계절 패턴을 가정한 월별 가중치(실측 아님, 가정값).
     * 1~12월: 연초·설(1·2월), 여름·이사철(7·8월), 추석(9월), 연말 소비(12월)에서 증가.
     */
    static final double[] KOREA_SEASONAL =
            {1.05, 1.15, 1.00, 0.95, 1.00, 1.00, 1.05, 1.10, 1.15, 1.00, 0.95, 1.20};

    public ScenarioResponse monthlyWaste(SimulationConfig base, double[] factors) {
        double[] f = (factors == null || factors.length == 0) ? KOREA_SEASONAL : factors;
        SimulationConfig cfg = base.copy();
        cfg.setDays(12 * 30);                 // 12개월 × 30일 = 360일
        cfg.setMonthlyWasteFactor(f);

        int seeds = Math.max(1, base.getSeeds());
        // 시드별 값을 그대로 들고 있는다 — 합계만 모으면 "이 차이가 계절성인지 잡음인지"를
        // 나중에 되물을 수 없다. 아래 순위 판정이 이 분산을 쓴다.
        double[][] vals = new double[12][seeds];
        for (int s = 0; s < seeds; s++) {
            SimulationResult r = sim.runSingle(cfg, s + 1);
            Map<Integer, Double> wm = r.getWasteByMonth();
            if (wm != null) for (int m = 0; m < 12; m++) vals[m][s] = wm.getOrDefault(m, 0.0);
        }

        ScenarioResponse resp = new ScenarioResponse(
                "MONTHLY_WASTE", "월별 쓰레기 배출량 (계절 변동 가정)", "월");
        resp.setYLabel("배출량(kg/월)");
        resp.setYUnit("kg");
        ScenarioResponse.Series series = resp.newSeries("월 배출량(kg)");
        List<String> cats = new ArrayList<>();
        double[] avg = new double[12];
        double bestV = -Double.MAX_VALUE, minV = Double.MAX_VALUE;
        for (int m = 0; m < 12; m++) {
            avg[m] = mean(vals[m]);
            series.add(Math.round(avg[m] * 10) / 10.0, 0);
            cats.add((m + 1) + "월");
            bestV = Math.max(bestV, avg[m]);
            minV = Math.min(minV, avg[m]);
        }
        resp.setXCategories(cats);

        // 순위를 잡음 위에 세우지 않는다.
        //
        // 예전에는 `avg > bestV` 엄격 비교로 최댓값 하나를 골랐다. 그런데 이 시뮬레이션은
        // 확률적이라 <b>가중치가 완전히 같은 달들도 매번 다른 값</b>이 나온다 — 12개월
        // 가중치를 전부 1.0으로 두고 돌려도 "5월이 최다"처럼 특정 달이 뽑힌다. 그건 계절성이
        // 아니라 난수가 정한 순위다. 동률(정확히 같은 값)만 찾아서는 이 문제가 잡히지 않는다.
        //
        // 그래서 시드 간 표준오차를 잡음 척도로 삼아, 그 안에 들어오는 달들은 <b>최댓값과
        // 구별되지 않는다</b>고 보고 함께 적는다. 시드가 1개면 잡음을 추정할 방법이 없으므로
        // 순위를 단정하지 않고 그 사실을 알린다(D-25·D-32 없는 우열을 만들지 않는다).
        double noise = standardError(vals, seeds);
        List<String> maxMonths = monthsWithin(avg, bestV, noise);
        List<String> minMonths = monthsWithin(avg, minV, noise);

        resp.addInsight("최다 배출 월", String.join("·", maxMonths) + " (" + Math.round(bestV) + " kg)");
        resp.addInsight("최소 배출 월", String.join("·", minMonths) + " (" + Math.round(minV) + " kg)");

        if (seeds < 2) {
            resp.addInsight("순위 신뢰도", "시드 1개로는 월별 차이가 계절성인지 난수인지 가릴 수 없다 — "
                    + "seeds를 늘려 다시 볼 것. 위 최다·최소 월은 이 한 번의 실현값 기준이다");
        } else if (maxMonths.size() > 1 || minMonths.size() > 1) {
            resp.addInsight("순위 신뢰도", String.format(
                    "시드 간 표준오차 ±%.0fkg 안에서 서로 구별되지 않는 달들을 함께 적었다 — "
                    + "하나를 대표로 고르지 않았다. 계절 축을 살리려면 monthlyFactor의 월별 차이를 "
                    + "키우거나 seeds를 늘릴 것", noise));
        }
        if (maxMonths.size() == 12) {
            resp.addInsight("주의", "12개월이 서로 구별되지 않는다 — 계절 가중치가 평탄하거나 "
                    + "적용되지 않았다는 뜻이므로 이 실험에서는 계절성을 읽을 수 없다");
        }
        resp.addInsight("주의", "계절 가중치는 명절·여름·연말 패턴을 가정한 값으로, 실측 데이터가 아닙니다");
        return resp;
    }

    private static double mean(double[] xs) {
        double sum = 0;
        for (double x : xs) sum += x;
        return xs.length == 0 ? 0 : sum / xs.length;
    }

    /**
     * 월 평균값의 <b>표준오차</b>(kg) — 각 달의 시드 간 분산을 통합(pooled)해 √seeds로 나눈다.
     * 그래프에 찍히는 값이 얼마나 흔들리는지의 척도이므로, 이보다 작은 월 간 차이는
     * 순위의 근거가 될 수 없다. 시드가 1개면 추정할 수 없어 0을 돌려준다(정확한 동률만 묶임).
     */
    private static double standardError(double[][] vals, int seeds) {
        if (seeds < 2) return 0.0;
        double pooledVar = 0;
        for (double[] monthVals : vals) {
            double mu = mean(monthVals);
            double ss = 0;
            for (double v : monthVals) ss += (v - mu) * (v - mu);
            pooledVar += ss / (seeds - 1);
        }
        return Math.sqrt(pooledVar / vals.length) / Math.sqrt(seeds);
    }

    /**
     * {@code target}과 잡음 범위 안에서 구별되지 않는 달 이름 목록. {@code noise}가 0이면
     * 표시 자릿수(0.1kg) 기준의 정확한 동률만 묶는다 — 사용자에게 같은 숫자로 보이는 두 달을
     * "다르다"고 판정하면 안내가 오히려 혼란스러워진다.
     */
    private static List<String> monthsWithin(double[] avg, double target, double noise) {
        double tol = Math.max(noise, 0.05);
        List<String> out = new ArrayList<>();
        for (int m = 0; m < avg.length; m++) {
            if (Math.abs(avg[m] - target) <= tol) out.add((m + 1) + "월");
        }
        return out;
    }

    // ── 12. 차종 × 방문 순서 탐색 ──────────────────────────────────────────

    /** 순서 후보를 자동 생성할 때 전수 탐색을 허용하는 상한. 4! = 24가 경계다. */
    static final int MAX_AUTO_PERMUTATIONS = 24;

    /** 한 번의 실험에서 비교할 순서 후보 개수 상한 — 실행 시간이 조합 수에 비례한다. */
    static final int MAX_ROUTE_CANDIDATES = 24;

    /**
     * 격자가 "평평하다"고 볼 경계(건). 보고하는 평균은 0.1건 단위로 반올림하므로,
     * 그보다 작은 폭은 사용자에게 보이지도 않는 차이다 — 그런 폭으로 축 순위를 매기면
     * 표에는 같은 숫자가 늘어서 있는데 결론만 승자를 지목하는 상태가 된다.
     */
    static final double FLAT_GRID_EPSILON = 0.05;

    /**
     * 차종(용량·기동성) × 수거장 방문 순서의 격자를 훑어 <b>민원이 가장 적은 조합</b>을 찾는다.
     *
     * <p><b>왜 두 축을 함께 도는가</b>: 두 축은 독립이 아니다. 1톤 트럭은 골목을 빨리 돌지만
     * 용량이 1,000kg뿐이라 많이 쌓인 건물을 뒤에 두면 용량이 먼저 바닥난다 — 즉 <b>어느
     * 차종이 유리한지가 방문 순서에 따라 뒤집힌다</b>. 축을 따로 훑으면 이 상호작용이
     * 통째로 사라지고 "5톤이 항상 낫다" 같은 뻔한 결론만 남는다.
     *
     * <p><b>이동시간을 반드시 확보한다</b>: 건물 간 이동시간이 0이면 방문 순서를 아무리
     * 바꿔도 모든 건물이 같은 시각에 수거되어 순서가 결과에 반영될 물리적 여지가 없다.
     * 차종의 기동성 배수도 0을 나눠 봐야 0이다. 그래서 base에 이동시간이 없으면
     * {@link TravelTimeCalculator#DEFAULT_ROUTE_TRAVEL_MINUTES}를 채우고 그 사실을
     * insight로 밝힌다(FR-47과 같은 이유 — 조용히 바꾸지 않는다).
     *
     * <p><b>순서 후보를 지어내지 않는다</b>: 건물이 n개면 순서는 n!이라 5개만 되어도 120가지다.
     * 전수를 못 도는 규모에서 임의로 몇 개만 뽑아 "최적"이라 부르면 사용자는 탐색하지 않은
     * 구간을 탐색했다고 읽는다. 그래서 (a) 후보를 직접 주면 그것만 돌고, (b) 자동 생성은
     * {@link #MAX_AUTO_PERMUTATIONS} 이하일 때만 전수로 하며, (c) 그 위에서는 대표 후보만
     * 돌면서 <b>전수가 아니라는 것</b>을 insight에 남긴다(엣지 스윕의 FR-100·101과 같은 원칙).
     *
     * @param base           공통 설정. 이동시간이 0이면 기본값으로 채운다
     * @param routeSequences 비교할 방문 순서 후보(각각 Node_A 형식의 리스트). null이면 자동 생성
     * @param truckTypeNames 비교할 차종 이름. null이면 전 차종
     */
    public ScenarioResponse truckRouteSearch(SimulationConfig base,
                                             List<List<String>> routeSequences,
                                             List<String> truckTypeNames) {
        int nB = base.getNumBuildings();
        List<TruckType> trucks = resolveTruckTypes(truckTypeNames);

        boolean exhaustive;
        List<List<String>> routes;
        if (routeSequences != null && !routeSequences.isEmpty()) {
            routes = new ArrayList<>(routeSequences.subList(0, Math.min(routeSequences.size(), MAX_ROUTE_CANDIDATES)));
            exhaustive = false;                       // 사용자가 고른 부분집합이다
        } else if (factorial(nB) <= MAX_AUTO_PERMUTATIONS) {
            routes = allPermutations(nB);
            exhaustive = true;
        } else {
            routes = representativeRoutes(nB);
            exhaustive = false;
        }

        // 이동시간이 0이면 순서·차종이 결과에 반영될 여지가 없다(위 주석 참고).
        SimulationConfig grid = base.copy();
        boolean travelFilled = false;
        if (grid.getRouteTravelMinutes() <= 0) {
            grid.setRouteTravelMinutes(TravelTimeCalculator.DEFAULT_ROUTE_TRAVEL_MINUTES);
            travelFilled = true;
        }

        ScenarioResponse resp = new ScenarioResponse(
                "TRUCK_ROUTE_SEARCH", "차종 × 방문 순서 탐색 (민원 최소 조합)", "방문 순서");

        List<String> cats = new ArrayList<>();
        for (List<String> r : routes) cats.add(routeLabel(r));
        resp.setXCategories(cats);

        double bestMean = Double.MAX_VALUE, worstMean = -1;
        TruckType bestTruck = null, worstTruck = null;
        List<String> bestRoute = null, worstRoute = null;

        // 축별 효과 크기 비교용 — 같은 순서에서 차종만 바꿨을 때의 폭과
        // 같은 차종에서 순서만 바꿨을 때의 폭 중 어느 쪽이 큰지가 해석의 핵심이다.
        double maxSpreadWithinRoute = 0.0;   // 순서 고정, 차종만 변화
        double maxSpreadWithinTruck = 0.0;   // 차종 고정, 순서만 변화
        double[][] means = new double[trucks.size()][routes.size()];

        for (int ti = 0; ti < trucks.size(); ti++) {
            TruckType truck = trucks.get(ti);
            ScenarioResponse.Series s = resp.newSeries(truck.labelKo);
            for (int ri = 0; ri < routes.size(); ri++) {
                List<String> route = routes.get(ri);
                SimulationConfig cfg = grid.copy();
                cfg.setTruckType(truck.name());
                cfg.setRouteSequence(new ArrayList<>(route));
                double[] ms = meanStd(cfg);
                s.add(ms[0], ms[1]);
                means[ti][ri] = ms[0];
                if (ms[0] < bestMean) { bestMean = ms[0]; bestTruck = truck; bestRoute = route; }
                if (ms[0] > worstMean) { worstMean = ms[0]; worstTruck = truck; worstRoute = route; }
            }
        }

        for (int ri = 0; ri < routes.size(); ri++) {
            double lo = Double.MAX_VALUE, hi = -1;
            for (int ti = 0; ti < trucks.size(); ti++) {
                lo = Math.min(lo, means[ti][ri]);
                hi = Math.max(hi, means[ti][ri]);
            }
            maxSpreadWithinRoute = Math.max(maxSpreadWithinRoute, hi - lo);
        }
        for (int ti = 0; ti < trucks.size(); ti++) {
            double lo = Double.MAX_VALUE, hi = -1;
            for (int ri = 0; ri < routes.size(); ri++) {
                lo = Math.min(lo, means[ti][ri]);
                hi = Math.max(hi, means[ti][ri]);
            }
            maxSpreadWithinTruck = Math.max(maxSpreadWithinTruck, hi - lo);
        }

        // key/value는 UI의 공통 렌더러가 그대로 쓰고, 나머지 필드는 MCP·REST 소비자가
        // 문자열을 다시 파싱하지 않고 조합을 그대로 재현할 수 있게 구조화해 함께 싣는다.
        // 전 조합이 동률이면 "최적"이라는 말이 없는 우열을 있다고 읽히게 한다 —
        // 어느 조합을 골라도 같다는 사실 자체를 값에 실어 보낸다.
        boolean flatGrid = Math.max(maxSpreadWithinRoute, maxSpreadWithinTruck) < FLAT_GRID_EPSILON;
        resp.addInsight(comboInsight("최적 조합", bestTruck, bestRoute, bestMean, flatGrid));
        resp.addInsight(comboInsight("최악 조합", worstTruck, worstRoute, worstMean, flatGrid));

        resp.addInsight("개선 폭", round1(worstMean - bestMean) + "건");
        resp.addInsight("탐색 조합 수", trucks.size() + "차종 × " + routes.size() + "순서 = "
                + (trucks.size() * routes.size()) + "가지");

        // 어느 축을 먼저 손봐야 하는지 — 이 실험을 돌리는 실질적 이유다.
        //
        // 다만 두 축이 모두 결과를 못 움직였다면 "그래도 차종 쪽이 크다"고 말해서는 안 된다.
        // 0.0건과 0.0건을 비교해 승자를 발표하면 사용자는 없는 차이를 있다고 읽는다 —
        // 엣지 비교의 D-25("정상상태도 피크도 같으면 조건을 바꾸라고 안내")와 같은 상황이다.
        // 그래서 이 경우에는 순위를 매기는 대신 왜 평평한지와 무엇을 올려야 하는지를 알린다.
        if (flatGrid) {
            resp.addInsight("축별 효과",
                    "이 조건에서는 차종·방문 순서 어느 쪽도 민원 수를 바꾸지 못했습니다(둘 다 0건 차이) — "
                  + "순위를 매길 근거가 없습니다. 두 축이 결과에 반영되려면 (a) 건물 간 이동시간을 "
                  + "늘려 방문 순서에 따른 수거 시각 차이를 만들거나, (b) 배출량을 늘려(거주민 수↑) "
                  + "차종 정격용량이 실제로 부족해지는 구간으로 들어가야 합니다. "
                  + "현재 이동시간 " + grid.getRouteTravelMinutes() + "분에서는 방문 순서를 바꿔도 "
                  + "수거 시각 차이가 민원 임계를 넘나들 만큼 벌어지지 않습니다");
        } else {
            resp.addInsight("축별 효과",
                    "차종만 바꿨을 때 최대 " + round1(maxSpreadWithinRoute) + "건, "
                  + "순서만 바꿨을 때 최대 " + round1(maxSpreadWithinTruck) + "건 → "
                  + (maxSpreadWithinRoute > maxSpreadWithinTruck ? "차종"
                     : maxSpreadWithinTruck > maxSpreadWithinRoute ? "방문 순서"
                     : "두") + " 축의 영향이 "
                  + (maxSpreadWithinRoute == maxSpreadWithinTruck ? "같습니다" : "더 큽니다"));
        }

        if (!exhaustive) {
            resp.addInsight("탐색 범위",
                    "전수 탐색이 아닙니다 — 건물 " + nB + "개의 가능한 순서는 " + factorial(nB)
                  + "가지이고 그중 " + routes.size() + "가지만 비교했습니다. "
                  + "여기서 나온 최적 조합은 비교한 후보 안에서의 최적입니다");
        }
        if (travelFilled) {
            resp.addInsight("가정",
                    "건물 간 이동시간이 지정되지 않아 " + TravelTimeCalculator.DEFAULT_ROUTE_TRAVEL_MINUTES
                  + "분으로 가정했습니다 — 이동시간이 0이면 방문 순서와 차종 기동성이 결과에 반영되지 않습니다");
        }
        return resp;
    }

    /** 조합 insight 한 줄 — 사람이 읽는 {@code value}와 기계가 읽는 구조화 필드를 함께 담는다. */
    private static Map<String, Object> comboInsight(String key, TruckType truck, List<String> route,
                                                    double mean, boolean tiedAcrossGrid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", (truck == null ? "-" : truck.labelKo) + " · "
                + (route == null ? "-" : routeLabel(route)) + " (" + round1(mean) + "건)"
                + (tiedAcrossGrid ? " — 전 조합 동률이라 우열이 없습니다" : ""));
        m.put("tied", tiedAcrossGrid);
        m.put("truckType", truck == null ? null : truck.name());
        m.put("truckLabel", truck == null ? null : truck.labelKo);
        m.put("routeSequence", route);
        m.put("mean", round1(mean));
        return m;
    }

    /** 이름 목록 → 차종. null·빈 목록이면 전 차종. 알 수 없는 이름은 {@link TruckType#fromName} 이 거부한다. */
    private static List<TruckType> resolveTruckTypes(List<String> names) {
        if (names == null || names.isEmpty()) return Arrays.asList(TruckType.values());
        // 중복 입력이 격자를 부풀리지 않도록 순서를 보존하며 한 번씩만 남긴다.
        LinkedHashSet<TruckType> out = new LinkedHashSet<>();
        for (String n : names) out.add(TruckType.fromName(n));
        return new ArrayList<>(out);
    }

    /** 건물 n개의 모든 방문 순서(Node_A…). 호출부가 n! ≤ 상한임을 보장한다. */
    static List<List<String>> allPermutations(int n) {
        List<List<String>> out = new ArrayList<>();
        permute(new ArrayList<>(), new boolean[n], n, out);
        return out;
    }

    private static void permute(List<Integer> acc, boolean[] used, int n, List<List<String>> out) {
        if (acc.size() == n) {
            List<String> seq = new ArrayList<>(n);
            for (int b : acc) seq.add(SimulationEngine.nodeId(b));
            out.add(seq);
            return;
        }
        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            used[i] = true;
            acc.add(i);
            permute(acc, used, n, out);
            acc.remove(acc.size() - 1);
            used[i] = false;
        }
    }

    /**
     * 전수 탐색이 불가능한 규모에서 쓰는 대표 순서 — 정방향과 역방향 두 가지뿐이다.
     * 여기에 "무작위 k개"를 섞지 않는 이유는, 무작위 후보가 섞이면 같은 요청이 실행마다
     * 다른 최적을 내놓아 재현성(NFR-02)이 깨지기 때문이다.
     */
    static List<List<String>> representativeRoutes(int n) {
        List<String> forward = new ArrayList<>(n);
        for (int b = 0; b < n; b++) forward.add(SimulationEngine.nodeId(b));
        List<String> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        return List.of(forward, reverse);
    }

    /** 차트 x축 라벨 — "A→C→B" 처럼 노드 접두사를 떼어 짧게 만든다. */
    static String routeLabel(List<String> route) {
        StringBuilder sb = new StringBuilder();
        for (String node : route) {
            if (sb.length() > 0) sb.append('→');
            sb.append(node.startsWith("Node_") ? node.substring(5) : node);
        }
        return sb.toString();
    }

    private static int factorial(int n) {
        int f = 1;
        for (int i = 2; i <= n; i++) f *= i;
        return f;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
