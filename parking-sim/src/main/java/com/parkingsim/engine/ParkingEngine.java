package com.parkingsim.engine;

import com.parkingsim.model.GuidanceMode;
import com.parkingsim.model.ParkingConfig;
import com.parkingsim.model.PricingPolicy;
import com.parkingsim.model.UserType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 공영주차장 탐색 · 혼잡 이산사건 시뮬레이션 — 시드 하나, 기간 전체.
 *
 * <p>주차장은 한 줄로 늘어서 있다. 차량은 하루 중 유형별 시각에 목적지 옆 주차장에 도착한다.
 * 빈자리가 있으면 주차하고, 없으면 가장 가까운 아직 안 가 본 주차장으로 옮긴다. 도착한 뒤
 * 탐색 포기 시간이 지나면 포기한다.
 *
 * <p>안내 방식이 탐색을 바꾼다.
 * <ul>
 *   <li>안내 없음 — 만차 주차장에 들어가 한 바퀴 돈 뒤에야 안다({@code fullLotCircleMinutes}).</li>
 *   <li>고정 안내판 — 입구에서 만차를 알아 돌지 않는다. 다음 주차장은 가 봐야 안다.</li>
 *   <li>실시간 앱 — 마지막 갱신 때 빈자리가 있던 가장 가까운 주차장으로 바로 간다.</li>
 * </ul>
 *
 * <p>가정 둘(카드 {@code quality.calibration} 에 {@code measured:false}):
 * <ul>
 *   <li>목적지는 1번 주차장(중심 상권) 쪽으로 몰린다 — i번 주차장 옆이 목적지일 가중치 {@code 1/(i+1)}.
 *       고르게 흩으면 모든 주차장이 같이 차서 어떤 안내도 도울 수 없다.</li>
 *   <li>실시간 앱 이용자는 도착 <b>전에</b> 목적지를 바꾼다 — 처음 옮기는 거리의 절반만 우회한다
 *       ({@link #APP_EN_ROUTE_FACTOR}). 주차장이 한 줄이라 그렇지 않으면 앱과 안내판이 같아진다.</li>
 * </ul>
 *
 * <p>같은 시각의 사건은 출차 → 안내 갱신 → 표본 → 도착 순으로 처리한다 — 같은 분에 빠진 자리를
 * 같은 분에 온 차가 쓸 수 있게.
 */
@Component
public class ParkingEngine {

    private static final int DEPART = 0, REFRESH = 1, SAMPLE = 2, ARRIVE = 3;
    private static final int SAMPLE_MINUTES = 10;
    /** 실시간 앱 이용자가 도착 전에 목적지를 바꿀 때 우회 거리 배수. */
    static final double APP_EN_ROUTE_FACTOR = 0.5;

    private final CongestionProfiles profiles;

    public ParkingEngine(CongestionProfiles profiles) {
        this.profiles = profiles;
    }

    private static final class Vehicle {
        final UserType type;
        final double start;
        final int stay;
        final boolean[] visited;
        boolean first = true;

        Vehicle(UserType type, double start, int stay, int lots) {
            this.type = type;
            this.start = start;
            this.stay = stay;
            this.visited = new boolean[lots];
        }
    }

    private record Event(double t, int kind, long seq, Vehicle v, int lot, boolean reservedSpot) {}

    public SeedResult run(ParkingConfig c, long seed) {
        Random rnd = new Random(seed);
        final int lots = c.getLotCount();
        final int[] spaces = new int[lots];
        final int[] reserved = new int[lots];
        final int[] general = new int[lots];
        final List<UserType> mix = c.resolveUserMix();
        final PricingPolicy pricing = c.resolvePricingPolicy();
        final GuidanceMode guidance = c.resolveGuidanceMode();
        final double share = c.residentsPresent() && c.getResidentPriorityShare() != null
                ? c.getResidentPriorityShare() : 0.0;
        for (int l = 0; l < lots; l++) {
            spaces[l] = c.spacesAt(l);
            reserved[l] = (int) Math.floor(spaces[l] * share);
            general[l] = spaces[l] - reserved[l];
        }
        final double[] mult = c.isCongestionEnabled()
                ? profiles.multipliers(c.getCongestionProfileId()).orElseThrow(
                        () -> new IllegalStateException("혼잡 프로파일이 없습니다: " + c.getCongestionProfileId()))
                : null;
        final int maxSearch = c.getMaxSearchMinutes();
        final int horizon = c.getDays() * 1440;

        int[] genOcc = new int[lots];
        int[] resOcc = new int[lots];
        boolean[] snapGeneral = new boolean[lots];
        boolean[] snapResident = new boolean[lots];
        java.util.Arrays.fill(snapGeneral, true);
        java.util.Arrays.fill(snapResident, true);

        Map<UserType, Integer> arrivalsByType = new EnumMap<>(UserType.class);
        Map<UserType, Integer> parkedByType = new EnumMap<>(UserType.class);
        Map<UserType, Integer> gaveUpByType = new EnumMap<>(UserType.class);
        Map<UserType, Double> searchByType = new EnumMap<>(UserType.class);
        for (UserType u : mix) {
            arrivalsByType.put(u, 0);
            parkedByType.put(u, 0);
            gaveUpByType.put(u, 0);
            searchByType.put(u, 0.0);
        }

        PriorityQueue<Event> pq = new PriorityQueue<>((a, b) -> {
            int cmp = Double.compare(a.t(), b.t());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.kind(), b.kind());
            return cmp != 0 ? cmp : Long.compare(a.seq(), b.seq());
        });
        long seq = 0;
        // Existing vehicles are not new arrivals and therefore not counted in vehicle balance or revenue.
        if (c.getParkingSnapshot() != null) {
            for (int l = 0; l < lots; l++) {
                genOcc[l] = c.getParkingSnapshot().lots().get(l).occupied();
                snapGeneral[l] = genOcc[l] < general[l];
                snapResident[l] = snapGeneral[l];
                for (int i = 0; i < genOcc[l]; i++)
                    pq.add(new Event(c.getInitialVehicleRemainingMinutes(), DEPART, seq++, null, l, false));
            }
        }

        int arrivals = 0, priceAvoided = 0;
        for (int d = 0; d < c.getDays() + (c.startMinute() > 0 ? 1 : 0); d++) {
            for (int i = 0; i < c.getDailyArrivals(); i++) {
                UserType type = mix.get(i % mix.size());
                double tod = type.uniformArrival
                        ? type.arrivalMeanMinutes + rnd.nextDouble() * type.arrivalSpreadMinutes
                        : type.arrivalMeanMinutes + rnd.nextGaussian() * type.arrivalSpreadMinutes;
                tod = Math.max(0, Math.min(1439, tod));
                double t = d * 1440.0 + tod - c.startMinute();
                if (t < 0 || t >= horizon) continue;
                arrivals++;
                arrivalsByType.merge(type, 1, Integer::sum);

                double fee = feePer30(c, pricing, hourOf(t + c.startMinute()));
                double pAvoid = Math.min(0.95, type.priceElasticity * fee / 1000.0);
                if (rnd.nextDouble() < pAvoid) {
                    priceAvoided++;
                    continue;
                }
                int dest = pickDestination(rnd, lots);
                int stay;
                if (type == UserType.Resident) {
                    double leave = (d + 1) * 1440.0 + 7 * 60 + 30 + rnd.nextGaussian() * 30 - c.startMinute();
                    stay = (int) Math.max(60, leave - t);
                } else {
                    stay = (int) Math.max(10, type.meanStayMinutes * (0.5 + rnd.nextDouble()));
                }
                Vehicle v = new Vehicle(type, t, stay, lots);
                pq.add(new Event(t, ARRIVE, seq++, v, dest, false));
            }
        }
        if (guidance == GuidanceMode.REALTIME_APP) {
            for (int t = 0; t < horizon; t += c.getGuidanceRefreshMinutes()) {
                pq.add(new Event(t, REFRESH, seq++, null, -1, false));
            }
        }
        for (int t = 0; t < horizon; t += SAMPLE_MINUTES) {
            pq.add(new Event(t, SAMPLE, seq++, null, -1, false));
        }

        int parked = 0, gaveUp = 0, moves = 0;
        double searchSum = 0, giveUpSum = 0;
        long revenue = 0;
        double[] hourSum = new double[24];
        int[] hourCount = new int[24];
        double[] lotPeak = new double[lots];
        double[] fullMinutes = new double[lots];

        while (!pq.isEmpty()) {
            Event e = pq.peek();
            if (e.t() >= horizon) break;
            pq.poll();
            switch (e.kind()) {
                case DEPART -> {
                    if (e.reservedSpot()) resOcc[e.lot()]--; else genOcc[e.lot()]--;
                }
                case REFRESH -> {
                    for (int l = 0; l < lots; l++) {
                        snapGeneral[l] = genOcc[l] < general[l];
                        snapResident[l] = resOcc[l] < reserved[l] || genOcc[l] < general[l];
                    }
                }
                case SAMPLE -> {
                    int occ = 0;
                    for (int l = 0; l < lots; l++) {
                        occ += genOcc[l] + resOcc[l];
                        lotPeak[l] = Math.max(lotPeak[l], (genOcc[l] + resOcc[l]) / (double) spaces[l]);
                        if (genOcc[l] >= general[l]) fullMinutes[l] += SAMPLE_MINUTES;
                    }
                    int h = hourOf(e.t() + c.startMinute());
                    hourSum[h] += occ / (double) c.totalSpaces();
                    hourCount[h]++;
                }
                case ARRIVE -> {
                    Vehicle v = e.v();
                    int lot = e.lot();
                    double t = e.t();
                    boolean resident = v.type == UserType.Resident;
                    boolean[] snap = resident ? snapResident : snapGeneral;

                    // 실시간 앱: 도착하자마자, 만차로 보이는 목적지는 들르지 않고 빈 곳으로 간다.
                    if (v.first && guidance == GuidanceMode.REALTIME_APP && lots >= 2 && !snap[lot]) {
                        v.first = false;
                        int next = nearest(lot, v.visited, snap, true);
                        if (next >= 0) {
                            v.visited[lot] = true;
                            double arrive = t + APP_EN_ROUTE_FACTOR * travel(c, mult, lot, next, t);
                            if (arrive - v.start > maxSearch) {
                                gaveUp++;
                                giveUpSum += maxSearch;   // 다음 주차장으로 가다가 한도에서 포기한다
                                gaveUpByType.merge(v.type, 1, Integer::sum);
                            } else {
                                moves++;
                                pq.add(new Event(arrive, ARRIVE, seq++, v, next, false));
                            }
                            continue;
                        }
                    }
                    v.first = false;

                    boolean useReserved = resident && resOcc[lot] < reserved[lot];
                    if (useReserved || genOcc[lot] < general[lot]) {
                        if (useReserved) resOcc[lot]++; else genOcc[lot]++;
                        double search = t - v.start;
                        parked++;
                        searchSum += search;
                        parkedByType.merge(v.type, 1, Integer::sum);
                        searchByType.merge(v.type, search, Double::sum);
                        revenue += (long) (feePer30(c, pricing, hourOf(t + c.startMinute())) * Math.ceil(v.stay / 30.0));
                        pq.add(new Event(t + v.stay, DEPART, seq++, v, lot, useReserved));
                        continue;
                    }

                    // 만차 — 다음 주차장을 고르거나 포기한다.
                    v.visited[lot] = true;
                    double now = t + (guidance == GuidanceMode.NONE ? c.getFullLotCircleMinutes() : 0.0);
                    int next = guidance == GuidanceMode.REALTIME_APP
                            ? nearest(lot, v.visited, snap, true)
                            : -1;
                    if (next < 0) next = nearest(lot, v.visited, snap, false);
                    double arrive = next < 0 ? Double.MAX_VALUE : now + travel(c, mult, lot, next, now);
                    if (next < 0 || arrive - v.start > maxSearch) {
                        gaveUp++;
                        // 갈 곳이 없으면 지금 포기하고, 시간이 모자라면 가다가 한도에서 포기한다.
                        giveUpSum += next < 0 ? Math.min(now - v.start, maxSearch) : maxSearch;
                        gaveUpByType.merge(v.type, 1, Integer::sum);
                    } else {
                        moves++;
                        pq.add(new Event(arrive, ARRIVE, seq++, v, next, false));
                    }
                }
                default -> throw new IllegalStateException("알 수 없는 사건: " + e.kind());
            }
        }
        int stillSearching = 0;
        for (Event e : pq) if (e.kind() == ARRIVE) stillSearching++;

        double[] hourly = new double[24];
        for (int h = 0; h < 24; h++) hourly[h] = hourCount[h] == 0 ? 0.0 : hourSum[h] / hourCount[h];

        return new SeedResult(seed, arrivals, parked, gaveUp, priceAvoided, stillSearching, searchSum, giveUpSum,
                arrivalsByType, parkedByType, gaveUpByType, searchByType,
                hourly, lotPeak, fullMinutes, revenue, moves);
    }

    /** 목적지 옆 주차장. i번일 가중치 1/(i+1) — 중심 상권(0번) 쪽으로 몰린다. */
    static int pickDestination(Random rnd, int lots) {
        double total = 0;
        for (int i = 0; i < lots; i++) total += 1.0 / (i + 1);
        double r = rnd.nextDouble() * total;
        for (int i = 0; i < lots; i++) {
            r -= 1.0 / (i + 1);
            if (r < 0) return i;
        }
        return lots - 1;
    }

    /** 30분당 요금. 무료면 0, 시간대 차등이면 혼잡 시간대에 할증. */
    static double feePer30(ParkingConfig c, PricingPolicy pricing, int hour) {
        if (pricing == PricingPolicy.FREE) return 0.0;
        double base = c.getBaseFeePer30Min();
        if (pricing == PricingPolicy.TIME_OF_DAY && PricingPolicy.isPeakHour(hour)) {
            double rate = c.getPeakSurchargeRate() == null ? 0.0 : c.getPeakSurchargeRate();
            return base * (1 + rate);
        }
        return base;
    }

    /** 가장 가까운 아직 안 가 본 주차장. {@code requireSnapshot} 이면 마지막 안내에서 빈 곳만. 없으면 -1. */
    static int nearest(int from, boolean[] visited, boolean[] snap, boolean requireSnapshot) {
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int j = 0; j < visited.length; j++) {
            if (j == from || visited[j]) continue;
            if (requireSnapshot && !snap[j]) continue;
            int d = Math.abs(j - from);
            if (d < bestDist) {
                best = j;
                bestDist = d;
            }
        }
        return best;
    }

    static double travel(ParkingConfig c, double[] mult, int from, int to, double t) {
        double base = Math.abs(to - from) * c.getLotSpacingMinutes();
        return mult == null ? base : base * mult[hourOf(t + c.startMinute())];
    }

    static int hourOf(double t) {
        return (int) ((t % 1440) / 60);
    }
}
