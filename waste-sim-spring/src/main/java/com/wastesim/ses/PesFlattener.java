package com.wastesim.ses;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.SimulationConfig;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * 가지치기된 트리(PES)를 계산용 설정({@link SimulationConfig})으로 편다.
 *
 * <p><b>{@code JangnyangScenarioBuilder.toConfig()}(:167 이하)와 결과가 한 필드도
 * 달라선 안 된다.</b> 그 메서드는 지우지 않고 참조 구현으로 남겨 두었고,
 * {@code PesFlattenerDifferentialTest}(Task 10)가 둘을 필드 단위로 대조한다. 여기서
 * 새 규칙을 지어내면 조용히 다른 시뮬레이션이 된다 — 그래서 이 클래스의 각 줄은
 * {@code toConfig()}의 대응하는 줄을 그대로 옮긴 것이지, 새로 설계한 것이 아니다.
 *
 * <p><b>답하지 않은 값은 세터를 부르지 않는다.</b> 부르지 않은 자리가 곧
 * {@code SimulationConfig}의 기본값이고, 그것이 "이 실험에서 정하지 않았다"는
 * 기록이다(toConfig()도 defaultApproval/AppliedDefault 같은 절차 제어와 서버 기본값
 * 계산을 겸하지만, 이 평탄화기는 <b>PES가 담고 있는 답변 값만</b> 옮긴다 — 트래픽
 * 기본 프로파일 채우기나 "이동시간 0이면 15로 올린다" 같은 계산된 기본값은 옮기지
 * 않는다. 자세한 사유는 task-9-report.md 참고).
 */
public final class PesFlattener {

    /**
     * 교통 결합의 지점 id. {@link SesPruner}가 trafficMode=APPLY가 아니면 이 결합을
     * 죽이므로, 살아 있는지 여부가 곧 {@code toConfig()}의
     * {@code traffic = trafficMode != null && !"NONE".equals(trafficMode)}와 같다.
     */
    private static final String TRAFFIC_COUPLING = "coupling:교통 구역.혼잡계수->수거 경로.이동시간";

    private PesFlattener() { }

    public static SimulationConfig flatten(PrunedStructure pes) {
        SimulationConfig c = new SimulationConfig();

        // ── 복제 수 ──────────────────────────────────────────────────────────
        c.setNumBuildings(pes.count("multi:수거지점 집합"));
        c.setResidentsPerBuilding(pes.count("multi:거주민 집합"));
        c.setNumTrucks(pes.count("multi:수거차량 집합"));

        // ── 직접 대응하는 속성 — 키는 answerField다(Ruling 2) ──────────────────
        intOf(pes, "days", c::setDays);
        intOf(pes, "seeds", c::setSeeds);
        dblOf(pes, "wasteMeanKg", c::setWasteMeanKg);
        dblOf(pes, "wasteSigma", c::setWasteSigma);
        dblOf(pes, "leaveSigma", c::setLeaveSigma);
        dblOf(pes, "capacity", c::setCapacity);
        dblOf(pes, "threshold", c::setThreshold);
        dblObjOf(pes, "routeAvailableCapacityKg", c::setRouteAvailableCapacityKg);
        dblOf(pes, "initialTruckLoadKg", c::setInitialTruckLoadKg);
        intOf(pes, "dispatchIntervalMinutes", c::setDispatchIntervalMinutes);
        intOf(pes, "serviceMinutesPerSite", c::setServiceMinutesPerSite);
        intOf(pes, "routeTravelMinutes", c::setRouteTravelMinutes);
        intObjOf(pes, "intraZoneTravelMinutes", c::setIntraZoneTravelMinutes);
        strOf(pes, "zoneAssignmentRule", c::setZoneAssignmentRule);
        strListOf(pes, "routeSequence", c::setRouteSequence);

        // ── 고른 가지 — chosenSpecs는 코드값 그대로 담고 있다(Ruling 3), 다시
        //    번역하지 않고 그대로 세터에 넘긴다 ──────────────────────────────
        chosen(pes, "spec:수거차량:차종 축", c::setTruckType);
        chosen(pes, "spec:수거 경로:이동시간 방식 축", c::setTravelTimeMode);
        chosen(pes, "spec:거주민:배출시각 모델 축", c::setDischargeTimeMode);

        // ── 죽은 결합 — trafficMode 답 자체는 CouplingActivation이라 attributeValues에
        //    남지 않는다(SesPruner). 결합의 생사가 곧 trafficMode==APPLY다 ──────────
        boolean trafficLive = pes.isLive(TRAFFIC_COUPLING);
        c.setTrafficEnabled(trafficLive);
        if (trafficLive) {
            // toConfig()는 교통을 켰는데 프로파일이 없으면 실측 기본 프로파일로
            // 채운다(TrafficDataService.defaultProfileId, AppliedDefault 기록). 이
            // 평탄화기는 PES가 가진 값만 옮기므로 그 기본값 계산은 하지 않는다 —
            // 답한 값이 있으면 그대로 옮기고, 없으면 세터를 부르지 않는다.
            strOf(pes, "trafficProfileId", c::setTrafficProfileId);
        }

        // ── 분기가 필요한 것들 — toConfig()의 규칙을 그대로 옮긴다 ────────────
        applyCollectionTime(pes, c);
        applyCollectionSchedule(pes, c);
        applyOccupationPreset(pes, c);
        applyDischargeWindow(pes, c);

        return c;
    }

    /**
     * 수거 시각은 이제 answerField가 갈라져 있다 — {@code collectionTime}(단일값)과
     * {@code collectionTimes}(목록)는 서로 다른 필드이면서 같은 SES 지점을 가리킨다
     * (PrunedStructure의 Ruling 2). toConfig()도 두 필드를 각각 다른 세터로 보내고,
     * 목록은 <b>2개 이상일 때만</b> 다회 수거로 본다 — 1개짜리 목록은 버린다.
     */
    private static void applyCollectionTime(PrunedStructure pes, SimulationConfig c) {
        intOf(pes, "collectionTime", c::setCollectionTimeMinutes);

        Object rawTimes = pes.value("collectionTimes");
        if (rawTimes instanceof List<?> list && list.size() > 1) {
            List<Integer> minutes = list.stream()
                    .filter(Number.class::isInstance).map(o -> ((Number) o).intValue()).toList();
            if (!minutes.isEmpty()) c.setCollectionTimesMinutes(minutes);
        }
    }

    private static void applyCollectionSchedule(PrunedStructure pes, SimulationConfig c) {
        Object schedule = pes.value("collectionSchedule");
        if (schedule == null) return;
        switch (String.valueOf(schedule)) {
            case "EVERY_DAY" -> c.setCollectionIntervalDays(1);
            case "EVERY_2_DAYS" -> c.setCollectionIntervalDays(2);
            case "EVERY_3_DAYS" -> c.setCollectionIntervalDays(3);
            case "EVERY_7_DAYS" -> c.setCollectionIntervalDays(7);
            // 요일 집합을 쓰는 스케줄은 주기도 함께 1로 둔다 — toConfig()의
            // applyDaysOfWeek(:465)가 그렇게 한다. 검증기(V-D1)가 주기와 요일
            // 집합을 동시 지정으로 보지 않게 하려면 주기를 기본값 1로 맞춰야 한다.
            case "WEEKDAYS_MON_FRI" -> {
                c.setCollectionDaysOfWeek(List.of(0, 1, 2, 3, 4));
                c.setCollectionIntervalDays(1);
            }
            case "MON_WED_FRI" -> {
                c.setCollectionDaysOfWeek(List.of(0, 2, 4));
                c.setCollectionIntervalDays(1);
            }
            case "POHANG_MON_TUE_THU_FRI" -> {
                c.setCollectionDaysOfWeek(List.of(0, 1, 3, 4));
                c.setCollectionIntervalDays(1);
            }
            default -> throw new IllegalStateException("세트에 없는 수거 스케줄 값: " + schedule);
        }
    }

    private static void applyOccupationPreset(PrunedStructure pes, SimulationConfig c) {
        Object preset = pes.value("occupationPreset");
        if (preset == null) return;
        c.setOccupationMix(List.copyOf(ScenarioPreset.fromKey(String.valueOf(preset)).mix));
    }

    private static void applyDischargeWindow(PrunedStructure pes, SimulationConfig c) {
        Object window = pes.value("dischargeWindow");
        if (!(window instanceof List<?> list) || list.size() != 2) return;
        if (list.get(0) instanceof Number start && list.get(1) instanceof Number end) {
            c.setDischargeWindowStartMinutes(start.intValue());
            c.setDischargeWindowEndMinutes(end.intValue());
        }
    }

    private static void chosen(PrunedStructure pes, String pointId, Consumer<String> setter) {
        String picked = pes.chosen(pointId);
        if (picked != null) setter.accept(picked);
    }

    private static void intOf(PrunedStructure pes, String answerField, IntConsumer setter) {
        if (pes.value(answerField) instanceof Number n) setter.accept(n.intValue());
    }

    private static void intObjOf(PrunedStructure pes, String answerField, Consumer<Integer> setter) {
        if (pes.value(answerField) instanceof Number n) setter.accept(n.intValue());
    }

    private static void dblOf(PrunedStructure pes, String answerField, DoubleConsumer setter) {
        if (pes.value(answerField) instanceof Number n) setter.accept(n.doubleValue());
    }

    private static void dblObjOf(PrunedStructure pes, String answerField, Consumer<Double> setter) {
        if (pes.value(answerField) instanceof Number n) setter.accept(n.doubleValue());
    }

    private static void strOf(PrunedStructure pes, String answerField, Consumer<String> setter) {
        Object v = pes.value(answerField);
        if (v != null) setter.accept(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static void strListOf(PrunedStructure pes, String answerField, Consumer<List<String>> setter) {
        Object v = pes.value(answerField);
        if (v instanceof List<?> list && !list.isEmpty()) setter.accept((List<String>) list);
    }
}
