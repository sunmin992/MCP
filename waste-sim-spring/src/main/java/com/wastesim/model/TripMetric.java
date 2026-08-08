package com.wastesim.model;

/**
 * 운행(trip) 1회의 적재·수거 상세 (TRUCK_CAPACITY_ENHANCEMENT_PLAN.md §3.4).
 *
 * <p>전체 평균만으로는 어느 트럭·경로가 병목인지 알 수 없어, 운행 단위로 배정용량·초기
 * 적재·실제 수거·최종 적재·이용률을 남긴다. 운행 스케줄은 시드와 무관하게 결정되므로
 * (발생량만 시드마다 달라짐) 다중 시드 요약에서는 같은 {@code tripId}끼리 평균낸다.
 */
public record TripMetric(
        String truckId,
        String tripId,
        double allocatedCapacityKg,
        double initialLoadKg,
        double availablePickupCapacityKg,
        double collectedKg,
        double finalLoadKg,
        double unusedCapacityKg,
        double utilizationPercent,
        int partialPickupCount
) {}
