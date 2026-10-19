package com.parkingsim.engine;

import com.parkingsim.model.ParkingConfig;
import com.parkingsim.model.UserType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실행 설정 하나를 시드 1..seeds 로 돌려 집계한다.
 *
 * <p>시드를 고정하는 이유: 설정이 다른 실행끼리 같은 시드를 쓰면 차이를 같은 시드끼리 짝지어 볼 수
 * 있다. 횟수 지표는 모두 <b>일평균</b>으로 낸다 — 기간이 다른 실행끼리도 비교되게.
 */
@Component
public class ParkingExperiment {

    private final ParkingEngine engine;

    public ParkingExperiment(ParkingEngine engine) {
        this.engine = engine;
    }

    /** 집계 결과. 필드 이름이 그대로 결과 JSON 의 키가 된다. */
    public record Summary(
            int seeds,
            double meanSearchMinutes,
            double stdSearchMinutes,
            double meanSearchMinutesAll,
            double stdSearchMinutesAll,
            double giveUpsPerDay,
            double stdGiveUpsPerDay,
            double giveUpRate,
            Map<String, Map<String, Double>> byUserType,
            double peakOccupancyRatio,
            double[] lotPeakOccupancy,
            double[] hourlyOccupancy,
            double fullMinutesPerLotPerDay,
            double revenuePerDay,
            double interLotMovesPerDay,
            double priceAvoidedPerDay,
            int vehicleBalanceErrorMax,
            List<Integer> allGiveUps) {}

    public Summary run(ParkingConfig c) {
        List<SeedResult> results = new ArrayList<>();
        for (long seed = 1; seed <= c.getSeeds(); seed++) results.add(engine.run(c, seed));
        int days = c.getDays();
        int lots = c.getLotCount();

        double[] search = results.stream().mapToDouble(SeedResult::meanSearchMinutes).toArray();
        double[] searchAll = results.stream().mapToDouble(SeedResult::meanSearchMinutesAll).toArray();
        double[] giveUps = results.stream().mapToDouble(r -> r.gaveUp() / (double) days).toArray();
        double[] giveUpRate = results.stream().mapToDouble(r -> {
            int attempted = r.arrivals() - r.priceAvoided();
            return attempted == 0 ? 0.0 : r.gaveUp() / (double) attempted;
        }).toArray();

        Map<String, Map<String, Double>> byType = new LinkedHashMap<>();
        for (UserType u : c.resolveUserMix()) {
            if (byType.containsKey(u.name())) continue;
            double arrivals = 0, gave = 0, searchSum = 0, parked = 0;
            for (SeedResult r : results) {
                arrivals += r.arrivalsByType().getOrDefault(u, 0);
                gave += r.gaveUpByType().getOrDefault(u, 0);
                parked += r.parkedByType().getOrDefault(u, 0);
                searchSum += r.searchMinutesByType().getOrDefault(u, 0.0);
            }
            int n = results.size();
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("arrivalsPerDay", round(arrivals / n / days));
            m.put("giveUpsPerDay", round(gave / n / days));
            m.put("meanSearchMinutes", round(parked == 0 ? 0.0 : searchSum / parked));
            byType.put(u.name(), m);
        }

        double[] lotPeak = new double[lots];
        double[] hourly = new double[24];
        double fullSum = 0;
        for (SeedResult r : results) {
            for (int l = 0; l < lots; l++) {
                lotPeak[l] += r.lotPeakOccupancy()[l] / results.size();
                fullSum += r.fullMinutes()[l];
            }
            for (int h = 0; h < 24; h++) hourly[h] += r.hourlyOccupancy()[h] / results.size();
        }
        double peak = 0;
        for (int l = 0; l < lots; l++) {
            lotPeak[l] = round(lotPeak[l]);
            peak = Math.max(peak, lotPeak[l]);
        }
        for (int h = 0; h < 24; h++) hourly[h] = round(hourly[h]);

        return new Summary(
                results.size(),
                round(mean(search)), round(std(search)),
                round(mean(searchAll)), round(std(searchAll)),
                round(mean(giveUps)), round(std(giveUps)),
                round(mean(giveUpRate)),
                byType,
                peak, lotPeak, hourly,
                round(fullSum / results.size() / lots / days),
                round(results.stream().mapToLong(SeedResult::revenue).average().orElse(0) / days),
                round(results.stream().mapToInt(SeedResult::interLotMoves).average().orElse(0) / days),
                round(results.stream().mapToInt(SeedResult::priceAvoided).average().orElse(0) / days),
                results.stream().mapToInt(r -> Math.abs(r.vehicleBalanceError())).max().orElse(0),
                results.stream().map(SeedResult::gaveUp).toList());
    }

    static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length == 0 ? 0 : s / v.length;
    }

    /** 표본 표준편차. 시드가 하나면 0. */
    static double std(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.length - 1));
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
