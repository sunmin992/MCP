package com.countersim.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 시드 하나로 기간 전체를 돌린 결과. 횟수는 기간 합계다 — 일평균은 집계가 나눈다.
 *
 * @param visitors        방문객(예약 포함)
 * @param served          처리받은 사람
 * @param reneged         기다리다 돌아간 사람
 * @param waitSumServed   처리받은 사람의 대기 시간 합(분)
 * @param waitSumReneged  돌아간 사람이 돌아가기까지 기다린 시간 합(분)
 * @param byType          RESERVED / WALK_IN 별 [방문, 이탈, 대기 합]
 * @param hourlyWaitSum   도착 시간대(09~17시)별 대기 합(분)
 * @param hourlyCount     도착 시간대별 사람 수(처리 + 이탈)
 * @param busyMinutes     창구가 처리하던 시간 합(분)
 * @param overtimeMinutes 문 닫은 뒤 처리한 시간 합(분, 날마다 마지막 처리 기준)
 * @param peakQueue       기다리던 사람이 가장 많았던 순간의 인원
 */
public record SeedResult(
        long seed,
        int visitors,
        int served,
        int reneged,
        double waitSumServed,
        double waitSumReneged,
        Map<String, double[]> byType,
        double[] hourlyWaitSum,
        int[] hourlyCount,
        double busyMinutes,
        double overtimeMinutes,
        int peakQueue) {

    /** 방문 = 처리 + 이탈. 0 이 아니면 사람이 새거나 생겼다. */
    public int visitorBalanceError() {
        return visitors - served - reneged;
    }

    /** 처리받은 사람만의 평균 대기. 이탈이 많으면 오래 기다리던 사람이 빠져 낮게 나온다 — 단독으로 비교하지 않는다. */
    public double meanWaitServed() {
        return served == 0 ? 0.0 : waitSumServed / served;
    }

    /** 온 사람 모두(처리 + 이탈)의 평균 대기. 설정끼리 비교할 때는 이것을 쓴다. */
    public double meanWaitAll() {
        int n = served + reneged;
        return n == 0 ? 0.0 : (waitSumServed + waitSumReneged) / n;
    }

    static final class Builder {
        final long seed;
        int visitors, served, reneged, peakQueue;
        double waitSumServed, waitSumReneged, busyMinutes, overtimeMinutes;
        final Map<String, double[]> byType = new LinkedHashMap<>();
        final double[] hourlyWaitSum;
        final int[] hourlyCount;

        Builder(long seed, int hours) {
            this.seed = seed;
            this.hourlyWaitSum = new double[hours];
            this.hourlyCount = new int[hours];
        }

        void served(CounterEngine.Visitor v, double wait, int hour) {
            served++;
            waitSumServed += wait;
            record(v, wait, hour, false);
        }

        void reneged(CounterEngine.Visitor v, double wait, int hour) {
            reneged++;
            waitSumReneged += wait;
            record(v, wait, hour, true);
        }

        private void record(CounterEngine.Visitor v, double wait, int hour, boolean left) {
            double[] t = byType.computeIfAbsent(v.reserved() ? "RESERVED" : "WALK_IN", k -> new double[3]);
            t[0]++;
            if (left) t[1]++;
            t[2] += wait;
            hourlyWaitSum[hour] += wait;
            hourlyCount[hour]++;
        }

        SeedResult build() {
            return new SeedResult(seed, visitors, served, reneged, waitSumServed, waitSumReneged, byType,
                    hourlyWaitSum, hourlyCount, busyMinutes, overtimeMinutes, peakQueue);
        }
    }
}
