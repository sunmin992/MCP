package com.districtsim.engine;

import com.districtsim.model.HousingType;

import java.util.Map;

/**
 * 시드 하나로 기간 전체를 돌린 결과. 양은 기간 합계 — 일평균은 집계가 나눈다.
 *
 * @param generatedKg           이 수거가 맡는 배출량(상가 제외분은 뺀다)
 * @param collectedKg           실은 양
 * @param remainingKg           기간 끝에 구역에 남은 양(오늘 밤 내놓은 것 포함)
 * @param excludedKg            상가를 수거에서 뺐을 때 그 배출량
 * @param collectionDays        실제로 수거한 날 수
 * @param skippedForHoliday     수거일인데 공휴일이라 건너뛴 날 수
 * @param leftoverKgSum         수거한 날마다 수거 뒤 남은 양의 합
 * @param leftoverZoneDays      수거한 날 수거 뒤에도 쓰레기가 남은 구역-일 수
 * @param maxWaitDays           한 구역이 다 비워지지 않은 채 지난 가장 긴 날 수
 * @param maxZoneBacklogKg      수거 직전 한 구역에 쌓인 가장 많은 양
 * @param preCollectionKgSum    수거한 날마다 수거 직전 쌓인 양의 합
 * @param trips                 비우러 다녀온 횟수(마지막 하차 포함)
 * @param truckDays             차량이 나간 차량-일 수
 * @param workMinutesSum        차량-일마다 근무 시간 합
 * @param generatedByWeekday    요일(0=월)별 배출량
 * @param collectedByWeekday    요일별 수거량
 * @param leftoverByType        주거 유형별 수거 뒤 남은 양의 합
 */
public record SeedResult(
        long seed,
        double generatedKg,
        double collectedKg,
        double remainingKg,
        double excludedKg,
        int collectionDays,
        int skippedForHoliday,
        double leftoverKgSum,
        int leftoverZoneDays,
        int maxWaitDays,
        double maxZoneBacklogKg,
        double preCollectionKgSum,
        int trips,
        int truckDays,
        double workMinutesSum,
        double[] generatedByWeekday,
        double[] collectedByWeekday,
        Map<HousingType, Double> leftoverByType) {

    /** 배출 = 수거 + 남은 양. 0 이 아니면 쓰레기가 새거나 생겼다. */
    public double massBalanceErrorKg() {
        return generatedKg - collectedKg - remainingKg;
    }
}
