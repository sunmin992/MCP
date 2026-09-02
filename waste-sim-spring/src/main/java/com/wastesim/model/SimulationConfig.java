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
    /**
     * 1인 1일 평균 배출량(kg). 기본값 0.9는 원 논문(Table 1, Gallup Korea 2014
     * 기반)의 가정치를 그대로 유지한다(Python 참조 엔진과의 결과 비교 가능성을
     * 지키기 위해 기본값은 바꾸지 않았다). 환경부 전국폐기물통계조사 최신
     * 결과(전국 평균 약 0.95kg/인·일, 지역 편차 0.8~1.7kg — 실측_보정_가이드.md
     * 참고)로 교체해 실측 캘리브레이션할 때 이 필드를 사용한다. Python
     * 참조 엔진(occupations.py)에는 이 값이 전달되지 않고 항상 0.9로 고정된다
     * — "논문 원본 재현"이라는 그쪽의 목적을 지키기 위해서다.
     */
    private double wasteMeanKg = 0.9;
    private double capacity = 30.0;
    private double threshold = 0.8;
    /**
     * 시나리오 규모 이름. {@code null}이면 필드 기본값(= 논문 기준선)을 그대로 쓴다.
     *
     * <p>{@link ScenarioScale#JANGRYANG_CAPACITY}를 고르면 건물 수·동당 거주민·지점
     * 용량·차종·대수 다섯 필드가 그 규모로 채워진다. 명시적으로 준 개별 값이 있으면
     * <b>그쪽이 이긴다</b> — 규모는 출발점이고 최종 결정이 아니다.
     *
     * <p>필드 기본값을 이 규모로 바꾸지 않은 이유는 논문 재현이 4동 25명에 걸려 있어서다.
     * 기본값을 옮기면 기존 결과가 조용히 달라진다.
     */
    private String scenarioScale = null;

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

    /**
     * 수거하는 요일 집합. <b>0=월 1=화 2=수 3=목 4=금 5=토 6=일</b>이며
     * {@code SimulationEngine}의 {@code day % 7} 규약과 같다 — 0일차가 월요일이다.
     *
     * <p>{@code null}이면 기존처럼 {@link #collectionIntervalDays}로 판정한다. 기본이
     * {@code null}이라 지정하지 않으면 결과가 예전과 완전히 같다.
     *
     * <p><b>주기로는 표현되지 않는 스케줄이 있다.</b> 포항시 북구의 실제 생활쓰레기 수거는
     * 월·화·목·금인데(공식 배출요일 일·월·수·목에 하루를 더한 것 —
     * {@code schedule/pohang-bukgu-collection-schedule.json}), 이걸 "N일마다"로 쓸 방법이
     * 없다. 요일 집합이 그 자리를 채운다.
     *
     * <p>{@code collectionIntervalDays}와 함께 지정하면 검증기가 거부한다 — 두 가지 다른
     * 스케줄 방식이고, 조용히 둘 다 적용하면 아무 날도 수거하지 않는 설정이 만들어질 수 있다.
     */
    private List<Integer> collectionDaysOfWeek = null;

    /**
     * 배출 시각 모델. 기본은 논문 모델이며 결과가 예전과 완전히 같다.
     * 값은 {@link DischargeTimeMode#fromName(String)}이 해석한다.
     */
    private String dischargeTimeMode = DischargeTimeMode.PAPER_BASELINE.name();

    /**
     * 같은 교통 구역 안에서 지점을 옮길 때 걸리는 분.
     * {@link TravelTimeMode#ZONE_PROXY_HYBRID}에서만 쓴다.
     *
     * <p><b>측정된 값이 아니고, 기본값도 없다.</b> 구역 간 행렬에는 대각 성분이 없고 있을
     * 수도 없어서(구역은 점이 아니라 영역이다), 같은 구역 안의 이동은 측정할 대상이 아예
     * 없고 누군가 정해 줘야 한다.
     *
     * <p>{@code null}은 "지정하지 않았다"는 뜻이며 0과 다르다. 0은 <b>구역 안을 이동하는 데
     * 시간이 들지 않는다</b>는 강한 하한 가정이다 — 한때 이 필드의 기본값이 0이었는데,
     * 그러면 아무 값도 주지 않은 실행이 조용히 그 가정을 쓰게 된다. 이 프로젝트의 다른
     * 미측정 입력은 모두 실행을 막는 쪽을 택했다(V-T6이 자유주행시간 없는 구간을 막는 것과
     * 같은 이유다). 그래서 —
     *
     * <ul>
     *   <li>같은 구역이 연속되는 이동이 <b>없으면</b> 값 없이 실행된다 — 쓸 자리가 없다.</li>
     *   <li>그런 이동이 있는데 값이 없으면 <b>검증이 막는다</b>(V-T7).</li>
     *   <li>명시적으로 지정하면(0 포함) 그 값으로 실행하고, 결과에
     *       {@code INTRA_ZONE_TIME_ASSUMED} 표시를 붙인다.</li>
     * </ul>
     *
     * <p>0·5·10분으로 민감도를 함께 보고하는 것이 이 파라미터의 올바른 사용법이다.
     */
    private Integer intraZoneTravelMinutes = null;

    /**
     * 배출 허용 창의 시작 시각(자정 기준 분). 기본 1200 = 20:00.
     * {@link DischargeTimeMode#POHANG_ACTUAL}에서만 쓴다.
     */
    private int dischargeWindowStartMinutes = 20 * 60;

    /**
     * 배출 허용 창의 종료 시각(자정 기준 분). 기본 360 = 06:00 — 시작보다 작으므로
     * <b>자정을 넘는 창</b>이다. 포항시 북구 공식 배출 시각이 20:00~06:00이다
     * ({@code schedule/pohang-bukgu-collection-schedule.json}).
     */
    private int dischargeWindowEndMinutes = 6 * 60;
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

    /**
     * 구간 이동시간을 무엇으로 계산할지. 기본은 상수 모드이며 기존 결과를 그대로 낸다.
     * 값은 {@link TravelTimeMode#fromName(String)}이 해석한다.
     */
    private String travelTimeMode = TravelTimeMode.LEGACY_CONSTANT.name();

    /**
     * 지점 하나에서의 정차·상차 시간(분). 혼합 모드에서만 쓴다.
     *
     * <p>기본이 0인 이유는 상수 모드의 기본값 15분이 이미 정차분을 떠맡고 있을 수 있어서다 —
     * 둘을 함께 세면 이중 계산이 된다. 혼합 모드로 갈 때 이 값을 명시적으로 정하는 것이
     * "이동시간과 정차시간을 분리한다"의 실제 내용이다.
     *
     * <p><b>지점마다 붙는다</b> — 첫 지점도 포함해서 방문하는 모든 지점에 한 번씩이다. 그
     * 운행에서 첫 지점도 실제로 수거하기 때문이다. 한때 이동 구간에만 붙어서 지점 4곳에
     * 정차시간이 3번만 들어갔고, 그 상태에서는 이름과 계산이 어긋났다.
     *
     * <p><b>측정할 방법</b> — 거창한 API가 필요하지 않다. 현장에서 세 시각만 적으면 된다.
     *
     * <pre>
     *   접근·주차시간   = 수거 시작 - 도착
     *   상차시간        = 출발     - 수거 시작
     *   전체 서비스시간 = 출발     - 도착      &lt;- 이 파라미터
     * </pre>
     *
     * <p>20~30회 기록하고 <b>평균 하나가 아니라 중앙값과 상위 90%</b>를 쓴다. 순회 시간은
     * 느린 쪽 꼬리에 좌우되므로 평균만 보면 계획이 낙관적으로 기울고, 두 값을 함께 보고하면
     * 그 폭이 드러난다.
     *
     * <p>수거량이나 지점 유형별 차이가 크면 나중에 이렇게 나눌 수 있다 —
     * {@code 기본 정차시간 + 수거량(kg) × kg당 상차시간 + 지점 유형별 가중치}. 기록이
     * 20~30건 모이기 전에 이 구조를 먼저 만들 이유는 없다.
     */
    private int serviceMinutesPerSite = 0;

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

    // ── 교통 레이어 (TRAFFIC_EXTENSION_DESIGN.md §3) ────────────────────────
    /** 교통 레이어 사용 여부. false면 기존 동작과 완전히 동일(하위호환). */
    private boolean trafficEnabled = false;
    /** 적용할 TrafficProfile id (예: "jangryang-weekday"). null이면 미적용. */
    private String trafficProfileId = null;
    /** 차량 종류(TruckType 이름). */
    private String truckType = "LARGE_5TON";
    /** 운행 1회당 이 경로에 배정된 적재용량(kg). null이면 차종 정격용량 전체. */
    private Double routeAvailableCapacityKg = null;
    /** 운행 시작 시 이미 실려 있는 적재량(kg). */
    private double initialTruckLoadKg = 0.0;
    /** 트럭 간 시차 배차(분). >0이면 트럭 k의 출발이 slot+k*interval로 분산. */
    private int dispatchIntervalMinutes = 0;
    /** 수거장 방문 순서(노드 id, 예: "Node_A"). null이면 기본(round-robin) 순서. */
    private List<String> routeSequence = null;
    /** 교통 유발 민원 가중(RED 구간 통과 시 total에 더해지는 값). */
    private double trafficComplaintWeight = 1.0;

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

    public double getWasteMeanKg() { return wasteMeanKg; }
    public void setWasteMeanKg(double v) { this.wasteMeanKg = v; }

    public double getCapacity() { return capacity; }
    public void setCapacity(double v) { this.capacity = v; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double v) { this.threshold = v; }

    public String getScenarioScale() { return scenarioScale; }
    public void setScenarioScale(String v) { this.scenarioScale = v; }

    /** 해석된 시나리오 규모. 값이 없으면 논문 기준선. 알 수 없으면 예외(검증기가 잡는다). */
    public ScenarioScale resolveScenarioScale() {
        return ScenarioScale.fromName(scenarioScale);
    }

    public int getNumBuildings() { return numBuildings; }
    public void setNumBuildings(int v) { this.numBuildings = v; }

    public int getResidentsPerBuilding() { return residentsPerBuilding; }
    public void setResidentsPerBuilding(int v) { this.residentsPerBuilding = v; }

    public List<String> getOccupationMix() { return occupationMix; }
    public void setOccupationMix(List<String> v) { this.occupationMix = v; }

    public List<Integer> getCollectionTimesMinutes() { return collectionTimesMinutes; }
    public void setCollectionTimesMinutes(List<Integer> v) { this.collectionTimesMinutes = v; }

    public Integer getIntraZoneTravelMinutes() { return intraZoneTravelMinutes; }
    public void setIntraZoneTravelMinutes(Integer v) { this.intraZoneTravelMinutes = v; }

    /** 구역 내 이동시간이 지정됐는가. 명시적 0과 미지정을 구분한다. */
    public boolean hasIntraZoneTravelMinutes() { return intraZoneTravelMinutes != null; }

    public String getDischargeTimeMode() { return dischargeTimeMode; }
    public void setDischargeTimeMode(String v) { this.dischargeTimeMode = v; }

    /** 해석된 배출 시각 모드. 값이 없으면 논문 모델. 알 수 없는 값이면 예외(검증기가 잡는다). */
    public DischargeTimeMode resolveDischargeTimeMode() {
        return DischargeTimeMode.fromName(dischargeTimeMode);
    }

    public int getDischargeWindowStartMinutes() { return dischargeWindowStartMinutes; }
    public void setDischargeWindowStartMinutes(int v) { this.dischargeWindowStartMinutes = v; }

    public int getDischargeWindowEndMinutes() { return dischargeWindowEndMinutes; }
    public void setDischargeWindowEndMinutes(int v) { this.dischargeWindowEndMinutes = v; }

    /**
     * 배출 창의 길이(분). 종료가 시작보다 작으면 자정을 넘는 창으로 본다 —
     * 20:00~06:00이면 600분이다.
     */
    public int dischargeWindowSpanMinutes() {
        int start = dischargeWindowStartMinutes, end = dischargeWindowEndMinutes;
        return end > start ? end - start : (1440 - start) + end;
    }

    public List<Integer> getCollectionDaysOfWeek() { return collectionDaysOfWeek; }
    public void setCollectionDaysOfWeek(List<Integer> v) { this.collectionDaysOfWeek = v; }

    /** 요일 집합으로 스케줄을 정하는가. 아니면 {@link #getCollectionIntervalDays()}를 쓴다. */
    public boolean usesDaysOfWeek() {
        return collectionDaysOfWeek != null && !collectionDaysOfWeek.isEmpty();
    }

    public int getCollectionIntervalDays() { return collectionIntervalDays; }
    /**
     * 받은 값을 그대로 보관한다 — 범위 판정은 {@code SimulationConfigValidator}가 한다.
     *
     * <p>예전에는 {@code Math.max(1, v)}로 보정했는데, 그러면 주기 0을 요청해도 매일
     * 수거로 조용히 바뀌어 검증기가 잘못된 값을 볼 기회 자체가 없었다. 보정은 오류를
     * 없애는 게 아니라 <b>보이지 않게</b> 만든다.
     */
    public void setCollectionIntervalDays(int v) { this.collectionIntervalDays = v; }

    public boolean isSkipWeekends() { return skipWeekends; }
    public void setSkipWeekends(boolean v) { this.skipWeekends = v; }

    public Integer getWeekendCollectionTimeMinutes() { return weekendCollectionTimeMinutes; }
    public void setWeekendCollectionTimeMinutes(Integer v) { this.weekendCollectionTimeMinutes = v; }

    public List<Integer> getHolidays() { return holidays; }
    public void setHolidays(List<Integer> v) { this.holidays = v; }

    public int getNumTrucks() { return numTrucks; }
    /**
     * 0 이하 값도 그대로 저장한다(과거엔 Math.max(1,v)로 강제 보정했으나,
     * 그러면 V-T1 검증기가 truckCount=0을 볼 수 없어 시나리오 4의 "모든 트럭
     * 운행 중단" 차단이 무력화됨). 엔진은 자체적으로 Math.max(1,..)로 방어한다.
     */
    public void setNumTrucks(int v) { this.numTrucks = v; }

    public String getTravelTimeMode() { return travelTimeMode; }
    public void setTravelTimeMode(String v) { this.travelTimeMode = v; }

    /** 해석된 모드. 값이 없으면 상수 모드. 알 수 없는 값이면 예외(검증기가 잡는다). */
    public TravelTimeMode resolveTravelTimeMode() { return TravelTimeMode.fromName(travelTimeMode); }

    public int getServiceMinutesPerSite() { return serviceMinutesPerSite; }
    public void setServiceMinutesPerSite(int v) { this.serviceMinutesPerSite = v; }

    public int getRouteTravelMinutes() { return routeTravelMinutes; }
    /** 보정하지 않는다 — 이유는 {@link #setCollectionIntervalDays(int)} 참고. */
    public void setRouteTravelMinutes(int v) { this.routeTravelMinutes = v; }

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

    public boolean isTrafficEnabled() { return trafficEnabled; }
    public void setTrafficEnabled(boolean v) { this.trafficEnabled = v; }

    public String getTrafficProfileId() { return trafficProfileId; }
    public void setTrafficProfileId(String v) { this.trafficProfileId = v; }

    public String getTruckType() { return truckType; }
    public void setTruckType(String v) { this.truckType = v; }

    public Double getRouteAvailableCapacityKg() { return routeAvailableCapacityKg; }
    public void setRouteAvailableCapacityKg(Double v) { this.routeAvailableCapacityKg = v; }

    public double getInitialTruckLoadKg() { return initialTruckLoadKg; }
    public void setInitialTruckLoadKg(double v) { this.initialTruckLoadKg = v; }

    /** {@link #getNumTrucks()}의 별칭(설계서 필드명 truckCount). */
    public int getTruckCount() { return numTrucks; }
    public void setTruckCount(int v) { setNumTrucks(v); }

    public int getDispatchIntervalMinutes() { return dispatchIntervalMinutes; }
    /** 보정하지 않는다 — 이유는 {@link #setCollectionIntervalDays(int)} 참고. */
    public void setDispatchIntervalMinutes(int v) { this.dispatchIntervalMinutes = v; }

    public List<String> getRouteSequence() { return routeSequence; }
    public void setRouteSequence(List<String> v) { this.routeSequence = v; }

    public double getTrafficComplaintWeight() { return trafficComplaintWeight; }
    public void setTrafficComplaintWeight(double v) { this.trafficComplaintWeight = v; }

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
            WasteType single = WasteType.single(capacity, threshold, collectionIntervalDays);
            // 전역 요일 집합을 지정했으면 암묵적 단일 종류도 그 요일을 따라야 한다.
            // 그러지 않으면 이 종류가 collectionIntervalDays를 물려받아, 전역 게이트에서
            // 요일 집합이 주기를 대신했는데도 종류 게이트가 주기로 다시 거부한다 —
            // 수거일이 통째로 사라져 "민원 폭증"이라는 그럴듯한 결과가 나온다.
            return Collections.singletonList(
                    usesDaysOfWeek() ? single.withDaysOfWeek(collectionDaysOfWeek) : single);
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
        c.wasteMeanKg = wasteMeanKg;
        c.capacity = capacity;
        c.threshold = threshold;
        c.numBuildings = numBuildings;
        c.residentsPerBuilding = residentsPerBuilding;
        c.occupationMix = (occupationMix == null) ? null : new ArrayList<>(occupationMix);
        c.collectionTimesMinutes = (collectionTimesMinutes == null) ? null : new ArrayList<>(collectionTimesMinutes);
        c.collectionIntervalDays = collectionIntervalDays;
        c.collectionDaysOfWeek = (collectionDaysOfWeek == null) ? null : new ArrayList<>(collectionDaysOfWeek);
        c.scenarioScale = scenarioScale;
        c.dischargeTimeMode = dischargeTimeMode;
        c.intraZoneTravelMinutes = intraZoneTravelMinutes;
        c.dischargeWindowStartMinutes = dischargeWindowStartMinutes;
        c.dischargeWindowEndMinutes = dischargeWindowEndMinutes;
        c.skipWeekends = skipWeekends;
        c.weekendCollectionTimeMinutes = weekendCollectionTimeMinutes;
        c.holidays = (holidays == null) ? null : new ArrayList<>(holidays);
        c.numTrucks = numTrucks;
        c.routeTravelMinutes = routeTravelMinutes;
        c.travelTimeMode = travelTimeMode;
        c.serviceMinutesPerSite = serviceMinutesPerSite;
        c.wasteTypes = (wasteTypes == null) ? null : new ArrayList<>(wasteTypes);
        c.returnDischarge = returnDischarge;
        c.returnFraction = returnFraction;
        c.landlordEnabled = landlordEnabled;
        c.landlordInspectMinutes = landlordInspectMinutes;
        c.landlordThreshold = landlordThreshold;
        c.monthlyWasteFactor = (monthlyWasteFactor == null) ? null : monthlyWasteFactor.clone();
        c.trafficEnabled = trafficEnabled;
        c.trafficProfileId = trafficProfileId;
        c.truckType = truckType;
        c.routeAvailableCapacityKg = routeAvailableCapacityKg;
        c.initialTruckLoadKg = initialTruckLoadKg;
        c.dispatchIntervalMinutes = dispatchIntervalMinutes;
        c.routeSequence = (routeSequence == null) ? null : new ArrayList<>(routeSequence);
        c.trafficComplaintWeight = trafficComplaintWeight;
        return c;
    }

    /** 차종 정격용량과 경로 배정용량 중 실제 운행에 적용할 값. */
    public double resolveRouteCapacityKg(double nominalPayloadKg) {
        if (routeAvailableCapacityKg == null) return nominalPayloadKg;
        return Math.min(nominalPayloadKg, routeAvailableCapacityKg);
    }

    /** 기존 적재량을 제외하고 신규 폐기물을 실을 수 있는 용량. */
    public double resolvePickupCapacityKg(double nominalPayloadKg) {
        return Math.max(0.0, resolveRouteCapacityKg(nominalPayloadKg) - initialTruckLoadKg);
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

    /** {@code H:MM} 또는 {@code HH:MM}만 허용한다 — 시 0~23, 분 0~59. */
    private static final java.util.regex.Pattern HHMM =
            java.util.regex.Pattern.compile("\\s*([01]?\\d|2[0-3]):([0-5]\\d)\\s*");

    /**
     * "HH:MM" 문자열 → 자정 기준 분. (ScenarioController.toMinutes()와 중복이던 걸 통합)
     *
     * <p>시와 분을 <b>각각</b> 검사한다. 예전에는 합계만 계산해서 {@code 12:99}가 819분,
     * 즉 13:39로 조용히 바뀌었다 — 총 분이 하루 범위 안이라 이후 범위 검증도 통과하므로,
     * 사용자가 요청한 시각과 다른 시각으로 실험이 돌아가고 아무도 알아채지 못했다.
     */
    public static int hhmmToMinutes(String hhmm) {
        if (hhmm == null) throw new IllegalArgumentException("수거 시각이 비어 있습니다.");
        java.util.regex.Matcher m = HHMM.matcher(hhmm);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "수거 시각은 HH:MM 형식이어야 합니다(시 00~23, 분 00~59). 받은 값: " + hhmm);
        }
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }
}
