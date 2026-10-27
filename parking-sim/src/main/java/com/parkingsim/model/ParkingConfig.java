package com.parkingsim.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 공영주차장 시뮬레이션 실행 설정 한 벌.
 *
 * <p>세터가 있는 필드가 사용자가 정할 수 있는 값이다. 초기값이 {@code null} 인 필드는 기본값이 없다 —
 * 서브태스크 템플릿이 반드시 묻고, 검증기가 비어 있으면 막는다. 조용히 대신할 값을 두지 않는다.
 *
 * <p>게터는 확인 화면과 확인 토큰 해시가 그대로 읽는다. 계산용 메서드는 {@code get}/{@code is} 로
 * 시작하지 않게 짓는다 — 그러면 해시와 화면에 섞인다.
 */
public class ParkingConfig {
    private com.parkingsim.data.ParkingSnapshot parkingSnapshot;
    private Integer initialVehicleRemainingMinutes;
    public com.parkingsim.data.ParkingSnapshot getParkingSnapshot() { return parkingSnapshot; }
    public void setParkingSnapshot(com.parkingsim.data.ParkingSnapshot snapshot) { this.parkingSnapshot = snapshot; }
    public Integer getInitialVehicleRemainingMinutes() { return initialVehicleRemainingMinutes; }
    public void setInitialVehicleRemainingMinutes(Integer minutes) { this.initialVehicleRemainingMinutes = minutes; }
    public int spacesAt(int lot) { return parkingSnapshot == null ? spacesPerLot : parkingSnapshot.lots().get(lot).totalSpaces(); }
    public int startMinute() { return parkingSnapshot == null ? 0 : parkingSnapshot.startMinute(); }

    /** 주차장 수. 주차장은 한 줄로 늘어서 있고 이웃한 두 곳 사이를 {@link #lotSpacingMinutes} 에 간다. */
    private int lotCount = 4;
    /** 주차장 한 곳의 면수. 모든 주차장이 같다. */
    private int spacesPerLot = 80;
    /** 요금 정책. {@link PricingPolicy} 이름. */
    private String pricingPolicy = PricingPolicy.FLAT.name();
    /** 30분당 기본 요금(원). */
    private int baseFeePer30Min = 500;
    /** 혼잡 시간대 할증률(0.5 = 50% 더). 시간대 차등일 때만 쓴다. 기본값 없음. */
    private Double peakSurchargeRate;
    /** 이용자 구성. 목록 순서대로 차량에 돌아가며 배정하고, 같은 유형을 여러 번 적으면 비중이 커진다. */
    private List<String> userMix = new ArrayList<>(List.of("Commuter", "Shopper", "Visitor"));
    /** 각 주차장에서 거주자만 쓸 수 있는 면의 비율. 거주자가 구성에 있을 때만 쓴다. 기본값 없음. */
    private Double residentPriorityShare;
    /** 하루 도착 차량 수(요금 회피 전). */
    private int dailyArrivals = 900;
    /** 안내 방식. {@link GuidanceMode} 이름. */
    private String guidanceMode = GuidanceMode.NONE.name();
    /** 실시간 앱의 빈자리 갱신 주기(분). */
    private int guidanceRefreshMinutes = 5;
    /** 도로 혼잡을 주차장 간 이동시간에 반영하는가. */
    private boolean congestionEnabled = false;
    /** 혼잡 프로파일 id. 혼잡을 반영할 때만 쓴다. */
    private String congestionProfileId = "weekday-downtown";
    /** 이 시간 안에 못 찾으면 포기한다(분). 기본값 없음. */
    private Integer maxSearchMinutes;
    /** 기간(일). */
    private int days = 14;
    /** 반복 횟수. 시드는 1..seeds 로 고정이다. */
    private int seeds = 30;

    /** 이웃한 주차장 사이 자유주행 이동시간(분). 템플릿에 없다 — 가정값. */
    private double lotSpacingMinutes = 3.0;
    /** 안내 없이 만차 주차장에 들어가 한 바퀴 도는 시간(분). 템플릿에 없다 — 가정값. */
    private double fullLotCircleMinutes = 2.0;

    public int getLotCount() { return lotCount; }
    public void setLotCount(int lotCount) { this.lotCount = lotCount; }

    public int getSpacesPerLot() { return spacesPerLot; }
    public void setSpacesPerLot(int spacesPerLot) { this.spacesPerLot = spacesPerLot; }

    public String getPricingPolicy() { return pricingPolicy; }
    public void setPricingPolicy(String pricingPolicy) { this.pricingPolicy = pricingPolicy; }

    public int getBaseFeePer30Min() { return baseFeePer30Min; }
    public void setBaseFeePer30Min(int baseFeePer30Min) { this.baseFeePer30Min = baseFeePer30Min; }

    public Double getPeakSurchargeRate() { return peakSurchargeRate; }
    public void setPeakSurchargeRate(Double peakSurchargeRate) { this.peakSurchargeRate = peakSurchargeRate; }

    public List<String> getUserMix() { return userMix; }
    public void setUserMix(List<String> userMix) { this.userMix = userMix == null ? null : new ArrayList<>(userMix); }

    public Double getResidentPriorityShare() { return residentPriorityShare; }
    public void setResidentPriorityShare(Double residentPriorityShare) { this.residentPriorityShare = residentPriorityShare; }

    public int getDailyArrivals() { return dailyArrivals; }
    public void setDailyArrivals(int dailyArrivals) { this.dailyArrivals = dailyArrivals; }

    public String getGuidanceMode() { return guidanceMode; }
    public void setGuidanceMode(String guidanceMode) { this.guidanceMode = guidanceMode; }

    public int getGuidanceRefreshMinutes() { return guidanceRefreshMinutes; }
    public void setGuidanceRefreshMinutes(int guidanceRefreshMinutes) { this.guidanceRefreshMinutes = guidanceRefreshMinutes; }

    public boolean isCongestionEnabled() { return congestionEnabled; }
    public void setCongestionEnabled(boolean congestionEnabled) { this.congestionEnabled = congestionEnabled; }

    public String getCongestionProfileId() { return congestionProfileId; }
    public void setCongestionProfileId(String congestionProfileId) { this.congestionProfileId = congestionProfileId; }

    public Integer getMaxSearchMinutes() { return maxSearchMinutes; }
    public void setMaxSearchMinutes(Integer maxSearchMinutes) { this.maxSearchMinutes = maxSearchMinutes; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public int getSeeds() { return seeds; }
    public void setSeeds(int seeds) { this.seeds = seeds; }

    public double getLotSpacingMinutes() { return lotSpacingMinutes; }
    public void setLotSpacingMinutes(double lotSpacingMinutes) { this.lotSpacingMinutes = lotSpacingMinutes; }

    public double getFullLotCircleMinutes() { return fullLotCircleMinutes; }
    public void setFullLotCircleMinutes(double fullLotCircleMinutes) { this.fullLotCircleMinutes = fullLotCircleMinutes; }

    // ── 계산용 (게터가 아니다) ─────────────────────────────────────

    public PricingPolicy resolvePricingPolicy() { return PricingPolicy.fromName(pricingPolicy); }

    public GuidanceMode resolveGuidanceMode() { return GuidanceMode.fromName(guidanceMode); }

    public List<UserType> resolveUserMix() {
        List<UserType> out = new ArrayList<>();
        for (String s : userMix) out.add(UserType.fromName(s));
        return out;
    }

    public boolean residentsPresent() {
        return userMix != null && userMix.stream().anyMatch(s -> "resident".equalsIgnoreCase(s));
    }

    public int totalSpaces() { return parkingSnapshot == null ? lotCount * spacesPerLot
            : parkingSnapshot.lots().stream().mapToInt(com.parkingsim.data.ParkingSnapshot.Lot::totalSpaces).sum(); }
}
