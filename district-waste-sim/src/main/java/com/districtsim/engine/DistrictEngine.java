package com.districtsim.engine;

import com.districtsim.model.DistrictConfig;
import com.districtsim.model.HolidayPolicy;
import com.districtsim.model.HousingType;
import com.districtsim.model.ScheduleMode;
import com.districtsim.model.TruckType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 행정동 생활폐기물 수거 — 하루 단위 집계 시뮬레이션, 시드 하나.
 *
 * <p>하루의 흐름:
 * <ol>
 *   <li>수거일이면 차량이 수거 시작 시각에 차고지를 나와 맡은 구역을 번호 순으로 돈다. 구역마다 실을 수 있는
 *       속도가 주거 유형에 따라 다르다. 차가 차면 비우러 다녀오고(적환장 또는 소각장), 근무 시간이 끝나면
 *       남은 구역은 다음 수거일로 넘어간다.</li>
 *   <li>그날 밤 주민이 내놓은 양이 쌓인다 — 배출 창이 20:00~06:00 이라 오늘 낸 것은 다음 수거일에 실린다.</li>
 * </ol>
 *
 * <p>수거를 06:00 전에 시작하면 지난밤 배출 중 아직 안 나온 몫({@link #notOutYetFraction})은 그날 싣지 못하고
 * 다음 수거일로 넘어간다 — 배출 창(포항 북구 표준데이터 20:00~06:00) 안에서 고르게 내놓는다고 가정한다.
 *
 * <p>지점 하나하나가 아니라 <b>구역 합계</b>만 본다. 그래서 수거장별 적재 · 민원은 이 모델로 말할 수 없다.
 */
@Component
public class DistrictEngine {

    private final HolidayCalendar calendar;

    public DistrictEngine(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    public SeedResult run(DistrictConfig c, long seed) {
        Random rnd = new Random(seed);
        final int zones = c.getZoneCount();
        final List<HousingType> mix = c.resolveHousingMix();
        final TruckType truck = c.resolveTruckType();
        final ScheduleMode mode = c.resolveScheduleMode();
        final List<Integer> baseDays = c.resolveCollectionDays();
        final HolidayPolicy policy = c.getHolidayPolicy() == null ? null : HolidayPolicy.fromName(c.getHolidayPolicy());
        final LocalDate start = LocalDate.parse(c.getStartDate());
        final int trucks = c.effectiveTrucks();

        HousingType[] typeOf = new HousingType[zones];
        boolean[] included = new boolean[zones];
        for (int z = 0; z < zones; z++) {
            typeOf[z] = mix.get(z % mix.size());
            included[z] = !(typeOf[z] == HousingType.Commercial && c.commercialExcluded());
        }

        final int[] bounds = allocate(c, typeOf, trucks);
        double[] available = new double[zones];
        double[] lastNight = new double[zones];
        final double notOut = notOutYetFraction(c.getCollectionStartMinutes());
        int[] waitDays = new int[zones];
        double generated = 0, collected = 0, excluded = 0;
        double[] genByDow = new double[7];
        double[] colByDow = new double[7];
        Map<HousingType, Double> leftoverByType = new EnumMap<>(HousingType.class);
        for (HousingType t : mix) leftoverByType.put(t, 0.0);

        // 0일차 새벽에 실릴 전날 밤 배출량
        for (int z = 0; z < zones; z++) {
            double g = generate(c, typeOf[z], rnd);
            if (included[z]) {
                available[z] += g;
                lastNight[z] = g;
                generated += g;
            } else {
                excluded += g;
            }
        }

        int collectionDays = 0, skipped = 0, leftoverZoneDays = 0, maxWait = 0, trips = 0, truckDays = 0;
        double leftoverSum = 0, maxBacklog = 0, preSum = 0, workSum = 0;
        boolean owed = false;   // 공휴일로 미룬 수거가 있다

        for (int d = 0; d < c.getDays(); d++) {
            LocalDate date = start.plusDays(d);
            int dow = date.getDayOfWeek().getValue() - 1;   // 0=월
            boolean holiday = c.isHolidayEnabled() && calendar.isHoliday(date);
            boolean scheduled = baseDays.contains(dow);

            boolean collect;
            if (holiday) {
                if (scheduled) {
                    skipped++;
                    if (mode != ScheduleMode.DAILY && policy == HolidayPolicy.NEXT_DAY) owed = true;
                }
                collect = false;
            } else {
                collect = scheduled || owed;
                owed = false;
            }

            if (collect) {
                collectionDays++;
                double pre = 0;
                for (int z = 0; z < zones; z++) {
                    pre += available[z];
                    maxBacklog = Math.max(maxBacklog, available[z]);
                }
                preSum += pre;
                double[] holdOf = new double[zones];
                for (int z = 0; z < zones; z++) holdOf[z] = Math.min(available[z], lastNight[z] * notOut);

                // 차량마다 이웃 구역 묶음을 맡는다.
                for (int k = 0; k < trucks; k++) {
                    int from = bounds[k];
                    int to = bounds[k + 1];
                    double t = c.getDepotTravelMinutes() * truck.travelFactor * jitter(rnd);
                    double limit = c.getShiftMinutes();
                    double load = 0;
                    int truckTrips = 0;
                    for (int z = from; z < to && t < limit; z++) {
                        if (z > from) t += c.getZoneTravelMinutes() * truck.travelFactor * jitter(rnd);
                        double rate = typeOf[z].serviceKgPerMinute;
                        double hold = holdOf[z];   // 아직 안 나온 몫
                        while (available[z] - hold > 1e-9 && t < limit) {
                            double take = Math.min(available[z] - hold, Math.min(truck.capacityKg - load, (limit - t) * rate));
                            if (take <= 0) break;
                            t += take / rate;
                            load += take;
                            available[z] -= take;
                            collected += take;
                            colByDow[dow] += take;
                            if (load >= truck.capacityKg - 1e-9) {
                                t += c.unloadRoundTripMinutes() * jitter(rnd);
                                truckTrips++;
                                load = 0;
                            }
                        }
                    }
                    if (load > 0) truckTrips++;   // 마지막 하차(근무 시간 뒤라도 비운다)
                    trips += truckTrips;
                    truckDays++;
                    workSum += Math.min(t, limit);
                }

                for (int z = 0; z < zones; z++) {
                    if (available[z] > 1e-6) {
                        // 수거 뒤 길에 남는 양 — 차가 지나간 뒤 내놓은 몫도 다음 수거일까지 남는다.
                        leftoverZoneDays++;
                        leftoverSum += available[z];
                        leftoverByType.merge(typeOf[z], available[z], Double::sum);
                    } else {
                        available[z] = 0;
                    }
                    // 기다린 날 수는 "나와 있었는데 못 실은" 양이 있을 때만 이어진다. 아직 안 나온 몫은 놓친 것이 아니다.
                    boolean missed = available[z] - holdOf[z] > 1e-6;
                    waitDays[z] = missed ? waitDays[z] + 1 : 0;
                }
            } else {
                for (int z = 0; z < zones; z++) if (available[z] > 1e-6) waitDays[z]++;
            }
            for (int z = 0; z < zones; z++) maxWait = Math.max(maxWait, waitDays[z]);

            // 오늘 밤 배출 — 다음 수거일에 실린다.
            for (int z = 0; z < zones; z++) {
                double g = generate(c, typeOf[z], rnd);
                if (included[z]) {
                    available[z] += g;
                    lastNight[z] = g;
                    generated += g;
                    genByDow[dow] += g;
                } else {
                    excluded += g;
                }
            }
        }
        double remaining = 0;
        for (double a : available) remaining += a;

        return new SeedResult(seed, generated, collected, remaining, excluded, collectionDays, skipped,
                leftoverSum, leftoverZoneDays, maxWait, maxBacklog, preSum, trips, truckDays, workSum,
                genByDow, colByDow, leftoverByType);
    }

    /**
     * 차량 k 가 맡는 구역은 {@code [bounds[k], bounds[k+1])}. 이웃 구역끼리 묶는다.
     *
     * <p>같은 구역 수로 나누면 상가 · 단독주택처럼 오래 걸리는 구역이 한 차에 몰릴 수 있다. 작업 시간이 같게
     * 나누면 구역별 예상 작업 시간(하루 배출량 ÷ 싣는 속도)의 누적이 고르게 끊긴다. 차가 한 대면 둘이 같다.
     */
    static int[] allocate(DistrictConfig c, HousingType[] typeOf, int trucks) {
        int zones = typeOf.length;
        int[] b = new int[trucks + 1];
        b[trucks] = zones;
        if (!c.balancedAllocation()) {
            for (int k = 0; k <= trucks; k++) b[k] = k * zones / Math.max(1, trucks);
            return b;
        }
        double[] work = new double[zones];
        double total = 0;
        for (int z = 0; z < zones; z++) {
            work[z] = c.getHouseholdsPerZone() * typeOf[z].kgPerHouseholdPerDay / typeOf[z].serviceKgPerMinute;
            total += work[z];
        }
        double acc = 0;
        int k = 1;
        for (int z = 0; z < zones && k < trucks; z++) {
            acc += work[z];
            // 남은 차마다 구역이 하나는 남도록
            if (acc >= total * k / trucks || zones - (z + 1) == trucks - k) b[k++] = z + 1;
        }
        return b;
    }

    private static double generate(DistrictConfig c, HousingType type, Random rnd) {
        double mean = c.getHouseholdsPerZone() * type.kgPerHouseholdPerDay;
        return Math.max(0, mean * (1 + c.getGenerationCv() * rnd.nextGaussian()));
    }

    /**
     * 수거 시작 시각에 지난밤 배출 중 아직 안 나온 몫. 배출 창 20:00~06:00(600분)에 고르게 내놓는다고 보고,
     * 06:00 이후 시작이면 0 이다. 20:00 이후 시작은 그날 밤 배출과 겹치므로 같은 규칙(창 안의 경과)으로 센다.
     */
    static double notOutYetFraction(int startMinutes) {
        int windowStart = 20 * 60, windowEnd = 6 * 60, length = 600;
        int elapsed;
        if (startMinutes >= windowStart) elapsed = startMinutes - windowStart;
        else if (startMinutes < windowEnd) elapsed = startMinutes + (1440 - windowStart);
        else return 0.0;
        return Math.max(0.0, 1.0 - elapsed / (double) length);
    }

    /** 이동 · 하차 시간 흔들림 0.9~1.2. */
    private static double jitter(Random rnd) {
        return 0.9 + 0.3 * rnd.nextDouble();
    }
}
