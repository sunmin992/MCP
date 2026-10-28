package com.parkingsim.data;

import java.time.Instant;
import java.util.List;

/** Immutable API input, frozen before confirmation. fetchedAt is NOT a sensor timestamp. */
public record ParkingSnapshot(String snapshotId, String source, String fetchedAt,
                              String sigunguCd, List<Lot> lots, String occupancySource,
                              java.util.Map<String, java.util.Map<String, String>> facilityDetails,
                              Integer assumedStartMinute) {
    public ParkingSnapshot(String id, String source, String fetchedAt, String region, List<Lot> lots) {
        this(id, source, fetchedAt, region, lots, "REALTIME_API", java.util.Map.of(), null);
    }
    public ParkingSnapshot {
        lots = List.copyOf(lots);
        // Sorted immutable metadata keeps confirmation hashes deterministic.
        var copy = new java.util.TreeMap<String, java.util.Map<String, String>>();
        facilityDetails.forEach((id, fields) -> copy.put(id,
                java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(fields))));
        facilityDetails = java.util.Collections.unmodifiableMap(copy);
    }
    public record Lot(String id, String name, String address, String latitude, String longitude,
                      int facilitySpaces, int totalSpaces, int availableSpaces) {
        public int occupied() { return totalSpaces - availableSpaces; }
    }
    /** 표준 모드는 사용자가 빈자리를 가정한 시각에서 시작한다. 조회 시각은 그 상태와 무관하다. */
    public int startMinute() {
        if (assumedStartMinute != null) return assumedStartMinute;
        var time = Instant.parse(fetchedAt).atZone(java.time.ZoneId.of("Asia/Seoul"));
        return time.getHour() * 60 + time.getMinute();
    }
}
