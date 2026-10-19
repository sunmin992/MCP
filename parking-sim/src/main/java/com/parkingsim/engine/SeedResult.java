package com.parkingsim.engine;

import com.parkingsim.model.UserType;

import java.util.Map;

/**
 * 시드 하나로 기간 전체를 돌린 결과. 횟수는 기간 합계다 — 일평균은 집계가 나눈다.
 *
 * @param arrivals          도착 차량(요금 회피 전)
 * @param parked            주차에 성공한 차량
 * @param gaveUp            탐색 포기 차량
 * @param priceAvoided      요금 때문에 오지 않은 차량
 * @param stillSearching    기간이 끝날 때 아직 찾고 있던 차량
 * @param searchMinutesSum  주차에 성공한 차량의 탐색 시간 합(분)
 * @param giveUpMinutesSum  포기한 차량이 포기하기까지 찾은 시간 합(분)
 * @param hourlyOccupancy   시(0–23)별 평균 점유율 — 전체 면 기준, 기간 평균
 * @param lotPeakOccupancy  주차장별 최대 점유율
 * @param fullMinutes       주차장별 일반면이 만차였던 시간(분, 기간 합, 10분 표본)
 * @param revenue           요금 수입(원, 기간 합)
 * @param interLotMoves     만차 때문에 다른 주차장으로 옮긴 횟수(기간 합)
 */
public record SeedResult(
        long seed,
        int arrivals,
        int parked,
        int gaveUp,
        int priceAvoided,
        int stillSearching,
        double searchMinutesSum,
        double giveUpMinutesSum,
        Map<UserType, Integer> arrivalsByType,
        Map<UserType, Integer> parkedByType,
        Map<UserType, Integer> gaveUpByType,
        Map<UserType, Double> searchMinutesByType,
        double[] hourlyOccupancy,
        double[] lotPeakOccupancy,
        double[] fullMinutes,
        long revenue,
        int interLotMoves) {

    /** 도착 = 주차 + 포기 + 요금 회피 + 탐색 중. 0 이 아니면 차량이 새거나 생겼다. */
    public int vehicleBalanceError() {
        return arrivals - parked - gaveUp - priceAvoided - stillSearching;
    }

    /** 주차한 차량만의 평균 탐색 시간. 포기가 많으면 오래 찾던 차가 빠져 낮게 나온다 — 단독으로 비교하지 않는다. */
    public double meanSearchMinutes() {
        return parked == 0 ? 0.0 : searchMinutesSum / parked;
    }

    /** 찾으러 온 모든 차량(주차 + 포기)의 평균 탐색 시간. 설정끼리 비교할 때는 이것을 쓴다. */
    public double meanSearchMinutesAll() {
        int n = parked + gaveUp;
        return n == 0 ? 0.0 : (searchMinutesSum + giveUpMinutesSum) / n;
    }
}
