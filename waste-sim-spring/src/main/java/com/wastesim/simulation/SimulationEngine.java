package com.wastesim.simulation;

import com.wastesim.model.DischargeTimeMode;
import com.wastesim.model.OccupationType;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TripMetric;
import com.wastesim.model.TruckType;
import com.wastesim.model.WasteType;

import static com.wastesim.util.Round.round2;
import com.wastesim.model.TravelTimeMode;
import com.wastesim.service.TrafficDataService;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.traffic.TravelTimeMatrix;
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
    /** 부동소수 비교용 허용 오차(용량/수거량 kg 단위). */
    static final double EPS = 1e-9;

    private final TrafficDataService trafficData;
    private final CollectionSiteRegistry sites;
    private final TravelTimeMatrix travelTimes;
    private final TravelTimeMatrix zoneTravelTimes;

    @org.springframework.beans.factory.annotation.Autowired
    public SimulationEngine(TrafficDataService trafficData, CollectionSiteRegistry sites,
                            TravelTimeMatrix travelTimes,
                            com.wastesim.traffic.ZoneTravelTimeMatrix zoneTravelTimes) {
        this.trafficData = trafficData;
        this.sites = sites;
        this.travelTimes = travelTimes;
        this.zoneTravelTimes = zoneTravelTimes;
    }

    /** 구역 간 행렬을 따로 주지 않으면 기본 구역 행렬을 읽는다. */
    public SimulationEngine(TrafficDataService trafficData, CollectionSiteRegistry sites,
                            TravelTimeMatrix travelTimes) {
        this.trafficData = trafficData;
        this.sites = sites;
        this.travelTimes = travelTimes;
        this.zoneTravelTimes = TravelTimeMatrix.ofZones();
    }

    /**
     * 수거 지점 정보와 자유주행시간 없이 만드는 엔진. 상수 모드로만 돌 수 있고, 교통 구역은
     * 지점 id를 그대로 쓴다 — 즉 이 엔진의 결과는 v1.12까지와 완전히 같다.
     */
    public SimulationEngine(TrafficDataService trafficData) {
        this(trafficData, CollectionSiteRegistry.empty(), TravelTimeMatrix.empty());
    }

    /** 건물 인덱스 → 노드 id(예: 0→"Node_A"). 최대 26개 건물까지 지원. */
    public static String nodeId(int buildingIndex) {
        return "Node_" + (char) ('A' + buildingIndex);
    }

    /**
     * 이 구간에 걸리는 분. 모드에 따라 갈린다.
     *
     * <p>혼합 모드인데 자유주행시간이 없으면 상수로 되돌리지 않고 예외를 던진다 — 검증기가
     * 이미 막았어야 하는 상태이고(V-T6), 여기서 조용히 되돌리면 무엇으로 계산한 결과인지
     * 구별할 수 없게 된다.
     */
    private int hopMinutes(SimulationConfig cfg, String from, String to,
                           int baseMinutes, double mobilityFactor, double congestionWeight) {
        TravelTimeMode mode = cfg.resolveTravelTimeMode();
        if (mode == TravelTimeMode.LEGACY_CONSTANT) {
            return TravelTimeCalculator.hopMinutes(baseMinutes, mobilityFactor, congestionWeight);
        }
        if (mode == TravelTimeMode.ZONE_PROXY_HYBRID) {
            return zoneProxyHopMinutes(cfg, from, to, mobilityFactor, congestionWeight);
        }
        java.util.OptionalDouble ff = travelTimes.freeFlowSeconds(from, to);
        if (ff.isEmpty()) {
            throw new IllegalStateException("OSRM_HYBRID인데 " + from + "->" + to
                    + " 구간의 자유주행시간이 없습니다. 검증에서 걸러졌어야 하는 상태입니다.");
        }
        return TravelTimeCalculator.hopMinutesFromFreeFlow(ff.getAsDouble(), mobilityFactor,
                congestionWeight, cfg.getServiceMinutesPerSite());
    }

    /**
     * 구역 근사 모드의 한 구간. 두 지점이 <b>같은 구역인지</b>로 갈린다.
     *
     * <p>구역이 다르면 구역 간 실측 주행시간에 시간대 혼잡을 곱하고, 같으면
     * {@code intraZoneTravelMinutes}를 쓴다 — 구역 간 행렬에 대각 성분이 없기 때문이다.
     * 구역 내 이동에는 혼잡을 곱하지 않는다. 그 값 자체가 이미 "구역 안에서 평균적으로
     * 이만큼 걸린다"는 통짜 가정이어서, 여기에 다시 시간대 배수를 얹으면 측정하지 않은
     * 값에 측정하지 않은 보정을 곱하는 셈이 된다.
     *
     * <p>어느 쪽이든 도착 지점의 정차·상차 시간을 더한다.
     */
    private int zoneProxyHopMinutes(SimulationConfig cfg, String from, String to,
                                    double mobilityFactor, double congestionWeight) {
        String fromZone = zoneOf(from);
        String toZone = zoneOf(to);
        if (fromZone.equals(toZone)) {
            return cfg.getIntraZoneTravelMinutes() + cfg.getServiceMinutesPerSite();
        }
        java.util.OptionalDouble ff = zoneTravelTimes.freeFlowSeconds(fromZone, toZone);
        if (ff.isEmpty()) {
            throw new IllegalStateException("ZONE_PROXY_HYBRID인데 구역 " + fromZone + "->" + toZone
                    + " 의 자유주행시간이 없습니다. 검증에서 걸러졌어야 하는 상태입니다.");
        }
        return TravelTimeCalculator.hopMinutesFromFreeFlow(ff.getAsDouble(), mobilityFactor,
                congestionWeight, cfg.getServiceMinutesPerSite());
    }

    /**
     * 이 수거 지점이 속한 교통 구역. 매핑이 없으면 지점 id를 그대로 구역 id로 본다 — 두
     * 이름공간이 겹쳐 있던 시절의 동작이며, 수거 지점을 등록하기 전에도 기본 4개 건물
     * (Node_A~D)이 이름이 같은 구역 4곳으로 해석되게 해 준다.
     */
    private String zoneOf(String siteId) {
        return sites.trafficZoneOf(siteId).orElse(siteId);
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
            if (c != 0) return c;
            c = Integer.compare(this.priority(), o.priority());
            return c != 0 ? c : Integer.compare(this.tieBreaker(), o.tieBreaker());
        }
        int priority() { return 1; }
        int tieBreaker() { return 0; }
    }

    /** 수거: 트럭이 건물 b를 day d에 방문해 수거(due 종류만 비움) */
    static class CollectEvt extends Evt {
        final int building, day, tripId, stopOrder;
        CollectEvt(int time, int building, int day, int tripId, int stopOrder) {
            super(time);
            this.building = building;
            this.day = day;
            this.tripId = tripId;
            this.stopOrder = stopOrder;
        }
        @Override int priority() { return 0; }   // 같은 시각이면 수거 먼저
        @Override int tieBreaker() { return tripId * 100 + stopOrder; }
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
        double routeCapacityKg = cfg.resolveRouteCapacityKg(truckType.capacityKg);
        double initialTruckLoadKg = cfg.getInitialTruckLoadKg();
        double pickupCapacityKg = cfg.resolvePickupCapacityKg(truckType.capacityKg);
        TrafficProfile trafficProfile = cfg.isTrafficEnabled()
                ? trafficData.find(cfg.getTrafficProfileId()) : null;
        double trafficComplaintAccum = 0;
        long completionSum = 0;
        int completionCount = 0;

        PriorityQueue<Evt> pq = new PriorityQueue<>();
        // 운행(trip)마다 적재용량을 새로 부여한다. 같은 운행 안에서는 여러 수거장을
        // 방문하면서 남은 용량이 감소하고, 다음 트럭/다음 수거 슬롯과는 공유하지 않는다.
        Map<Integer, Double> remainingTruckCapacity = new HashMap<>();
        double totalRouteCapacityKg = 0.0;
        double totalInitialTruckLoadKg = 0.0;
        double totalAvailableCollectionCapacityKg = 0.0;
        int nextTripId = 0;

        // 용량 소진·부분수거 진단(§3.3)
        int partialPickupCount = 0;      // 일부만 수거한 방문
        int unservedPickupCount = 0;     // 잔여 용량 없어 전혀 못 한 방문
        double uncollectedDemandKg = 0.0;// 수거 시점 용량 부족으로 남긴 수요(kg)
        Set<Integer> exhaustedTrips = new HashSet<>();   // 적재용량을 모두 쓴 운행
        // 운행별 상세(§3.4) — tripId → 누적기. 병목 트럭·경로 식별용.
        Map<Integer, TripAcc> tripAccs = new LinkedHashMap<>();

        // ── 수거 이벤트 생성 ──────────────────────────────────────────────
        for (int d = 0; d < days; d++) {
            if (!isTruckDay(d, cfg, types)) continue;
            List<Integer> slots = daySlots(d, cfg);
            for (int k = 0; k < routes.size(); k++) {
                List<Integer> route = routes.get(k);
                // 방문 노드가 없는 경로(트럭 수 > 건물 수일 때 발생)는 운행 이벤트를
                // 만들지 않는다. 빈 운행까지 집계하면 배정용량·초기적재·완료시간
                // 분모가 늘어나 트럭 이용률이 실제보다 낮게 왜곡된다(§3.1).
                if (route.isEmpty()) continue;
                for (int si = 0; si < slots.size(); si++) {
                    int slot = slots.get(si);
                    int tripId = nextTripId++;
                    remainingTruckCapacity.put(tripId, pickupCapacityKg);
                    totalRouteCapacityKg += routeCapacityKg;
                    totalInitialTruckLoadKg += initialTruckLoadKg;
                    totalAvailableCollectionCapacityKg += pickupCapacityKg;
                    String truckId = "T" + (k + 1);
                    tripAccs.put(tripId, new TripAcc(truckId, truckId + "-D" + d + "-S" + si,
                            routeCapacityKg, initialTruckLoadKg, pickupCapacityKg));
                    int truckSlot = slot + k * cfg.getDispatchIntervalMinutes();
                    int arrival = d * DAY + truckSlot;
                    for (int pos = 0; pos < route.size(); pos++) {
                        int b = route.get(pos);
                        if (pos > 0) {
                            String site = nodeId(b);
                            double congestionWeight = 1.0;
                            if (trafficProfile != null) {
                                // 혼잡은 "그 지점이 속한 교통 구역"의 값이다. 매핑이 없으면
                                // 지점 id를 그대로 구역 id로 본다 — 두 이름공간이 겹쳐 있던
                                // 시절의 동작이며, 매핑을 채우기 전까지 결과를 그대로 유지한다.
                                String zone = zoneOf(site);
                                int minuteOfDay = arrival % DAY;
                                congestionWeight = trafficProfile.weightAt(minuteOfDay, zone);
                                if (trafficProfile.isRed(minuteOfDay, zone)) {
                                    trafficComplaintAccum += cfg.getTrafficComplaintWeight();
                                }
                            }
                            // 공식은 TravelTimeCalculator에 고정 — RouteDurationEstimator(경로
                            // 소요시간 단독 질의)와 동일 공식을 공유해 드리프트를 막는다.
                            arrival += hopMinutes(cfg, nodeId(route.get(pos - 1)), site,
                                    travel, truckType.mobilityFactor, congestionWeight);
                        }
                        if (arrival <= totalMinutes) pq.offer(new CollectEvt(arrival, b, d, tripId, pos));
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
                    double dailyAmount = sampleWaste(rng, cfg.getWasteSigma(), cfg.getWasteMeanKg()) * cfg.resolveMonthlyFactor(monthIdx);
                    wasteByMonth[monthIdx] += dailyAmount;
                    int leaveT = d * DAY + dischargeOffset(rng, cfg, occ.leaveMeanMinutes);
                    if (retDis) {
                        int retT = d * DAY + dischargeOffset(rng, cfg, occ.returnMeanMinutes);
                        offerDischarge(pq, leaveT, occ, b, d, dailyAmount * (1.0 - retFrac), totalMinutes);
                        offerDischarge(pq, retT,   occ, b, d, dailyAmount * retFrac,         totalMinutes);
                    } else {
                        offerDischarge(pq, leaveT, occ, b, d, dailyAmount, totalMinutes);
                    }
                }
            }
        }

        // ── 집계 ──────────────────────────────────────────────────────────
        int wasteOverflowComplaints = 0;
        int landlordComplaints = 0;
        double collectedWasteKg = 0.0;
        Map<String, Integer> byOcc = new LinkedHashMap<>();
        // 실제 거주 중인 직업군은 이번 시드에서 민원이 0건이어도 반드시 키를
        // 남긴다 — 아래에서는 merge()가 민원이 실제로 발생했을 때만 값을
        // 채우므로, 미리 0으로 깔아두지 않으면 그 직업군은 이번 시드에서
        // 아예 집계에서 빠진다. 그러면 SimulationService.runExperiment()가
        // 여러 시드를 모을 때도 그 직업군은 민원이 있었던 시드만으로 평균을
        // 내(0인 시드가 분모에서 빠짐) 실제보다 높게 나오고, UI에도 그
        // 직업군 행 자체가 사라져 보인다(실측: 라이브 채팅에서 생산직 행이
        // 통째로 안 보이는 것으로 재현됨).
        for (OccupationType t : mix) byOcc.putIfAbsent(t.name(), 0);
        Map<Integer, Integer> byDay = new TreeMap<>();

        while (!pq.isEmpty()) {
            Evt e = pq.poll();
            if (e.time > totalMinutes) break;

            if (e instanceof CollectEvt ce) {
                // 분리배출 수거 주기: due 종류만 수거한다. 트럭 잔여용량이 부족하면
                // 해당 건물의 due 폐기물들을 현재 적재량에 비례해 부분 수거한다.
                double dueTotal = 0.0;
                for (int t = 0; t < nT; t++) {
                    if (isTypeDue(ce.day, types.get(t))) dueTotal += fill[ce.building][t];
                }
                if (dueTotal > EPS) {
                    double remaining = remainingTruckCapacity.getOrDefault(ce.tripId, 0.0);
                    double collected = Math.min(dueTotal, remaining);
                    TripAcc acc = tripAccs.get(ce.tripId);
                    if (collected > EPS) {
                        double keepFraction = 1.0 - collected / dueTotal;
                        for (int t = 0; t < nT; t++) {
                            if (isTypeDue(ce.day, types.get(t))) {
                                fill[ce.building][t] *= keepFraction;
                            }
                        }
                        double newRemaining = remaining - collected;
                        remainingTruckCapacity.put(ce.tripId, newRemaining);
                        collectedWasteKg += collected;
                        if (acc != null) acc.collectedKg += collected;
                        if (newRemaining <= EPS) exhaustedTrips.add(ce.tripId);
                    }
                    // 이번 방문에서 용량 부족으로 남긴 수요(§3.3). 같은 폐기물이 다음
                    // 운행에서 재수거될 수 있어 종료 잔류량(residualWasteKg)과는 별개다.
                    double shortfall = dueTotal - collected;
                    if (shortfall > EPS) {
                        uncollectedDemandKg += shortfall;
                        if (collected > EPS) {
                            partialPickupCount++;                    // 일부만 수거
                            if (acc != null) acc.partialPickupCount++;
                        } else {
                            unservedPickupCount++;                   // 전혀 못 함
                        }
                    }
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
                    wasteOverflowComplaints++;
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
                    landlordComplaints++;
                    byOcc.merge(LANDLORD, 1, Integer::sum);
                    byDay.merge(ie.day, 1, Integer::sum);
                }
            }
        }

        // 교통 패널티는 생활쓰레기 민원과 단위가 다르므로 totalComplaints와
        // byOccupation에 합산하지 않는다. 소수 가중치도 반올림하지 않고 보존한다.
        double trafficPenalty = Math.round(trafficComplaintAccum * 100.0) / 100.0;
        int total = wasteOverflowComplaints + landlordComplaints;

        double maxPeak = 0;
        for (double[] row : peak) for (double v : row) maxPeak = Math.max(maxPeak, v);
        double generatedWasteKg = 0.0;
        for (double v : wasteByMonth) generatedWasteKg += v;
        double residualWasteKg = 0.0;
        for (double[] row : fill) for (double v : row) residualWasteKg += v;

        // P4(§3.5): 잔류량 분포 — 건물별·유형별·트럭별, 그리고 최대 잔류 건물.
        double[] buildingResidual = new double[nB];
        Map<String, Double> residualByBuilding = new LinkedHashMap<>();
        for (int b = 0; b < nB; b++) {
            double sum = 0.0;
            for (int t = 0; t < nT; t++) sum += fill[b][t];
            buildingResidual[b] = sum;
            residualByBuilding.put(nodeId(b), round2(sum));
        }
        Map<String, Double> typeRaw = new LinkedHashMap<>();
        for (int t = 0; t < nT; t++) {
            double sum = 0.0;
            for (int b = 0; b < nB; b++) sum += fill[b][t];
            typeRaw.merge(types.get(t).getKey(), sum, Double::sum);
        }
        Map<String, Double> residualByWasteType = new LinkedHashMap<>();
        typeRaw.forEach((key, v) -> residualByWasteType.put(key, round2(v)));
        String maxResidualBuilding = null;
        double maxResidualBuildingKg = 0.0;
        for (int b = 0; b < nB; b++) {
            if (buildingResidual[b] > maxResidualBuildingKg) {
                maxResidualBuildingKg = buildingResidual[b];
                maxResidualBuilding = nodeId(b);
            }
        }
        // 트럭(경로)별 미수거량 — 빈 경로는 제외(P1). 각 건물은 정확히 한 트럭에 배정된다.
        Map<String, Double> residualByTruck = new LinkedHashMap<>();
        for (int k = 0; k < routes.size(); k++) {
            List<Integer> route = routes.get(k);
            if (route.isEmpty()) continue;
            double sum = 0.0;
            for (int b : route) sum += buildingResidual[b];
            residualByTruck.put("T" + (k + 1), round2(sum));
        }
        double truckUtilizationPercent = totalRouteCapacityKg > 0
                ? (totalInitialTruckLoadKg + collectedWasteKg) / totalRouteCapacityKg * 100.0 : 0.0;
        double collectionCapacityUtilizationPercent = totalAvailableCollectionCapacityKg > 0
                ? collectedWasteKg / totalAvailableCollectionCapacityKg * 100.0 : 0.0;
        // 질량보존(§4.3): 내부값은 원본 정밀도로 계산하고 표시 단계에서만 반올림한다.
        double massBalanceErrorKg = generatedWasteKg - collectedWasteKg - residualWasteKg;
        SimulationResult result = new SimulationResult(
                cfg.getCollectionTimeLabel(), total, byOcc, byDay,
                Math.round(maxPeak * 100.0) / 100.0, seed);

        Map<Integer, Double> wasteMap = new TreeMap<>();
        for (int m = 0; m < nMonths; m++) wasteMap.put(m, Math.round(wasteByMonth[m] * 10.0) / 10.0);
        result.setWasteByMonth(wasteMap);
        result.setWasteOverflowComplaints(wasteOverflowComplaints);
        result.setLandlordComplaints(landlordComplaints);
        result.setTrafficPenalty(trafficPenalty);
        result.setGeneratedWasteKg(round2(generatedWasteKg));
        result.setCollectedWasteKg(round2(collectedWasteKg));
        result.setResidualWasteKg(round2(residualWasteKg));
        result.setAvailableCollectionCapacityKg(round2(totalAvailableCollectionCapacityKg));
        result.setTruckUtilizationPercent(round2(truckUtilizationPercent));
        result.setCollectionCapacityUtilizationPercent(round2(collectionCapacityUtilizationPercent));
        result.setPartialPickupCount(partialPickupCount);
        result.setUnservedPickupCount(unservedPickupCount);
        result.setCapacityExhaustedTripCount(exhaustedTrips.size());
        result.setUncollectedDemandKg(round2(uncollectedDemandKg));
        result.setMassBalanceErrorKg(round2(massBalanceErrorKg));
        result.setCoordinateQuality(cfg.resolveTravelTimeMode().coordinateQuality());
        result.setTripMetrics(buildTripMetrics(tripAccs));
        result.setResidualByBuilding(residualByBuilding);
        result.setResidualByWasteType(residualByWasteType);
        result.setResidualByTruck(residualByTruck);
        result.setMaxResidualBuilding(maxResidualBuilding);
        result.setMaxResidualBuildingKg(round2(maxResidualBuildingKg));
        result.setAvgCompletionMinutes(
                completionCount > 0 ? Math.round(completionSum * 10.0 / completionCount) / 10.0 : 0);
        return result;
    }

    /** 운행별 누적기(§3.4) — 구조(용량·초기적재)는 시드와 무관, 수거량만 시드마다 다르다. */
    static final class TripAcc {
        final String truckId, tripId;
        final double allocatedCapacityKg, initialLoadKg, availablePickupCapacityKg;
        double collectedKg = 0.0;
        int partialPickupCount = 0;
        TripAcc(String truckId, String tripId, double allocated, double initial, double available) {
            this.truckId = truckId;
            this.tripId = tripId;
            this.allocatedCapacityKg = allocated;
            this.initialLoadKg = initial;
            this.availablePickupCapacityKg = available;
        }
    }

    private static List<TripMetric> buildTripMetrics(Map<Integer, TripAcc> accs) {
        List<TripMetric> out = new ArrayList<>(accs.size());
        for (TripAcc a : accs.values()) {
            double finalLoad = a.initialLoadKg + a.collectedKg;
            double unused = a.allocatedCapacityKg - finalLoad;
            double util = a.allocatedCapacityKg > 0 ? finalLoad / a.allocatedCapacityKg * 100.0 : 0.0;
            out.add(new TripMetric(a.truckId, a.tripId,
                    round2(a.allocatedCapacityKg), round2(a.initialLoadKg), round2(a.availablePickupCapacityKg),
                    round2(a.collectedKg), round2(finalLoad), round2(unused), round2(util), a.partialPickupCount));
        }
        return out;
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

    /**
     * 트럭이 그 날 순회하는가.
     *
     * <p>공휴일·주말 외에 <b>수거 주기</b>도 본다. 예전에는 이 두 가지만 검사해서, 격일
     * 수거로 설정해도 차량은 매일 돌면서 실제로는 아무 용기도 비우지 않는 날이 생겼다 —
     * 그 날의 교통 민원과 이동 시간이 결과에 그대로 쌓여, 주기를 1·2·4일로 바꿔도 교통
     * 민원이 똑같이 나왔다. 전역 {@code collectionIntervalDays}는 아예 읽히지도 않았다.
     *
     * <p><b>전역 주기와 유형별 주기의 결합 규칙</b>: 전역 주기는 "차량이 며칠에 한 번
     * 오는가"이고, 유형별 주기는 "그 유형을 며칠에 한 번 비우는가"다. 차량이 오지 않는
     * 날에는 어떤 유형도 비울 수 없으므로 전역 주기가 먼저 걸리고, 차량이 오는 날이라도
     * 비울 유형이 하나도 없으면 운행할 이유가 없다. 즉 <b>둘 다 만족해야</b> 운행한다.
     *
     * <p>기본값(전역 1일, 기본 유형 1일)에서는 두 조건이 항상 참이라 기존 결과와 완전히
     * 같다. 유형을 지정하지 않으면 {@code resolveWasteTypes()}가 전역 주기를 그대로 물려준
     * 단일 유형을 만들므로, 그 경우에도 두 조건이 같은 날에 맞아떨어진다.
     */
    private static boolean isTruckDay(int day, SimulationConfig cfg, List<WasteType> types) {
        if (cfg.getHolidays() != null && cfg.getHolidays().contains(day)) return false;
        if (cfg.isSkipWeekends() && isWeekend(day)) return false;

        // 전역 스케줄 — 요일 집합을 지정했으면 그것이 주기를 대신한다(둘을 함께 쓰는 설정은
        // 검증기가 거부한다). 미수거일은 그 집합에서 빠지는 것으로 표현된다.
        if (cfg.usesDaysOfWeek()) {
            if (!cfg.getCollectionDaysOfWeek().contains(dayOfWeek(day))) return false;
        } else if (day % Math.max(1, cfg.getCollectionIntervalDays()) != 0) {
            return false;
        }

        // 종류별 스케줄 — 하나라도 오늘 수거하는 종류가 있으면 트럭이 나간다.
        for (WasteType t : types) {
            if (isTypeDue(day, t)) return true;
        }
        return false;
    }

    /**
     * 이 종류를 오늘 수거하는가.
     *
     * <p><b>세 자리가 같은 규칙을 써야 한다</b> — 트럭이 나가는지({@link #isTruckDay}),
     * 도착해서 어느 종류를 비우는지(수거 이벤트의 due 집계), 부분 수거 때 어느 종류를
     * 줄이는지. 처음 요일 집합을 넣을 때 첫 자리만 고치고 나머지 둘을 {@code intervalDays}로
     * 남겨 뒀는데, 그러면 <b>트럭은 나가지만 아무것도 비우지 않는다</b> — 수거가 일어난 것처럼
     * 보이면서 쓰레기는 계속 쌓여 민원이 두 배로 나왔다. 테스트가 그것을 잡았다.
     *
     * <p>요일 집합을 지정한 종류는 그 요일로만 판정하고, 지정하지 않은 종류는 주기로 판정한다.
     */
    private static boolean isTypeDue(int day, WasteType t) {
        if (t.usesDaysOfWeek()) {
            return t.getCollectionDaysOfWeek().contains(dayOfWeek(day));
        }
        return day % Math.max(1, t.getIntervalDays()) == 0;
    }

    /**
     * 요일 — <b>0=월 1=화 2=수 3=목 4=금 5=토 6=일</b>. 즉 시뮬레이션 0일차가 월요일이다.
     *
     * <p>이 규약은 {@code skipWeekends}·{@code weekendCollectionTimeMinutes}가 쓰던 것인데,
     * 요일 집합 스케줄이 들어오면서 <b>결과를 좌우하는 값</b>이 됐다 — 0일차를 다른 요일로
     * 보면 수거하는 날이 통째로 밀린다. 시작 요일을 설정으로 열지 않은 이유는 그러면 기존
     * 결과가 조용히 달라지기 때문이다.
     */
    static int dayOfWeek(int day) {
        return ((day % 7) + 7) % 7;
    }

    private static boolean isWeekend(int day) {
        int dow = dayOfWeek(day);   // 0=월 … 5=토, 6=일
        return dow == 5 || dow == 6;
    }

    /** 해당 날의 수거 시각 슬롯 */
    private static List<Integer> daySlots(int day, SimulationConfig cfg) {
        if (isWeekend(day) && cfg.getWeekendCollectionTimeMinutes() != null) {
            return Collections.singletonList(cfg.getWeekendCollectionTimeMinutes());
        }
        return cfg.resolveCollectionSlots();
    }

    /**
     * 이 배출 한 건이 일어나는 시각(그날 0시 기준 분).
     *
     * <p>논문 모델은 직업별 외출·귀가 시각 ± {@code leaveSigma}다. 포항시 실제 규정 모드는
     * 배출 허용 창 안에서 <b>균등</b>하게 뽑는다 — 공식 데이터가 창만 주고 분포를 주지
     * 않으므로, 창만 아는 상태에서 가장 적은 가정을 얹는 선택이다.
     *
     * <p>돌려주는 값이 1440을 넘을 수 있다. 창이 자정을 넘기 때문이다(20:00 시작, 600분
     * 길이면 최대 1799 = 다음 날 05:59). 호출부가 {@code d * DAY}에 더하므로 그 배출은
     * 자연히 다음 날로 넘어가고, 요일 기반 수거 스케줄과도 그렇게 맞물린다 — "일요일 밤에
     * 내놓고 월요일 새벽에 수거된다"가 바로 이 동작이다.
     *
     * <p><b>이 모드에서 {@code meanMinutes}는 쓰이지 않는다.</b> 즉 직업이 배출 시각에
     * 영향을 주지 않는다({@link com.wastesim.model.DischargeTimeMode#POHANG_ACTUAL} javadoc).
     */
    private int dischargeOffset(Random rng, SimulationConfig cfg, int meanMinutes) {
        if (cfg.resolveDischargeTimeMode() == DischargeTimeMode.POHANG_ACTUAL) {
            int span = Math.max(1, cfg.dischargeWindowSpanMinutes());
            return cfg.getDischargeWindowStartMinutes() + rng.nextInt(span);
        }
        return sampleOffset(rng, meanMinutes, cfg.getLeaveSigma());
    }

    private int sampleOffset(Random rng, int meanMinutes, double sigma) {
        int t = (int) Math.round(rng.nextGaussian() * sigma + meanMinutes);
        return Math.max(0, Math.min(1439, t));
    }

    private double sampleWaste(Random rng, double wasteSigma, double wasteMeanKg) {
        return Math.max(0.0, rng.nextGaussian() * wasteSigma + wasteMeanKg);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
