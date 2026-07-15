package com.wastesim.simulation;

import com.wastesim.model.OccupationType;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TruckType;
import com.wastesim.model.WasteType;
import com.wastesim.service.TrafficDataService;
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
 *  - 교통 레이어(TRAFFIC_EXTENSION_DESIGN.md §4): trafficEnabled=true일 때만
 *    작동 — 이동시간에 차종 기동성·시간대 혼잡 가중을 반영하고, 경로 순서·
 *    시차 배차를 지원하며, 정체(RED) 통과 시 별도 교통 민원을 집계한다.
 *    trafficEnabled=false면 기존 동작과 완전히 동일하다(하위호환).
 */
@Component
public class SimulationEngine {

    static final int DAY = 1440;
    static final String LANDLORD = "Landlord";
    static final String TRAFFIC = "Traffic";

    private final TrafficDataService trafficData;

    public SimulationEngine(TrafficDataService trafficData) {
        this.trafficData = trafficData;
    }

    /** 건물 인덱스 → 노드 id(예: 0→"Node_A"). 최대 26개 건물까지 지원. */
    public static String nodeId(int buildingIndex) {
        return "Node_" + (char) ('A' + buildingIndex);
    }

    /** 노드 id → 건물 인덱스. 형식이 잘못됐으면 -1. */
    public static int nodeIndex(String nodeId) {
        if (nodeId == null || !nodeId.matches("(?i)Node_[A-Za-z]")) return -1;
        return Character.toUpperCase(nodeId.charAt(5)) - 'A';
    }

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

        // 트럭별 구역(건물) 배정 — routeSequence가 있으면 그 순서로, 없으면 자연 순서로
        // round-robin 배정(§4-2).
        int numTrucks = Math.max(1, cfg.getNumTrucks());
        List<List<Integer>> routes = new ArrayList<>();
        for (int i = 0; i < numTrucks; i++) routes.add(new ArrayList<>());
        List<Integer> visitOrder = resolveVisitOrder(cfg, nB);
        for (int i = 0; i < visitOrder.size(); i++) routes.get(i % numTrucks).add(visitOrder.get(i));
        int travel = cfg.getRouteTravelMinutes();

        // 교통 레이어: trafficEnabled일 때만 프로파일을 조회한다(§4-1,4-4,4-5).
        // 차종 기동성(mobilityFactor)은 기본값(LARGE_5TON=1.0)이 중립이라 항상
        // 적용해도 trafficEnabled=false인 기존 시나리오와 결과가 동일하다.
        TruckType truckType = TruckType.fromName(cfg.getTruckType());
        TrafficProfile trafficProfile = cfg.isTrafficEnabled()
                ? trafficData.find(cfg.getTrafficProfileId()) : null;
        double trafficComplaintAccum = 0;
        long completionSum = 0;
        int completionCount = 0;

        PriorityQueue<Evt> pq = new PriorityQueue<>();

        // ── 수거 이벤트 생성 ──────────────────────────────────────────────
        for (int d = 0; d < days; d++) {
            if (!isTruckDay(d, cfg)) continue;
            List<Integer> slots = daySlots(d, cfg);
            for (int k = 0; k < routes.size(); k++) {
                List<Integer> route = routes.get(k);
                for (int slot : slots) {
                    int truckSlot = slot + k * cfg.getDispatchIntervalMinutes();
                    int arrival = d * DAY + truckSlot;
                    for (int pos = 0; pos < route.size(); pos++) {
                        int b = route.get(pos);
                        if (pos > 0) {
                            double effTravel = travel / truckType.mobilityFactor;
                            if (trafficProfile != null) {
                                String node = nodeId(b);
                                int minuteOfDay = arrival % DAY;
                                effTravel *= trafficProfile.weightAt(minuteOfDay, node);
                                if (trafficProfile.isRed(minuteOfDay, node)) {
                                    trafficComplaintAccum += cfg.getTrafficComplaintWeight();
                                }
                            }
                            arrival += (int) Math.round(effTravel);
                        }
                        if (arrival <= totalMinutes) pq.offer(new CollectEvt(arrival, b, d));
                    }
                    completionSum += (arrival - (d * DAY + truckSlot));
                    completionCount++;
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
        int nMonths = Math.max(1, (days + 29) / 30);   // 30일 = 1달
        double[] wasteByMonth = new double[nMonths];
        List<OccupationType> mix = cfg.resolveOccupationMix();
        int rPB = cfg.getResidentsPerBuilding();
        boolean retDis = cfg.isReturnDischarge();
        double retFrac = clamp01(cfg.getReturnFraction());
        for (int b = 0; b < nB; b++) {
            for (int k = 0; k < rPB; k++) {
                OccupationType occ = mix.get((b * rPB + k) % mix.size());
                for (int d = 0; d < days; d++) {
                    int monthIdx = d / 30;
                    double dailyAmount = sampleWaste(rng, cfg.getWasteSigma()) * cfg.resolveMonthlyFactor(monthIdx);
                    wasteByMonth[monthIdx] += dailyAmount;
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

        // 교통 유발 민원(§4-5): RED 구간 통과 누적치를 총 민원에 합산하고
        // "Traffic" 채널로 별도 노출한다. trafficProfile==null이면 0.
        int trafficComplaints = (int) Math.round(trafficComplaintAccum);
        if (trafficComplaints > 0) {
            total += trafficComplaints;
            byOcc.merge(TRAFFIC, trafficComplaints, Integer::sum);
        }

        double maxPeak = 0;
        for (double[] row : peak) for (double v : row) maxPeak = Math.max(maxPeak, v);
        SimulationResult result = new SimulationResult(
                cfg.getCollectionTimeLabel(), total, byOcc, byDay,
                Math.round(maxPeak * 100.0) / 100.0, seed);

        Map<Integer, Double> wasteMap = new TreeMap<>();
        for (int m = 0; m < nMonths; m++) wasteMap.put(m, Math.round(wasteByMonth[m] * 10.0) / 10.0);
        result.setWasteByMonth(wasteMap);
        result.setTrafficComplaints(trafficComplaints);
        result.setAvgCompletionMinutes(
                completionCount > 0 ? Math.round(completionSum * 10.0 / completionCount) / 10.0 : 0);
        return result;
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /**
     * 건물 방문 순서(인덱스 목록). routeSequence가 유효하면 그 순서, 아니면
     * 자연 순서(0..nB-1). 검증기(V-T4)가 실행 전 이미 routeSequence의
     * 유효성(건물 집합과 정확히 일치)을 확인하므로, 여기서는 방어적으로만
     * 재검증하고 이상하면 자연 순서로 폴백한다.
     */
    private static List<Integer> resolveVisitOrder(SimulationConfig cfg, int nB) {
        List<Integer> natural = new ArrayList<>();
        for (int b = 0; b < nB; b++) natural.add(b);

        List<String> seq = cfg.getRouteSequence();
        if (seq == null || seq.isEmpty()) return natural;

        List<Integer> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String s : seq) {
            int idx = nodeIndex(s);
            if (idx < 0 || idx >= nB || !seen.add(idx)) return natural;   // 이상하면 폴백
            out.add(idx);
        }
        return out.size() == nB ? out : natural;
    }

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
