package com.countersim.engine;

import com.countersim.model.CounterConfig;
import com.countersim.model.QueueDiscipline;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 민원 창구 하나 묶음의 이산사건 시뮬레이션. 시드 하나로 기간 전체를 돈다.
 *
 * <p>하루는 09:00~18:00(540분)이다. 문을 닫은 뒤에는 새로 받지 않고, 줄에 남은 사람은 끝까지 처리한다 —
 * 그 시간이 초과 근무다. 예약한 사람은 예약 시각에 와서 같은 줄의 걸어온 사람보다 먼저 불린다.
 *
 * <p>기다리다 돌아가는 사람(이탈)은 도착 후 {@code maxWaitMinutes} 가 지나도록 불리지 않은 사람이다.
 * 떠나는 순간을 따로 사건으로 두지 않고, 줄을 볼 때마다 기한이 지난 사람을 걷어 낸다 — 떠난 사람은
 * 창구를 쓰지 않으므로 결과가 같다.
 */
@Component
public class CounterEngine {

    /** 시간대(09시~17시)별 걸어오는 방문 가중치. 가정값 — 점심(12시)에 줄고 오전·오후 첫 시간에 몰린다. */
    static final double[] HOURLY_WEIGHT = {1.0, 1.4, 1.2, 0.6, 0.8, 1.3, 1.2, 1.0, 0.7};

    private static final int HOURS = HOURLY_WEIGHT.length;

    record Visitor(double arrival, double service, boolean reserved) {}

    public SeedResult run(CounterConfig c, long seed) {
        Random rnd = new Random(seed);
        int counters = c.getCounterCount();
        double maxWait = c.getMaxWaitMinutes();
        QueueDiscipline discipline = c.resolveQueueDiscipline();
        int queues = discipline == QueueDiscipline.SINGLE_LINE ? 1 : counters;

        SeedResult.Builder out = new SeedResult.Builder(seed, HOURS);
        for (int day = 0; day < c.getDays(); day++) {
            List<Visitor> visitors = arrivals(c, rnd);
            out.visitors += visitors.size();

            @SuppressWarnings("unchecked") Deque<Visitor>[] reservedQ = new Deque[queues];
            @SuppressWarnings("unchecked") Deque<Visitor>[] walkQ = new Deque[queues];
            for (int q = 0; q < queues; q++) {
                reservedQ[q] = new ArrayDeque<>();
                walkQ[q] = new ArrayDeque<>();
            }
            boolean[] busy = new boolean[counters];
            double[] busyUntil = new double[counters];
            double lastFinish = 0;
            int ai = 0;

            while (true) {
                double tA = ai < visitors.size() ? visitors.get(ai).arrival() : Double.POSITIVE_INFINITY;
                int kC = -1;
                for (int k = 0; k < counters; k++) {
                    if (busy[k] && (kC < 0 || busyUntil[k] < busyUntil[kC])) kC = k;
                }
                if (tA == Double.POSITIVE_INFINITY && kC < 0) break;

                double now;
                if (kC < 0 || tA <= busyUntil[kC]) {
                    now = tA;
                    for (int q = 0; q < queues; q++) purge(reservedQ[q], walkQ[q], now, maxWait, out);
                    Visitor v = visitors.get(ai++);
                    int q = discipline == QueueDiscipline.SINGLE_LINE ? 0 : shortest(reservedQ, walkQ, busy);
                    (v.reserved() ? reservedQ[q] : walkQ[q]).addLast(v);
                } else {
                    now = busyUntil[kC];
                    busy[kC] = false;
                    lastFinish = Math.max(lastFinish, now);
                    for (int q = 0; q < queues; q++) purge(reservedQ[q], walkQ[q], now, maxWait, out);
                }

                // 비는 창구가 자기 줄(번호표면 하나뿐인 줄)에서 다음 사람을 부른다. 예약한 사람이 먼저다.
                for (int k = 0; k < counters; k++) {
                    if (busy[k]) continue;
                    int q = queues == 1 ? 0 : k;
                    Visitor next = !reservedQ[q].isEmpty() ? reservedQ[q].pollFirst() : walkQ[q].pollFirst();
                    if (next == null) continue;
                    out.served(next, now - next.arrival(), hourOf(next));
                    busy[k] = true;
                    busyUntil[k] = now + next.service();
                    out.busyMinutes += next.service();
                }

                int waiting = 0;
                for (int q = 0; q < queues; q++) waiting += reservedQ[q].size() + walkQ[q].size();
                out.peakQueue = Math.max(out.peakQueue, waiting);
            }
            out.overtimeMinutes += Math.max(0, lastFinish - CounterConfig.OPEN_MINUTES);
        }
        return out.build();
    }

    /** 하루치 방문객. 예약한 사람은 하루에 고르게 나뉜 예약 시각에, 걸어오는 사람은 시간대 가중치대로 온다. */
    List<Visitor> arrivals(CounterConfig c, Random rnd) {
        int total = c.getDailyVisitors();
        int reserved = (int) Math.round(total * c.effectiveReservationShare());
        double weightSum = 0;
        for (double w : HOURLY_WEIGHT) weightSum += w;

        List<Visitor> out = new ArrayList<>(total);
        for (int i = 0; i < reserved; i++) {
            double t = (i + 0.5) * CounterConfig.OPEN_MINUTES / reserved;
            out.add(new Visitor(t, service(c, rnd), true));
        }
        for (int i = reserved; i < total; i++) {
            double pick = rnd.nextDouble() * weightSum;
            int h = 0;
            while (h < HOURS - 1 && pick >= HOURLY_WEIGHT[h]) pick -= HOURLY_WEIGHT[h++];
            double t = h * 60 + rnd.nextDouble() * 60;
            out.add(new Visitor(t, service(c, rnd), false));
        }
        out.sort(Comparator.comparingDouble(Visitor::arrival));
        return out;
    }

    private static double service(CounterConfig c, Random rnd) {
        return -c.getServiceMinutes() * Math.log(1 - rnd.nextDouble());
    }

    /** 기다린 시간이 한도를 넘은 사람을 걷어 내 이탈로 센다. 이탈한 사람의 대기는 한도와 같다. */
    private static void purge(Deque<Visitor> reservedQ, Deque<Visitor> walkQ, double now, double maxWait,
                              SeedResult.Builder out) {
        for (Deque<Visitor> q : List.of(reservedQ, walkQ)) {
            q.removeIf(v -> {
                if (now - v.arrival() <= maxWait) return false;
                out.reneged(v, maxWait, hourOf(v));
                return true;
            });
        }
    }

    /** 창구별 줄에서 가장 짧은 줄(처리 중인 사람 포함). 같으면 앞 창구. */
    private static int shortest(Deque<Visitor>[] reservedQ, Deque<Visitor>[] walkQ, boolean[] busy) {
        int best = 0, bestLen = Integer.MAX_VALUE;
        for (int q = 0; q < busy.length; q++) {
            int len = reservedQ[q].size() + walkQ[q].size() + (busy[q] ? 1 : 0);
            if (len < bestLen) {
                best = q;
                bestLen = len;
            }
        }
        return best;
    }

    private static int hourOf(Visitor v) {
        return Math.min(HOURS - 1, (int) (v.arrival() / 60));
    }
}
