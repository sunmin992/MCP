package com.districtsim.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 행정동 생활폐기물 수거 실행 설정 한 벌.
 *
 * <p>세터가 있는 필드가 정할 수 있는 값이다. 초기값이 {@code null} 인 필드는 기본값이 없다 — 템플릿이
 * 반드시 묻고, 필요한 설정에서 비어 있으면 검증기가 막는다.
 *
 * <p>게터는 확인 화면과 확인 토큰 해시가 그대로 읽는다. 계산용 메서드는 {@code get}/{@code is} 로 시작하지 않는다.
 */
public class DistrictConfig {

    /** 집계 구역 수. 구역은 번호 순으로 이어져 있고 차량은 이웃 구역 묶음을 맡는다. */
    private int zoneCount = 8;
    /** 구역당 세대 수(상가 구역이면 점포 수). */
    private int householdsPerZone = 600;
    /** 주거 유형 구성. 구역 번호 순서대로 돌아가며 배정하고, 같은 유형을 여러 번 적으면 비중이 커진다. */
    private List<String> housingMix = new ArrayList<>(List.of("Apartment", "Detached", "Studio"));
    /** 상가 쓰레기를 이 수거에 포함하는가. INCLUDED / EXCLUDED(사업장 자체 처리). */
    private String commercialHandling = "INCLUDED";
    private String truckType = TruckType.LARGE_5TON.name();
    private int truckCount = 2;
    /** 구역을 차량에 나누는 방식. EQUAL_COUNT(같은 구역 수) / BALANCED_LOAD(예상 작업 시간이 같게). */
    private String zoneAllocation = "EQUAL_COUNT";
    /** 수거 일정. {@link ScheduleMode} 이름. */
    private String scheduleMode = ScheduleMode.POHANG_BUKGU.name();
    /** 사용자가 고른 수거 요일(MON…SUN). WEEKDAYS 일 때만 쓴다. 기본값 없음. */
    private List<String> collectionDays;
    /** 공휴일을 반영하는가. */
    private boolean holidayEnabled = false;
    /** 수거일이 공휴일일 때. {@link HolidayPolicy} 이름. 기본값 없음. */
    private String holidayPolicy;
    /** 적환장을 쓰는가. 쓰지 않으면 차가 찰 때마다 소각장까지 왕복한다. */
    private boolean transferEnabled = false;
    /** 적환장 왕복 시간(분). 기본값 없음. */
    private Integer transferRoundTripMinutes;
    /** 하루 수거 시작 시각(자정 기준 분). */
    private int collectionStartMinutes = 360;
    private int days = 28;
    private int seeds = 30;

    // ── 템플릿에 없는 값(가정) ─────────────────────────────────────
    /** 시뮬레이션 0일차 날짜. 추석 연휴(2026-09-24~26)가 기간에 들도록 정했다. */
    private String startDate = "2026-09-21";
    /** 차량 한 대의 하루 근무 시간(분). */
    private int shiftMinutes = 480;
    /** 적환장이 없을 때 소각장 왕복 시간(분). */
    private int disposalRoundTripMinutes = 90;
    /** 이웃 구역 사이 이동시간(분, 5톤 기준). */
    private double zoneTravelMinutes = 8.0;
    /** 차고지 → 첫 구역 이동시간(분, 5톤 기준). */
    private double depotTravelMinutes = 15.0;
    /** 구역 하루 배출량의 변동계수. */
    private double generationCv = 0.15;

    public int getZoneCount() { return zoneCount; }
    public void setZoneCount(int zoneCount) { this.zoneCount = zoneCount; }

    public int getHouseholdsPerZone() { return householdsPerZone; }
    public void setHouseholdsPerZone(int householdsPerZone) { this.householdsPerZone = householdsPerZone; }

    public List<String> getHousingMix() { return housingMix; }
    public void setHousingMix(List<String> housingMix) { this.housingMix = housingMix == null ? null : new ArrayList<>(housingMix); }

    public String getCommercialHandling() { return commercialHandling; }
    public void setCommercialHandling(String commercialHandling) { this.commercialHandling = commercialHandling; }

    public String getTruckType() { return truckType; }
    public void setTruckType(String truckType) { this.truckType = truckType; }

    public int getTruckCount() { return truckCount; }
    public void setTruckCount(int truckCount) { this.truckCount = truckCount; }

    public String getZoneAllocation() { return zoneAllocation; }
    public void setZoneAllocation(String zoneAllocation) { this.zoneAllocation = zoneAllocation; }

    public String getScheduleMode() { return scheduleMode; }
    public void setScheduleMode(String scheduleMode) { this.scheduleMode = scheduleMode; }

    public List<String> getCollectionDays() { return collectionDays; }
    public void setCollectionDays(List<String> collectionDays) { this.collectionDays = collectionDays == null ? null : new ArrayList<>(collectionDays); }

    public boolean isHolidayEnabled() { return holidayEnabled; }
    public void setHolidayEnabled(boolean holidayEnabled) { this.holidayEnabled = holidayEnabled; }

    public String getHolidayPolicy() { return holidayPolicy; }
    public void setHolidayPolicy(String holidayPolicy) { this.holidayPolicy = holidayPolicy; }

    public boolean isTransferEnabled() { return transferEnabled; }
    public void setTransferEnabled(boolean transferEnabled) { this.transferEnabled = transferEnabled; }

    public Integer getTransferRoundTripMinutes() { return transferRoundTripMinutes; }
    public void setTransferRoundTripMinutes(Integer transferRoundTripMinutes) { this.transferRoundTripMinutes = transferRoundTripMinutes; }

    public int getCollectionStartMinutes() { return collectionStartMinutes; }
    public void setCollectionStartMinutes(int collectionStartMinutes) { this.collectionStartMinutes = collectionStartMinutes; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public int getSeeds() { return seeds; }
    public void setSeeds(int seeds) { this.seeds = seeds; }

    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public int getShiftMinutes() { return shiftMinutes; }
    public void setShiftMinutes(int shiftMinutes) { this.shiftMinutes = shiftMinutes; }

    public int getDisposalRoundTripMinutes() { return disposalRoundTripMinutes; }
    public void setDisposalRoundTripMinutes(int disposalRoundTripMinutes) { this.disposalRoundTripMinutes = disposalRoundTripMinutes; }

    public double getZoneTravelMinutes() { return zoneTravelMinutes; }
    public void setZoneTravelMinutes(double zoneTravelMinutes) { this.zoneTravelMinutes = zoneTravelMinutes; }

    public double getDepotTravelMinutes() { return depotTravelMinutes; }
    public void setDepotTravelMinutes(double depotTravelMinutes) { this.depotTravelMinutes = depotTravelMinutes; }

    public double getGenerationCv() { return generationCv; }
    public void setGenerationCv(double generationCv) { this.generationCv = generationCv; }

    // ── 계산용 (게터가 아니다) ─────────────────────────────────────

    public TruckType resolveTruckType() { return TruckType.fromName(truckType); }

    public ScheduleMode resolveScheduleMode() { return ScheduleMode.fromName(scheduleMode); }

    public List<HousingType> resolveHousingMix() {
        List<HousingType> out = new ArrayList<>();
        for (String s : housingMix) out.add(HousingType.fromName(s));
        return out;
    }

    public boolean commercialPresent() {
        return housingMix != null && housingMix.stream().anyMatch(s -> "commercial".equalsIgnoreCase(s));
    }

    public boolean balancedAllocation() {
        return "BALANCED_LOAD".equalsIgnoreCase(zoneAllocation);
    }

    public boolean commercialExcluded() {
        return "EXCLUDED".equalsIgnoreCase(commercialHandling);
    }

    /** 이 일정의 수거 요일 번호(0=월). WEEKDAYS 면 사용자 목록. */
    public List<Integer> resolveCollectionDays() {
        ScheduleMode mode = resolveScheduleMode();
        if (mode != ScheduleMode.WEEKDAYS) return mode.days;
        List<Integer> out = new ArrayList<>();
        if (collectionDays != null) for (String d : collectionDays) out.add(ScheduleMode.dayIndex(d));
        return out;
    }

    /** 차가 찰 때마다 비우러 다녀오는 시간. 적환장을 쓰면 적환장 왕복, 아니면 소각장 왕복. */
    public int unloadRoundTripMinutes() {
        return transferEnabled && transferRoundTripMinutes != null ? transferRoundTripMinutes : disposalRoundTripMinutes;
    }

    /** 실제로 일하는 차량 수 — 구역보다 차가 많으면 맡을 구역이 없는 차는 나가지 않는다. */
    public int effectiveTrucks() { return Math.max(0, Math.min(truckCount, zoneCount)); }
}
