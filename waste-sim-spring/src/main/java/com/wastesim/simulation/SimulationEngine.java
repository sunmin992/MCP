package com.wastesim.simulation;

import com.wastesim.model.OccupationType;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DEVS 포름 재구현 — event-queue 기반 discrete-event simulation.
 *
 * 시간 단위: 분 (1일 = 1440분)
 * 논문 Fig.2 ResidentGenerator / Fig.5 GarbageCan / Fig.6 GarbageTruck /
 *            ComplaintMonitor 를 단일 루프로 통합.
 */
@Component
public class SimulationEngine {

    static final int DAY = 1440;

    // ── 이벤트 정의 ─────────────────────────────────────────────────────────

    abstract static class Evt implements Comparable<Evt> {
        final int time;
        Evt(int time) { this.time = time; }

        @Override
        public int compareTo(Evt o) {
            int c = Integer.compare(this.time, o.time);
            // Collect 이벤트를 Discharge 이전에 처리 (같은 시각이면 수거 먼저)
            if (c == 0) return Integer.compare(this.priority(), o.priority());
            return c;
        }

        int priority() { return 1; } // 기본값 — Discharge
    }

    static class CollectEvt extends Evt {
        CollectEvt(int time) { super(time); }
        @Override int priority() { return 0; } // 수거 우선
    }

    static class DischargeEvt extends Evt {
        final int rid, building, day;
        final OccupationType occ;

        DischargeEvt(int time, int rid, OccupationType occ, int building, int day) {
            super(time);
            this.rid = rid;
            this.occ = occ;
            this.building = building;
            this.day = day;
        }
    }

    // ── 메인 시뮬레이션 ──────────────────────────────────────────────────────

    /**
     * 단일 seed 실행 — SimulationResult 반환.
     */
    public SimulationResult run(SimulationConfig cfg, int seed) {
        Random rng = new Random(seed);
        PriorityQueue<Evt> pq = new PriorityQueue<>();
        int totalMinutes = cfg.getDays() * DAY;

        // 수거 차량 초기 이벤트
        pq.offer(new CollectEvt(cfg.getCollectionTimeMinutes()));

        // 거주민 초기 배출 이벤트 (round-robin 직업 배정)
        int nB = cfg.getNumBuildings();
        int rPB = cfg.getResidentsPerBuilding();
        for (int b = 0; b < nB; b++) {
            for (int k = 0; k < rPB; k++) {
                int rid = b * rPB + k;
                OccupationType occ = OccupationType.fromIndex(b * rPB + k);
                int leaveMin = sampleLeaveOffset(rng, occ, cfg.getLeaveSigma());
                pq.offer(new DischargeEvt(leaveMin, rid, occ, b, 0));
            }
        }

        // 수거통 상태
        double[] canFill = new double[nB];
        double[] peakFill = new double[nB];

        // 집계
        int total = 0;
        Map<String, Integer> byOcc = new LinkedHashMap<>();
        Map<Integer, Integer> byDay = new TreeMap<>();

        while (!pq.isEmpty()) {
            Evt e = pq.poll();
            if (e.time > totalMinutes) break;

            if (e instanceof CollectEvt ce) {
                // 모든 수거통 비우기 (GarbageTruck)
                Arrays.fill(canFill, 0.0);
                int nextCollect = ce.time + DAY;
                if (nextCollect <= totalMinutes) pq.offer(new CollectEvt(nextCollect));

            } else if (e instanceof DischargeEvt de) {
                // 배출 (ResidentGenerator → GarbageCan)
                double amount = sampleWaste(rng, cfg.getWasteSigma());
                canFill[de.building] += amount;
                if (canFill[de.building] > peakFill[de.building])
                    peakFill[de.building] = canFill[de.building];

                // 청결도 판정 — fill ratio >= threshold → 민원 (GarbageCan Check)
                double ratio = canFill[de.building] / cfg.getCapacity();
                if (ratio >= cfg.getThreshold()) {
                    total++;
                    byOcc.merge(de.occ.name(), 1, Integer::sum);
                    byDay.merge(de.time / DAY, 1, Integer::sum);
                }

                // 다음날 배출 이벤트 예약
                int nextDay = de.day + 1;
                int nextTime = nextDay * DAY + sampleLeaveOffset(rng, de.occ, cfg.getLeaveSigma());
                if (nextTime <= totalMinutes)
                    pq.offer(new DischargeEvt(nextTime, de.rid, de.occ, de.building, nextDay));
            }
        }

        double maxPeak = Arrays.stream(peakFill).max().orElse(0.0);
        return new SimulationResult(
                cfg.getCollectionTimeLabel(), total, byOcc, byDay,
                Math.round(maxPeak * 100.0) / 100.0, seed);
    }

    // ── 확률 샘플링 ──────────────────────────────────────────────────────────

    private int sampleLeaveOffset(Random rng, OccupationType occ, double sigma) {
        int t = (int) Math.round(rng.nextGaussian() * sigma + occ.leaveMeanMinutes);
        return Math.max(0, Math.min(1439, t));
    }

    private double sampleWaste(Random rng, double wasteSigma) {
        return Math.max(0.0, rng.nextGaussian() * wasteSigma + 0.9);
    }
}
