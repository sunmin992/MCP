package com.wastesim.service;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.WasteType;
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
        double[] sum = new double[12];
        for (int s = 1; s <= seeds; s++) {
            SimulationResult r = sim.runSingle(cfg, s);
            Map<Integer, Double> wm = r.getWasteByMonth();
            if (wm != null) for (int m = 0; m < 12; m++) sum[m] += wm.getOrDefault(m, 0.0);
        }

        ScenarioResponse resp = new ScenarioResponse(
                "MONTHLY_WASTE", "월별 쓰레기 배출량 (계절 변동 가정)", "월");
        resp.setYLabel("배출량(kg/월)");
        resp.setYUnit("kg");
        ScenarioResponse.Series series = resp.newSeries("월 배출량(kg)");
        List<String> cats = new ArrayList<>();
        int bestM = 0, minM = 0;
        double bestV = -1, minV = Double.MAX_VALUE;
        for (int m = 0; m < 12; m++) {
            double avg = sum[m] / seeds;
            series.add(Math.round(avg * 10) / 10.0, 0);
            cats.add((m + 1) + "월");
            if (avg > bestV) { bestV = avg; bestM = m; }
            if (avg < minV) { minV = avg; minM = m; }
        }
        resp.setXCategories(cats);
        resp.addInsight("최다 배출 월", (bestM + 1) + "월 (" + Math.round(bestV) + " kg)");
        resp.addInsight("최소 배출 월", (minM + 1) + "월 (" + Math.round(minV) + " kg)");
        resp.addInsight("주의", "계절 가중치는 명절·여름·연말 패턴을 가정한 값으로, 실측 데이터가 아닙니다");
        return resp;
    }
}
