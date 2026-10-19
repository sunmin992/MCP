package com.districtsim.engine;

import com.districtsim.model.DistrictConfig;
import com.districtsim.model.HousingType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실행 설정 하나를 시드 1..seeds 로 돌려 집계한다. 양은 일평균(kg/일)으로 낸다 — 기간이 달라도 비교되게.
 */
@Component
public class DistrictExperiment {

    private final DistrictEngine engine;

    public DistrictExperiment(DistrictEngine engine) {
        this.engine = engine;
    }

    /** 집계 결과. 필드 이름이 그대로 결과 JSON 의 키가 된다. */
    public record Summary(
            int seeds,
            double leftoverKgPerCollectionDay,
            double stdLeftoverKgPerCollectionDay,
            double leftoverZoneDaysPerWeek,
            double maxWaitDays,
            double maxZoneBacklogKg,
            double preCollectionKgPerCollectionDay,
            Map<String, Double> leftoverKgByHousingType,
            double[] generatedKgByWeekday,
            double[] collectedKgByWeekday,
            double collectionDaysRun,
            double skippedForHoliday,
            double truckUtilization,
            double workMinutesPerTruckDay,
            double tripsPerCollectionDay,
            double generatedKgPerDay,
            double excludedKgPerDay,
            double massBalanceErrorKgMax,
            List<Double> allLeftoverKgPerCollectionDay) {}

    public Summary run(DistrictConfig c) {
        List<SeedResult> rs = new ArrayList<>();
        for (long seed = 1; seed <= c.getSeeds(); seed++) rs.add(engine.run(c, seed));
        int n = rs.size();
        int days = c.getDays();
        double capacity = c.resolveTruckType().capacityKg;

        double[] leftover = rs.stream().mapToDouble(r -> r.collectionDays() == 0 ? 0 : r.leftoverKgSum() / r.collectionDays()).toArray();

        Map<String, Double> byType = new LinkedHashMap<>();
        for (HousingType t : c.resolveHousingMix()) {
            if (byType.containsKey(t.name())) continue;
            double s = 0;
            for (SeedResult r : rs) {
                s += r.collectionDays() == 0 ? 0 : r.leftoverByType().getOrDefault(t, 0.0) / r.collectionDays();
            }
            byType.put(t.name(), round(s / n));
        }
        double[] gen = new double[7], col = new double[7];
        int weeks = Math.max(1, days / 7);
        for (SeedResult r : rs) {
            for (int i = 0; i < 7; i++) {
                gen[i] += r.generatedByWeekday()[i] / n / weeks;
                col[i] += r.collectedByWeekday()[i] / n / weeks;
            }
        }
        for (int i = 0; i < 7; i++) {
            gen[i] = round(gen[i]);
            col[i] = round(col[i]);
        }

        return new Summary(
                n,
                round(mean(leftover)), round(std(leftover)),
                round(rs.stream().mapToInt(SeedResult::leftoverZoneDays).average().orElse(0) / days * 7),
                round(rs.stream().mapToInt(SeedResult::maxWaitDays).average().orElse(0)),
                round(rs.stream().mapToDouble(SeedResult::maxZoneBacklogKg).average().orElse(0)),
                round(rs.stream().mapToDouble(r -> r.collectionDays() == 0 ? 0 : r.preCollectionKgSum() / r.collectionDays()).average().orElse(0)),
                byType, gen, col,
                round(rs.stream().mapToInt(SeedResult::collectionDays).average().orElse(0)),
                round(rs.stream().mapToInt(SeedResult::skippedForHoliday).average().orElse(0)),
                round(rs.stream().mapToDouble(r -> r.trips() == 0 ? 0 : r.collectedKg() / (r.trips() * capacity)).average().orElse(0)),
                round(rs.stream().mapToDouble(r -> r.truckDays() == 0 ? 0 : r.workMinutesSum() / r.truckDays()).average().orElse(0)),
                round(rs.stream().mapToDouble(r -> r.collectionDays() == 0 ? 0 : r.trips() / (double) r.collectionDays()).average().orElse(0)),
                round(rs.stream().mapToDouble(SeedResult::generatedKg).average().orElse(0) / days),
                round(rs.stream().mapToDouble(SeedResult::excludedKg).average().orElse(0) / days),
                round(rs.stream().mapToDouble(r -> Math.abs(r.massBalanceErrorKg())).max().orElse(0)),
                java.util.Arrays.stream(leftover).map(DistrictExperiment::round).boxed().toList());
    }

    static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length == 0 ? 0 : s / v.length;
    }

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
