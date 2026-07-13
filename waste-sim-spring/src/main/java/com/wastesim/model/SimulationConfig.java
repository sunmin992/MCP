package com.wastesim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulationConfig {

    private int collectionTimeMinutes = 12 * 60;   // 12:00 (720 min)
    private int days = 30;
    private int seeds = 30;
    private double leaveSigma = 30.0;
    private double wasteSigma = 0.3;
    private double capacity = 30.0;
    private double threshold = 0.8;
    private int numBuildings = 4;
    private int residentsPerBuilding = 25;

    /**
     * 거주민 직업 구성 비율 (round-robin 배정). null/빈 값이면 생산직·학생·주부 균등.
     * 예: ["Student","Student","BlueCollar"] → 학생 2 : 생산직 1 비율.
     */
    private List<String> occupationMix = null;

    // ── 수거 스케줄 (다회/격일/평일·주말/공휴일) ────────────────────────────
    /** 하루 중 수거 시각 목록(분). null이면 collectionTimeMinutes 1회. 예: [540,1080] = 09:00·18:00 */
    private List<Integer> collectionTimesMinutes = null;
    /** 수거 주기(일). 1=매일, 2=격일제 */
    private int collectionIntervalDays = 1;
    /** 주말(토·일) 미수거 여부 */
    private boolean skipWeekends = false;
    /** 주말 별도 수거 시각(분). skipWeekends=false일 때만 적용. null이면 평일과 동일 */
    private Integer weekendCollectionTimeMinutes = null;
    /** 공휴일(미수거) day 인덱스 목록 */
    private List<Integer> holidays = null;

    // ── 다중 트럭 / 구역 분할 ───────────────────────────────────────────────
    /** 트럭 수. 건물을 트럭 수로 나눠 구역(zone)별 순회 */
    private int numTrucks = 1;
    /** 순회 이동시간(분/건물). >0이면 경로 후반 건물일수록 수거가 늦어짐(구역 분할 효과) */
    private int routeTravelMinutes = 0;

    // ── 분리배출 ────────────────────────────────────────────────────────────
    /** 쓰레기 종류별 수거장. null이면 통합 단일 수거장(cfg.capacity/threshold/interval) */
    private List<WasteType> wasteTypes = null;

    // ── 결합모델 변형 ──────────────────────────────────────────────────────
    /** 외출·귀가 2회 배출 */
    private boolean returnDischarge = false;
    /** 귀가 시 배출 비율(0~1). 나머지는 외출 시 배출 */
    private double returnFraction = 0.4;
    /** 임대인(Check만 가진) 에이전트: 매일 수거장 점검 후 더러우면 민원 */
    private boolean landlordEnabled = false;
    private int landlordInspectMinutes = 20 * 60;  // 20:00 점검
    private double landlordThreshold = 0.6;

    // ── 월별(계절) 배출량 변동 ─────────────────────────────────────────────
    /** 12개월 계절 가중치(1.0=평년). null이면 변동 없음(모든 달 동일). 30일=1달 기준. */
    private double[] monthlyWasteFactor = null;

    // ── Getters & setters ──────────────────────────────────────────────────

    public int getCollectionTimeMinutes() { return collectionTimeMinutes; }
    public void setCollectionTimeMinutes(int v) { this.collectionTimeMinutes = v; }

    public int getDays() { return days; }
    public void setDays(int v) { this.days = v; }

    public int getSeeds() { return seeds; }
    public void setSeeds(int v) { this.seeds = v; }

    public double getLeaveSigma() { return leaveSigma; }
    public void setLeaveSigma(double v) { this.leaveSigma = v; }

    public double getWasteSigma() { return wasteSigma; }
    public void setWasteSigma(double v) { this.wasteSigma = v; }

    public double getCapacity() { return capacity; }
    public void setCapacity(double v) { this.capacity = v; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double v) { this.threshold = v; }

    public int getNumBuildings() { return numBuildings; }
    public void setNumBuildings(int v) { this.numBuildings = v; }

    public int getResidentsPerBuilding() { return residentsPerBuilding; }
    public void setResidentsPerBuilding(int v) { this.residentsPerBuilding = v; }

    public List<String> getOccupationMix() { return occupationMix; }
    public void setOccupationMix(List<String> v) { this.occupationMix = v; }

    public List<Integer> getCollectionTimesMinutes() { return collectionTimesMinutes; }
    public void setCollectionTimesMinutes(List<Integer> v) { this.collectionTimesMinutes = v; }

    public int getCollectionIntervalDays() { return collectionIntervalDays; }
    public void setCollectionIntervalDays(int v) { this.collectionIntervalDays = Math.max(1, v); }

    public boolean isSkipWeekends() { return skipWeekends; }
    public void setSkipWeekends(boolean v) { this.skipWeekends = v; }

    public Integer getWeekendCollectionTimeMinutes() { return weekendCollectionTimeMinutes; }
    public void setWeekendCollectionTimeMinutes(Integer v) { this.weekendCollectionTimeMinutes = v; }

    public List<Integer> getHolidays() { return holidays; }
    public void setHolidays(List<Integer> v) { this.holidays = v; }

    public int getNumTrucks() { return numTrucks; }
    public void setNumTrucks(int v) { this.numTrucks = Math.max(1, v); }

    public int getRouteTravelMinutes() { return routeTravelMinutes; }
    public void setRouteTravelMinutes(int v) { this.routeTravelMinutes = Math.max(0, v); }

    public List<WasteType> getWasteTypes() { return wasteTypes; }
    public void setWasteTypes(List<WasteType> v) { this.wasteTypes = v; }

    public boolean isReturnDischarge() { return returnDischarge; }
    public void setReturnDischarge(boolean v) { this.returnDischarge = v; }

    public double getReturnFraction() { return returnFraction; }
    public void setReturnFraction(double v) { this.returnFraction = v; }

    public boolean isLandlordEnabled() { return landlordEnabled; }
    public void setLandlordEnabled(boolean v) { this.landlordEnabled = v; }

    public int getLandlordInspectMinutes() { return landlordInspectMinutes; }
    public void setLandlordInspectMinutes(int v) { this.landlordInspectMinutes = v; }

    public double getLandlordThreshold() { return landlordThreshold; }
    public void setLandlordThreshold(double v) { this.landlordThreshold = v; }

    public double[] getMonthlyWasteFactor() { return monthlyWasteFactor; }
    public void setMonthlyWasteFactor(double[] v) { this.monthlyWasteFactor = v; }

    /** 해당 월(0-based)의 계절 가중치. 미지정 시 1.0. */
    public double resolveMonthlyFactor(int monthIndex) {
        if (monthlyWasteFactor == null || monthlyWasteFactor.length == 0) return 1.0;
        return monthlyWasteFactor[monthIndex % monthlyWasteFactor.length];
    }

    // ── 해석(resolve) 헬퍼 ─────────────────────────────────────────────────

    /** 직업 구성을 enum 리스트로 해석. 미지정 시 기본 3종(생산직·학생·주부). */
    public List<OccupationType> resolveOccupationMix() {
        if (occupationMix == null || occupationMix.isEmpty()) {
            return OccupationType.baseMix();
        }
        List<OccupationType> out = new ArrayList<>();
        for (String s : occupationMix) out.add(OccupationType.fromName(s));
        return out;
    }

    /** 쓰레기 종류 해석. 미지정 시 cfg 용량/임계/주기를 쓰는 통합 단일 수거장. */
    public List<WasteType> resolveWasteTypes() {
        if (wasteTypes == null || wasteTypes.isEmpty()) {
            return Collections.singletonList(
                    WasteType.single(capacity, threshold, collectionIntervalDays));
        }
        return wasteTypes;
    }

    /** 평일 수거 시각 슬롯(분) 목록. 다회 수거 지원. */
    public List<Integer> resolveCollectionSlots() {
        if (collectionTimesMinutes != null && !collectionTimesMinutes.isEmpty()) {
            return collectionTimesMinutes;
        }
        return Collections.singletonList(collectionTimeMinutes);
    }

    /** 동일한 설정의 복사본(시나리오 sweep에서 일부 파라미터만 바꿔 재사용). */
    public SimulationConfig copy() {
        SimulationConfig c = new SimulationConfig();
        c.collectionTimeMinutes = collectionTimeMinutes;
        c.days = days;
        c.seeds = seeds;
        c.leaveSigma = leaveSigma;
        c.wasteSigma = wasteSigma;
        c.capacity = capacity;
        c.threshold = threshold;
        c.numBuildings = numBuildings;
        c.residentsPerBuilding = residentsPerBuilding;
        c.occupationMix = (occupationMix == null) ? null : new ArrayList<>(occupationMix);
        c.collectionTimesMinutes = (collectionTimesMinutes == null) ? null : new ArrayList<>(collectionTimesMinutes);
        c.collectionIntervalDays = collectionIntervalDays;
        c.skipWeekends = skipWeekends;
        c.weekendCollectionTimeMinutes = weekendCollectionTimeMinutes;
        c.holidays = (holidays == null) ? null : new ArrayList<>(holidays);
        c.numTrucks = numTrucks;
        c.routeTravelMinutes = routeTravelMinutes;
        c.wasteTypes = (wasteTypes == null) ? null : new ArrayList<>(wasteTypes);
        c.returnDischarge = returnDischarge;
        c.returnFraction = returnFraction;
        c.landlordEnabled = landlordEnabled;
        c.landlordInspectMinutes = landlordInspectMinutes;
        c.landlordThreshold = landlordThreshold;
        c.monthlyWasteFactor = (monthlyWasteFactor == null) ? null : monthlyWasteFactor.clone();
        return c;
    }

    /** "HH:MM" 형식 문자열로 수거 시각 반환 */
    public String getCollectionTimeLabel() {
        return minutesToHhmm(collectionTimeMinutes);
    }

    /** "HH:MM" 문자열로 수거 시각 설정 */
    public void setCollectionTimeLabel(String hhmm) {
        this.collectionTimeMinutes = hhmmToMinutes(hhmm);
    }

    /** 자정 기준 분 → "HH:MM" 문자열. (ScenarioService.hhmm()과 중복이던 걸 통합) */
    public static String minutesToHhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** "HH:MM" 문자열 → 자정 기준 분. (ScenarioController.toMinutes()와 중복이던 걸 통합) */
    public static int hhmmToMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        int h = Integer.parseInt(parts[0].trim());
        int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
        return h * 60 + m;
    }
}
