package com.wastesim.simulation;

import com.wastesim.model.OccupationType;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.WasteType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DEVS 포름 재구현 — event-queue 기반 discrete-event simulation (확장판).
 *
 * 시간 단위: 분 (1일 = 1440분, 1주 = 7일, day%7: 0=월 … 5=토,6=일)
 *
 * 지원 기능:
 *  - 수거 스케줄: 다회/격일/평일·주말/공휴일  (collectionTimes, interval, skipWeekends, holidays)
 *  - 다중 트럭·구역 분할: 건물을 트럭별로 나눠 순회, 경로 이동시간 반영
 *  - 분리배출: 종류(일반/음식물/재활용)별 수거장·임계·수거 주기
 *  - 결합모델 변형: 외출/귀가 2회 배출, 임대인(Check만) 점검 에이전트
 */
@Component
public class SimulationEngine {

    static final int DAY = 1440;
    static final String LANDLORD = "Landlord";

    // ── 이벤트 정의 ─────────────────────────────────────────────────────────

    abstract static class Evt implements Comparable<Evt> {
        final int time;
        Evt(int time) { this.time = time; }
        @Override public int compareTo(Evt o) {
            int c = Integer.compare(this.time, o.time);
            return c != 0 ? c : Integer.compare(this.priority(), o.priority());
        }
        int priority() { return 1; }
    }

    /** 수거: 트럭이 건물 b를 day d에 방문해 수거(due 종류만 비움) */
    static class CollectEvt extends Evt {
        final int building, day;
        CollectEvt(int time, int building, int day) { super(time); this.building = building; this.day = day; }
        @Override int priority() { return 0; }   // 같은 시각이면 수거 먼저
    }

    /** 배출: 거주민이 건물 b에 amount(kg)만큼 배출 (외출/귀가 분할분 포함) */
    static class DischargeEvt extends Evt {
        final OccupationType occ; final int building, day; final double amount;
        DischargeEvt(int time, OccupationType occ, int building, int day, double amount) {
            super(time); this.occ = occ; this.building = building; this.day = day; this.amount = amount;
        }
        @Override int priority() { return 1; }
    }

    /** 임대인 점검: 건물 b의 수거장이 더러우면 민원(Check만 가진 에이전트) */
    static class InspectEvt extends Evt {
        final int building, day;
        InspectEvt(int time, int building, int day) { super(time); this.building = building; this.day = day; }
        @Override int priority() { return 2; }   // 배출·수거 뒤에 점검
    }

    // ── 메인 시뮬레이션 ──────────────────────────────────────────────────────

    public SimulationResult run(SimulationConfig cfg, int seed) {
        Random rng = new Random(seed);
        int days = cfg.getDays();
        int totalMinutes = days * DAY;
        int nB = cfg.getNumBuildings();

        List<WasteType> types = cfg.resolveWasteTypes();
        int nT = types.size();

        // 수거장 적재량 cans[building][type]
        double[][] fill = new double[nB][nT];
        double[][] peak = new double[nB][nT];

        // 트럭별 구역(건물) 배정 — round-robin
        int numTrucks = Math.max(1, cfg.getNumTrucks());
        List<List<Integer>> routes = new ArrayList<>();
        for (int i = 0; i < numTrucks; i++) routes.add(new ArrayList<>());
        for (int b = 0; b < nB; b++) routes.get(b % numTrucks).add(b);
        int travel = cfg.getRouteTravelMinutes();

        PriorityQueue<Evt> pq = new PriorityQueue<>();

        // ── 수거 이벤트 생성 ──────────────────────────────────────────────
        for (int d = 0; d < days; d++) {
            if (!isTruckDay(d, cfg)) continue;
            List<Integer> slots = daySlots(d, cfg);
            for (List<Integer> route : routes) {
                for (int slot : slots) {
                    for (int pos = 0; pos < route.size(); pos++) {
                        int b = route.get(pos);
                        int t = d * DAY + slot + pos * travel;
                        if (t <= totalMinutes) pq.offer(new CollectEvt(t, b, d));
                    }
                }
            }
        }

        // ── 임대인 점검 이벤트 ────────────────────────────────────────────
        if (cfg.isLandlordEnabled()) {
            for (int d = 0; d < days; d++) {
                int t = d * DAY + cfg.getLandlordInspectMinutes();
                if (t > totalMinutes) continue;
                for (int b = 0; b < nB; b++) pq.offer(new InspectEvt(t, b, d));
            }
        }

        // ── 거주민 배출 이벤트(전 기간 사전 생성) ─────────────────────────
        List<OccupationType> mix = cfg.resolveOccupationMix();
        int rPB = cfg.getResidentsPerBuilding();
        boolean retDis = cfg.isReturnDischarge();
        double retFrac = clamp01(cfg.getReturnFraction());
        for (int b = 0; b < nB; b++) {
            for (int k = 0; k < rPB; k++) {
                OccupationType occ = mix.get((b * rPB + k) % mix.size());
                for (int d = 0; d < days; d++) {
                    double dailyAmount = sampleWaste(rng, cfg.getWasteSigma());
                    int leaveT = d * DAY + sampleOffset(rng, occ.leaveMeanMinutes, cfg.getLeaveSigma());
                    if (retDis) {
                        int retT = d * DAY + sampleOffset(rng, occ.returnMeanMinutes, cfg.getLeaveSigma());
                        offerDischarge(pq, leaveT, occ, b, d, dailyAmount * (1.0 - retFrac), totalMinutes);
                        offerDischarge(pq, retT,   occ, b, d, dailyAmount * retFrac,         totalMinutes);
                    } else {
                        offerDischarge(pq, leaveT, occ, b, d, dailyAmount, totalMinutes);
                    }
                }
            }
        }

        // ── 집계 ──────────────────────────────────────────────────────────
        int total = 0;
        Map<String, Integer> byOcc = new LinkedHashMap<>();
        Map<Integer, Integer> byDay = new TreeMap<>();

        while (!pq.isEmpty()) {
            Evt e = pq.poll();
            if (e.time > totalMinutes) break;

            if (e instanceof CollectEvt ce) {
                // 분리배출 수거 주기: due 종류만 비움
                for (int t = 0; t < nT; t++) {
                    if (ce.day % types.get(t).getIntervalDays() == 0) fill[ce.building][t] = 0.0;
                }

            } else if (e instanceof DischargeEvt de) {
                // 종류별로 비율 분할 배출 + 배출 시점 청결도 판정(Check)
                boolean complained = false;
                for (int t = 0; t < nT; t++) {
                    WasteType wt = types.get(t);
                    double add = de.amount * wt.getFraction();
                    if (add <= 0) continue;
                    fill[de.building][t] += add;
                    if (fill[de.building][t] > peak[de.building][t]) peak[de.building][t] = fill[de.building][t];
                    double ratio = wt.getCapacity() > 0 ? fill[de.building][t] / wt.getCapacity() : 0.0;
                    if (ratio >= wt.getThreshold()) complained = true;   // 한 종류라도 넘으면 1건
                }
                if (complained) {
                    total++;
                    byOcc.merge(de.occ.name(), 1, Integer::sum);
                    byDay.merge(de.day, 1, Integer::sum);
                }

            } else if (e instanceof InspectEvt ie) {
                // 임대인: 가장 더러운 수거장이 임계 이상이면 민원 1건
                double worst = 0;
                for (int t = 0; t < nT; t++) {
                    double cap = types.get(t).getCapacity();
                    if (cap > 0) worst = Math.max(worst, fill[ie.building][t] / cap);
                }
                if (worst >= cfg.getLandlordThreshold()) {
                    total++;
                    byOcc.merge(LANDLORD, 1, Integer::sum);
                    byDay.merge(ie.day, 1, Integer::sum);
                }
            }
        }

        double maxPeak = 0;
        for (double[] row : peak) for (double v : row) maxPeak = Math.max(maxPeak, v);
        return new SimulationResult(
                cfg.getCollectionTimeLabel(), total, byOcc, byDay,
                Math.round(maxPeak * 100.0) / 100.0, seed);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private static void offerDischarge(PriorityQueue<Evt> pq, int time, OccupationType occ,
                                       int building, int day, double amount, int totalMinutes) {
        if (time <= totalMinutes) pq.offer(new DischargeEvt(time, occ, building, day, amount));
    }

    /** 트럭이 그 날 순회하는가 (공휴일/주말 제외) */
    private static boolean isTruckDay(int day, SimulationConfig cfg) {
        if (cfg.getHolidays() != null && cfg.getHolidays().contains(day)) return false;
        if (cfg.isSkipWeekends() && isWeekend(day)) return false;
        return true;
    }

    private static boolean isWeekend(int day) {
        int dow = day % 7;          // 0=월 … 5=토, 6=일
        return dow == 5 || dow == 6;
    }

    /** 해당 날의 수거 시각 슬롯 */
    private static List<Integer> daySlots(int day, SimulationConfig cfg) {
        if (isWeekend(day) && cfg.getWeekendCollectionTimeMinutes() != null) {
            return Collections.singletonList(cfg.getWeekendCollectionTimeMinutes());
        }
        return cfg.resolveCollectionSlots();
    }

    private int sampleOffset(Random rng, int meanMinutes, double sigma) {
        int t = (int) Math.round(rng.nextGaussian() * sigma + meanMinutes);
        return Math.max(0, Math.min(1439, t));
    }

    private double sampleWaste(Random rng, double wasteSigma) {
        return Math.max(0.0, rng.nextGaussian() * wasteSigma + 0.9);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
