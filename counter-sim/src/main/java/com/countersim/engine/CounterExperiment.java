package com.countersim.engine;

import com.countersim.model.CounterConfig;
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
public class CounterExperiment {

    private final CounterEngine engine;

    public CounterExperiment(CounterEngine engine) {
        this.engine = engine;
    }

    /** 집계 결과. 필드 이름이 그대로 결과 JSON 의 키가 된다. */
    public record Summary(
            int seeds,
            double meanWaitMinutesAll,
            double stdWaitMinutesAll,
            double meanWaitMinutesServed,
            double renegesPerDay,
            double stdRenegesPerDay,
            double renegeRate,
            Map<String, Map<String, Double>> byVisitorType,
            double utilization,
            double[] hourlyMeanWait,
            double peakQueueLength,
            double overtimeMinutesPerDay,
            double servedPerCounterPerDay,
            int visitorBalanceErrorMax,
            List<Integer> allReneges) {}

    public Summary run(CounterConfig c) {
        List<SeedResult> results = new ArrayList<>();
        for (long seed = 1; seed <= c.getSeeds(); seed++) results.add(engine.run(c, seed));
        int days = c.getDays();
        int n = results.size();

        double[] waitAll = results.stream().mapToDouble(SeedResult::meanWaitAll).toArray();
        double[] waitServed = results.stream().mapToDouble(SeedResult::meanWaitServed).toArray();
        double[] reneges = results.stream().mapToDouble(r -> r.reneged() / (double) days).toArray();
        double[] renegeRate = results.stream()
                .mapToDouble(r -> r.visitors() == 0 ? 0.0 : r.reneged() / (double) r.visitors()).toArray();

        Map<String, Map<String, Double>> byType = new LinkedHashMap<>();
        for (String type : List.of("RESERVED", "WALK_IN")) {
            double visitors = 0, left = 0, wait = 0;
            for (SeedResult r : results) {
                double[] t = r.byType().get(type);
                if (t == null) continue;
                visitors += t[0];
                left += t[1];
                wait += t[2];
            }
            if (visitors == 0) continue;
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("visitorsPerDay", round(visitors / n / days));
            m.put("renegesPerDay", round(left / n / days));
            m.put("meanWaitMinutes", round(wait / visitors));
            byType.put(type, m);
        }

        int hours = CounterEngine.HOURLY_WEIGHT.length;
        double[] hourly = new double[hours];
        for (int h = 0; h < hours; h++) {
            double sum = 0;
            int count = 0;
            for (SeedResult r : results) {
                sum += r.hourlyWaitSum()[h];
                count += r.hourlyCount()[h];
            }
            hourly[h] = round(count == 0 ? 0.0 : sum / count);
        }

        // 가동률의 분모에 초과 근무도 넣는다 — 문 닫은 뒤 처리한 시간을 운영 시간으로만 나누면 1 을 넘는다.
        double[] utilization = results.stream().mapToDouble(r -> r.busyMinutes()
                / (c.getCounterCount() * (CounterConfig.OPEN_MINUTES * (double) days + r.overtimeMinutes()))).toArray();
        return new Summary(
                n,
                round(mean(waitAll)), round(std(waitAll)),
                round(mean(waitServed)),
                round(mean(reneges)), round(std(reneges)),
                round(mean(renegeRate)),
                byType,
                round(mean(utilization)),
                hourly,
                round(results.stream().mapToInt(SeedResult::peakQueue).average().orElse(0)),
                round(results.stream().mapToDouble(SeedResult::overtimeMinutes).average().orElse(0) / days),
                round(results.stream().mapToInt(SeedResult::served).average().orElse(0)
                        / days / c.getCounterCount()),
                results.stream().mapToInt(r -> Math.abs(r.visitorBalanceError())).max().orElse(0),
                results.stream().map(SeedResult::reneged).toList());
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
