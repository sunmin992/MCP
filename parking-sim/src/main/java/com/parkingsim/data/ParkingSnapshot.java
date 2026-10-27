package com.parkingsim.data;

import java.time.Instant;
import java.util.List;

/** Immutable API input, frozen before confirmation. fetchedAt is NOT a sensor timestamp. */
public record ParkingSnapshot(String snapshotId, String source, String fetchedAt,
                              String sigunguCd, List<Lot> lots) {
    public ParkingSnapshot { lots = List.copyOf(lots); }
    public record Lot(String id, String name, String address, String latitude, String longitude,
                      int facilitySpaces, int totalSpaces, int availableSpaces) {
        public int occupied() { return totalSpaces - availableSpaces; }
    }
    public int startMinute() {
        var time = Instant.parse(fetchedAt).atZone(java.time.ZoneId.of("Asia/Seoul"));
        return time.getHour() * 60 + time.getMinute();
    }
}
