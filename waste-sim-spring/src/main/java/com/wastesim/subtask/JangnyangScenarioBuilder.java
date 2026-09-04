package com.wastesim.subtask;

import com.wastesim.mcp.SimulationModelProvider;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.DischargeTimeMode;
import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.EngineSelectionDetector;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationError;
import com.wastesim.tool.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 검증을 통과한 답변으로 시나리오 명세를 조립한다(FR-131~134, SDD 2.18.7).
 *
 * <pre>
 * JangnyangSubtaskAnswer[]  (검증 통과)
 *         │ JangnyangScenarioBuilder
 *         ▼
 * JangnyangScenarioSpec      ← 시나리오 유형 · 축 인자 · 적용 기본값 · 가정 목록
 *         │ toSimulationConfig()
 *         ▼
 * SimulationConfig           ← 기존 계산용 설정 (변경 없음)
 *         │ SimulationConfigValidator (기존)
 *         ▼
 * SimulationTool.validate → execute
 * </pre>
 *
 * <p><b>두 겹 검증을 합치지 않는다</b>(D-48). 서브태스크 검증은 "사용자가 이 질문에
 * 제대로 답했는가"를 보고, 공통 검증은 "이 설정으로 엔진을 돌릴 수 있는가"를 본다.
 * 전자만 두면 항목별로는 다 맞는데 조합이 모순인 경우(교통 on인데 프로파일 없음 등)를
 * 놓치고, 후자만 두면 오류를 항목별로 되물을 수 없다. 그래서 이 조립기는 답변 검증을
 * 이미 통과한 값을 받고도 <b>다시</b> {@link SimulationConfigValidator}를 거친다.
 */
@Component
public class JangnyangScenarioBuilder {

    private final JangnyangCompletenessChecker checker;
    private final SimulationConfigValidator configValidator;
    private final TrafficDataService trafficData;
    private final SimulationModelRegistry models;

    public JangnyangScenarioBuilder(JangnyangCompletenessChecker checker,
                                    SimulationConfigValidator configValidator,
                                    TrafficDataService trafficData,
                                    SimulationModelRegistry models) {
        this.checker = checker;
        this.configValidator = configValidator;
        this.trafficData = trafficData;
        this.models = models;
    }

    /**
     * 명세를 조립한다.
     *
     * <p>미충족이면 <b>부분 명세를 만들지 않는다</b>(UT-324). 반쯤 채워진 명세를 돌려주면
     * 호출부가 "일단 받았으니 되겠지" 하고 진행할 여지가 생긴다.
     */
    public BuildOutcome build(JangnyangSubtaskDefinition def,
                              Map<String, JangnyangSubtaskAnswer> answers) {
        JangnyangCompletenessChecker.CompletenessVerdict verdict = checker.check(def, answers);
        if (!verdict.sufficient()) {
            return BuildOutcome.incomplete(verdict.missing(), verdict.reasons());
        }

        // 시나리오 유형은 지어내지 않는다 — ST-02가 유효할 때만 여기 도달하므로 값은
        // 허용 목록 안에 있고, 그 값이 곧 유형이다(UT-320). 답변 조합을 보고 "아마
        // multi-truck 같다"고 추정하는 경로를 두지 않는 것이 요점이다.
        String scenarioType = verdict.scenarioType();

        List<AppliedDefault> defaults = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();

        SimulationConfig cfg = toConfig(def, answers, scenarioType, defaults, assumptions);

        // 공통 검증(D-48의 두 번째 겹). 통과하지 못하는 조합은 실행 전에 차단한다.
        ValidationResult vr = configValidator.validate(cfg);
        if (!vr.ready()) {
            return BuildOutcome.invalidConfig(vr.errors());
        }
        for (ValidationError w : vr.warnings()) {
            // 비차단 경고도 가정 목록에 남긴다 — 실행은 되지만 사용자가 알고 눌러야 한다.
            assumptions.add("경고(" + w.field() + "): " + w.message());
        }

        String engineId = engineOf(def, answers, defaults, assumptions);
        String toolName = toolFor(scenarioType, engineId);

        // 고른 엔진이 이 설정을 돌릴 수 있는가(FR-134). 실행 시점에도 어댑터가 같은
        // 판정을 하지만, 그때는 사용자가 답을 다 채우고 실행을 누른 뒤다.
        List<ValidationError> engineErrors = engineSupport(scenarioType, engineId, cfg);
        if (!engineErrors.isEmpty()) {
            return BuildOutcome.invalidConfig(engineErrors);
        }

        // 서버가 채운 값에 동의를 받았는가. 동의 없이 기본값을 적용하지 않는다.
        ValidationError unapproved = unapprovedDefaults(def, answers, defaults);
        if (unapproved != null) {
            return BuildOutcome.invalidConfig(List.of(unapproved));
        }

        disclose(scenarioType, assumptions);

        Map<String, JangnyangScenarioSpec.AnswerRecord> records = new LinkedHashMap<>();
        for (Map.Entry<String, JangnyangSubtaskAnswer> e : answers.entrySet()) {
            JangnyangSubtask st = def.byId(e.getKey());
            if (st == null) continue;
            JangnyangSubtaskAnswer a = e.getValue();
            records.put(e.getKey(), new JangnyangScenarioSpec.AnswerRecord(
                    st.answerField(), a.value(), a.raw(), a.source(), st.answerType()));
        }

        JangnyangScenarioSpec spec = new JangnyangScenarioSpec(
                def.subtaskSetId(), def.version(), def.hash(),
                scenarioType, toolName, engineId,
                records, List.copyOf(defaults), List.copyOf(assumptions), cfg);
        return BuildOutcome.built(spec);
    }

    // ── 명세 → 계산용 설정 ─────────────────────────────────────────────────

    /**
     * 답변을 {@link SimulationConfig}로 옮긴다. <b>계산에 쓰이는 값만</b> 넘어간다 —
     * 원문 답변·정규화 출처·가정 목록은 명세에 남고 설정에는 들어가지 않는다(D-50, UT-326).
     *
     * <p>서브태스크를 ID가 아니라 <b>답변 필드명</b>으로 찾는다. 세트 버전이 오르면 같은
     * 값이 다른 ID에 붙지만(v1의 ST-03과 v2의 ST-020은 둘 다 collectionTime이다) 필드명은
     * 그대로다. ID로 찾으면 버전을 올릴 때마다 이 메서드를 다시 편집해야 한다.
     */
    private SimulationConfig toConfig(JangnyangSubtaskDefinition def,
                                      Map<String, JangnyangSubtaskAnswer> answers,
                                      String scenarioType,
                                      List<AppliedDefault> defaults,
                                      List<String> assumptions) {
        SimulationConfig c = new SimulationConfig();
        Fields f = new Fields(def, answers);

        c.setCollectionTimeMinutes(f.intOr("collectionTime", c.getCollectionTimeMinutes()));
        c.setDays(f.intOr("days", c.getDays()));
        c.setSeeds(f.intOr("seeds", c.getSeeds()));
        c.setNumBuildings(f.intOr("numBuildings", c.getNumBuildings()));
        c.setCapacity(f.dblOr("capacity", c.getCapacity()));
        c.setThreshold(f.dblOr("threshold", c.getThreshold()));
        c.setLeaveSigma(f.dblOr("leaveSigma", c.getLeaveSigma()));
        c.setWasteSigma(f.dblOr("wasteSigma", c.getWasteSigma()));
        c.setWasteMeanKg(f.dblOr("wasteMeanKg", c.getWasteMeanKg()));
        c.setCollectionIntervalDays(f.intOr("collectionIntervalDays", c.getCollectionIntervalDays()));
        c.setTruckType(f.strOr("truckType", c.getTruckType()));
        c.setNumTrucks(f.intOr("truckCount", c.getNumTrucks()));
        c.setDispatchIntervalMinutes(f.intOr("dispatchIntervalMinutes", c.getDispatchIntervalMinutes()));
        c.setInitialTruckLoadKg(f.dblOr("initialTruckLoadKg", c.getInitialTruckLoadKg()));

        Double routeCapacity = f.dbl("routeAvailableCapacityKg");
        if (routeCapacity != null) c.setRouteAvailableCapacityKg(routeCapacity);

        // 건물별 인원이 있으면 평균을 쓴다 — 엔진은 건물당 인원 하나만 받는다. 값이
        // 균등하지 않으면 그 사실을 가정으로 남긴다(조용히 평균 내면 사용자는 자기가 준
        // 분포가 반영된 줄 안다).
        Map<String, Object> perBuilding = f.map("residentsPerBuildingMap");
        if (perBuilding != null && !perBuilding.isEmpty()) {
            int sum = 0;
            for (Object v : perBuilding.values()) if (v instanceof Number n) sum += n.intValue();
            int avg = Math.max(1, Math.round((float) sum / perBuilding.size()));
            c.setResidentsPerBuilding(avg);
            boolean uneven = perBuilding.values().stream()
                    .anyMatch(v -> v instanceof Number n && n.intValue() != avg);
            if (uneven) {
                assumptions.add("건물별 인원이 서로 달라 평균 " + avg
                        + "명으로 계산한다 — 엔진은 건물당 인원을 하나만 받는다.");
            }
        } else {
            c.setResidentsPerBuilding(f.intOr("residentsPerBuilding", c.getResidentsPerBuilding()));
        }

        // 직업 구성 프리셋(v3). 프리셋은 비율을 배정 목록의 <b>반복 횟수</b>로 표현하므로
        // (대학가형 = 학생 7 : 생산직 2 : 주부 1) 비율이 그대로 반영된다 — 아래 v2 경로가
        // 비율 맵을 목록으로 뭉개던 것과 다른 점이 이것이다.
        String preset = f.str("occupationPreset");
        if (preset != null) {
            ScenarioPreset p = ScenarioPreset.fromKey(preset);
            c.setOccupationMix(List.copyOf(p.mix));
            assumptions.add("직업 구성 " + preset + "(" + p.labelKo + ") — "
                    + p.ratioPercent().entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue() + "%")
                        .reduce((a, b) -> a + ", " + b).orElse("") + "로 배정한다.");
        }

        // 직업 구성은 비율로 받지만 엔진은 목록을 받는다 — 비율이 0인 직업은 뺀다.
        Map<String, Object> occ = preset != null ? null : f.map("occupationRatios");
        if (occ != null && !occ.isEmpty()) {
            List<String> mix = new ArrayList<>();
            for (Map.Entry<String, Object> e : occ.entrySet()) {
                if (e.getValue() instanceof Number n && n.doubleValue() > 0) mix.add(e.getKey());
            }
            if (!mix.isEmpty()) {
                c.setOccupationMix(mix);
                assumptions.add("직업 구성비는 목록으로만 반영된다 — 엔진이 비율 가중치를 받지 않는다: "
                        + String.join(", ", mix));
            }
        } else {
            List<String> mix = f.list("occupationMix");
            if (mix != null && !mix.isEmpty()) c.setOccupationMix(mix);
        }

        // 하루 여러 번 수거.
        List<?> times = f.rawList("collectionTimes");
        if (times != null && times.size() > 1) {
            List<Integer> minutes = new ArrayList<>();
            for (Object o : times) if (o instanceof Number n) minutes.add(n.intValue());
            if (!minutes.isEmpty()) c.setCollectionTimesMinutes(minutes);
        }

        // 수거 스케줄(v3) — 주기와 요일 집합을 한 질문으로 받되 <b>둘 중 하나만</b>
        // 설정한다. 함께 지정하면 교집합이 비어 한 번도 수거하지 않는 설정이 만들어질 수
        // 있고, 검증기(V-D1)가 그 조합을 거부한다.
        String schedule = f.str("collectionSchedule");
        if (schedule != null) {
            switch (schedule) {
                case "EVERY_DAY" -> c.setCollectionIntervalDays(1);
                case "EVERY_2_DAYS" -> c.setCollectionIntervalDays(2);
                case "EVERY_3_DAYS" -> c.setCollectionIntervalDays(3);
                case "EVERY_7_DAYS" -> c.setCollectionIntervalDays(7);
                case "WEEKDAYS_MON_FRI" -> applyDaysOfWeek(c, List.of(0, 1, 2, 3, 4), "월~금", assumptions);
                case "MON_WED_FRI" -> applyDaysOfWeek(c, List.of(0, 2, 4), "월·수·금", assumptions);
                case "POHANG_MON_TUE_THU_FRI" ->
                        applyDaysOfWeek(c, List.of(0, 1, 3, 4), "월·화·목·금(포항시 북구 실제 수거요일)", assumptions);
                default -> throw new IllegalStateException("세트에 없는 수거 스케줄 값: " + schedule);
            }
        }

        // 배출 시각 모델(v3).
        String dischargeMode = f.str("dischargeTimeMode");
        if (dischargeMode != null) {
            c.setDischargeTimeMode(dischargeMode);
            if (DischargeTimeMode.POHANG_ACTUAL.name().equals(dischargeMode)) {
                assumptions.add("배출 허용 창 안의 분포는 균등이다 — 공식 데이터가 주는 것은 창뿐이고, "
                        + "그 안에서 언제 버리는지는 어디에도 없다. 이 모델에서는 직업 구성이 배출 시각에 "
                        + "영향을 주지 않는다.");
            }
        }
        List<?> window = f.rawList("dischargeWindow");
        if (window != null && window.size() == 2
                && window.get(0) instanceof Number start && window.get(1) instanceof Number end) {
            c.setDischargeWindowStartMinutes(start.intValue());
            c.setDischargeWindowEndMinutes(end.intValue());
        }

        // 이동시간 계산 방식(v3)과 그 방식이 쓰는 값들.
        String travelMode = f.str("travelTimeMode");
        if (travelMode != null) {
            c.setTravelTimeMode(travelMode);
            // 그 방식이 쓰지 않는 값을 답했으면 그렇다고 적는다 — 값이 결과에 반영된 줄
            // 알고 조건을 바꿔 가며 실험하면 아무 변화가 없는 이유를 알 수 없다.
            if (!"LEGACY_CONSTANT".equals(travelMode) && f.intVal("routeTravelMinutes") != null) {
                assumptions.add("이동시간 방식이 " + travelMode
                        + "이므로 기본 이동시간(routeTravelMinutes)은 계산에 쓰이지 않는다.");
            }
            if (!"ZONE_PROXY_HYBRID".equals(travelMode) && f.intVal("intraZoneTravelMinutes") != null) {
                assumptions.add("이동시간 방식이 " + travelMode
                        + "이므로 구역 내 이동시간(intraZoneTravelMinutes)은 계산에 쓰이지 않는다.");
            }
        }
        Integer serviceMinutes = f.intVal("serviceMinutesPerSite");
        if (serviceMinutes != null) c.setServiceMinutesPerSite(serviceMinutes);
        Integer intraZone = f.intVal("intraZoneTravelMinutes");
        if (intraZone != null) c.setIntraZoneTravelMinutes(intraZone);
        // 건물의 교통 구역 배정 가정(v4). "해당없음"이면 Fields.value()가 null로 걸러
        // 주므로 여기서는 세팅하지 않는다 — 세팅하지 않은 값이 곧 "가정 없음"이다.
        String zoneRule = f.str("zoneAssignmentRule");
        if (zoneRule != null) c.setZoneAssignmentRule(zoneRule);

        // 교통.
        String trafficMode = f.str("trafficMode");
        boolean traffic = trafficMode != null && !"NONE".equals(trafficMode);
        c.setTrafficEnabled(traffic);
        if (traffic) {
            String profile = f.str("trafficProfileId");
            if (profile == null || JangnyangSubtaskValidator.NOT_APPLICABLE.equals(profile)
                    || "default".equals(profile)) {
                profile = trafficData.defaultProfileId();
                if (profile != null) {
                    defaults.add(new AppliedDefault("trafficProfileId", profile,
                            "교통 레이어를 켰는데 프로파일을 고르지 않았다 — 실측 기본 프로파일을 적용"));
                }
                // 기본 프로파일조차 없으면 채운 것이 없으므로 기록하지 않는다. null인 채로
                // 넘기면 공통 검증이 "교통 레이어를 사용하려면 trafficProfileId가 필요합니다"로
                // 막는다 — "적용했다"고 적어 두고 값이 비어 있는 것보다 낫다.
            }
            c.setTrafficProfileId(profile);
        }

        int travel = f.intOr("routeTravelMinutes", c.getRouteTravelMinutes());
        c.setRouteTravelMinutes(travel);
        if (traffic && c.getRouteTravelMinutes() <= 0) {
            // 이동시간이 0이면 혼잡 가중치가 걸릴 자리가 없어 교통을 켠 효과가 결과에
            // 전혀 나타나지 않는다.
            c.setRouteTravelMinutes(15);
            defaults.add(new AppliedDefault("routeTravelMinutes", 15,
                    "이동시간이 0이면 혼잡 가중치가 결과에 반영될 물리적 여지가 없다"));
        }

        List<String> route = f.list("routeSequence");
        if (route != null && !route.isEmpty()) c.setRouteSequence(route);

        // 주말 수거 정책.
        String weekend = f.str("weekendHolidayPolicy");
        if ("SKIP_WEEKEND".equals(weekend) || "SKIP_BOTH".equals(weekend)) {
            c.setSkipWeekends(true);
        }

        // 분리배출.
        String separation = f.str("wasteSeparationMode");
        if ("APPLY".equals(separation) || "COMPARE".equals(separation)) {
            c.setWasteTypes(com.wastesim.model.WasteType.defaultSeparated());
            assumptions.add("분리배출은 기본 3종(일반·음식물·재활용) 구성으로 반영한다.");
        }

        // 묻지 않은 값이 기본값으로 들어간 것도 숨기지 않는다(D-53).
        recordUnaskedDefault(def, answers, defaults, "leaveSigma", c.getLeaveSigma(),
                "외출 시각 분산을 묻지 않았다 — 논문 기준값");
        recordUnaskedDefault(def, answers, defaults, "wasteSigma", c.getWasteSigma(),
                "일일 배출량 변동을 묻지 않았다 — 논문 기준값");
        recordUnaskedDefault(def, answers, defaults, "wasteMeanKg", c.getWasteMeanKg(),
                "1인 1일 평균 배출량을 묻지 않았다 — 논문 가정치");

        // 묻지 않고 <b>계산해서</b> 보여주는 값들(v3). 질문을 줄인 자리이므로, 무엇이 어떻게
        // 계산됐는지 미리보기에 남긴다 — 사용자가 답하지 않은 값이 결과에 들어가 있는데
        // 근거가 없으면 "어디서 나온 숫자냐"에 답할 수 없다.
        if (def.byAnswerField("residentsPerBuilding") != null
                && def.byAnswerField("totalResidents") == null) {
            assumptions.add("총 주민 수 " + (c.getNumBuildings() * c.getResidentsPerBuilding())
                    + "명 = 건물 " + c.getNumBuildings() + "동 × 건물당 " + c.getResidentsPerBuilding() + "명.");
        }
        if (def.byAnswerField("collectionNodes") == null) {
            assumptions.add("수거 지점 " + c.getNumBuildings() + "곳을 건물 수에 맞춰 자동 생성한다: "
                    + String.join(", ", autoNodeIds(c.getNumBuildings())) + ".");
        }
        if (c.getRouteSequence() == null || c.getRouteSequence().isEmpty()) {
            assumptions.add("방문 순서를 지정하지 않아 자동 생성 순서대로 돈다.");
        }

        // 계산에 쓰이지 않는 질문을 그렇다고 밝힌다 — 답을 받았다는 사실만으로 결과에
        // 반영됐다고 읽히면, 목적 문장이 실험 조건이었다고 오해된다.
        List<String> recordOnly = new ArrayList<>();
        for (String field : List.of("simulationGoal", "defaultApproval",
                "inputAndScenarioConfirmed", "executionApproval")) {
            if (def.byAnswerField(field) != null) recordOnly.add(field);
        }
        if (!recordOnly.isEmpty()) {
            assumptions.add("계산에 쓰이지 않는 항목(기록·제어용): " + String.join(", ", recordOnly) + ".");
        }

        // "해당 없음"으로 넘어간 항목은 가정으로 남긴다 — 사용자가 답하지 않기로 한
        // 것도 실험의 조건이다.
        for (JangnyangSubtask st : def.collectSubtasks()) {
            JangnyangSubtaskAnswer a = answers.get(st.id());
            if (a != null && a.valid()
                    && JangnyangSubtaskValidator.NOT_APPLICABLE.equals(a.value())) {
                assumptions.add(st.answerField() + ": 해당 없음으로 두어 기본 동작을 따른다.");
            }
        }

        return c;
    }

    /**
     * 답변 필드명으로 값을 꺼내는 얇은 조회기.
     *
     * <p>"해당 없음"은 값이 아니라 <b>답하지 않기로 한 표시</b>이므로 여기서 걸러 낸다 —
     * 그래야 호출부가 기본값을 그대로 쓴다.
     */
    private record Fields(JangnyangSubtaskDefinition def, Map<String, JangnyangSubtaskAnswer> answers) {

        private Object value(String field) {
            JangnyangSubtask st = def.byAnswerField(field);
            if (st == null) return null;
            JangnyangSubtaskAnswer a = answers.get(st.id());
            if (a == null || !a.valid()) return null;
            Object v = a.value();
            return JangnyangSubtaskValidator.NOT_APPLICABLE.equals(v) ? null : v;
        }

        Integer intVal(String field) {
            return value(field) instanceof Number n ? n.intValue() : null;
        }

        int intOr(String field, int def) {
            Integer v = intVal(field);
            return v == null ? def : v;
        }

        Double dbl(String field) {
            return value(field) instanceof Number n ? n.doubleValue() : null;
        }

        double dblOr(String field, double def) {
            Double v = dbl(field);
            return v == null ? def : v;
        }

        String str(String field) {
            Object v = value(field);
            return v == null ? null : String.valueOf(v);
        }

        String strOr(String field, String def) {
            String v = str(field);
            return v == null ? def : v;
        }

        List<?> rawList(String field) {
            return value(field) instanceof List<?> l ? l : null;
        }

        @SuppressWarnings("unchecked")
        List<String> list(String field) {
            List<?> l = rawList(field);
            return l == null ? null : (List<String>) l;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map(String field) {
            return value(field) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
    }

    /** 요일 집합을 쓰는 스케줄. 주기는 기본값 1로 두어야 검증기(V-D1)가 동시 지정으로 보지 않는다. */
    private static void applyDaysOfWeek(SimulationConfig c, List<Integer> days,
                                        String label, List<String> assumptions) {
        c.setCollectionDaysOfWeek(days);
        c.setCollectionIntervalDays(1);
        assumptions.add("수거 요일을 " + label + "로 둔다 — 주기(N일마다)는 함께 적용하지 않는다.");
    }

    /** 건물 수에 맞춰 자동 생성하는 수거 지점 ID — 엔진의 {@code Node_A~Node_Z} 체계와 같다. */
    static List<String> autoNodeIds(int numBuildings) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.max(0, Math.min(26, numBuildings)); i++) {
            ids.add("Node_" + (char) ('A' + i));
        }
        return ids;
    }

    /**
     * 고른 엔진이 이 시나리오와 설정을 실제로 돌릴 수 있는가.
     *
     * <p>두 가지를 막는다. 첫째 <b>시나리오 비교에 참조 엔진을 고른 경우</b> — 비교 실험은
     * {@code run_scenario} 한 경로로만 돌고 그 경로는 Java 엔진을 쓴다. 막지 않으면 사용자는
     * python을 골랐는데 Java 결과를 받고, 어디에도 그 사실이 남지 않는다. 둘째 <b>어댑터가
     * 지원하지 않는 필드</b> — 판정은 어댑터 자신에게 묻는다({@code unsupported}), 여기서
     * 목록을 다시 적으면 어댑터가 늘 때 이 자리가 낡는다.
     */
    private List<ValidationError> engineSupport(String scenarioType, String engineId,
                                                SimulationConfig cfg) {
        List<ValidationError> errors = new ArrayList<>();
        boolean singleRun = "single-run".equals(scenarioType);
        if (!singleRun && EngineSelectionDetector.PYTHON_MODEL_ID.equals(engineId)) {
            errors.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "engine",
                    "정책 비교 실험(" + scenarioType + ")은 Java 엔진으로만 돌릴 수 있습니다. "
                            + "python을 고른 채 실행하면 비교는 Java로 계산되고 엔진 선택이 결과에 "
                            + "반영되지 않습니다. engine을 java로 바꾸거나 single-run으로 실행하세요."));
        }
        SimulationModelProvider provider = models == null ? null : models.byId(engineId);
        if (provider != null) {
            List<String> unsupported = provider.unsupported(cfg);
            if (!unsupported.isEmpty()) {
                errors.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "engine",
                        "엔진 " + engineId + "이(가) 지원하지 않는 설정입니다: "
                                + String.join(", ", unsupported)
                                + ". 값을 무시하거나 다른 엔진으로 바꾸지 않으므로, 해당 항목을 "
                                + "되돌리거나 엔진을 바꿔 주세요."));
            }
        }
        return errors;
    }

    /**
     * 서버가 채운 값에 동의를 받았는가(v3의 {@code defaultApproval}).
     *
     * <p>기본값을 동의 없이 적용하지 않는다는 규약이라, 채울 값이 있는데 사용자가 NONE으로
     * 답했으면 실행하지 않고 <b>무엇을 채우려 했는지</b> 알려준다. 세트에 이 질문이 없는
     * 버전(v2)에서는 판정하지 않는다.
     */
    private static ValidationError unapprovedDefaults(JangnyangSubtaskDefinition def,
                                                      Map<String, JangnyangSubtaskAnswer> answers,
                                                      List<AppliedDefault> defaults) {
        if (defaults.isEmpty()) return null;
        JangnyangSubtask st = def.byAnswerField("defaultApproval");
        if (st == null) return null;
        JangnyangSubtaskAnswer a = answers.get(st.id());
        if (a == null || !a.valid() || !"NONE".equals(String.valueOf(a.value()))) return null;
        List<String> fields = defaults.stream().map(AppliedDefault::field).distinct().toList();
        return new ValidationError(ErrorCode.MISSING_FIELD, "defaultApproval",
                "기본값 적용에 동의하지 않았는데 서버가 채워야 하는 값이 있습니다: "
                        + String.join(", ", fields)
                        + ". 해당 항목에 값을 직접 답하거나 defaultApproval을 ALL로 바꿔 주세요.");
    }

    /**
     * 비교 실험의 <b>기본 비교 범위</b>를 미리보기에 밝힌다.
     *
     * <p>축 값을 사용자에게 받지 않는 이유는 그 값이 실행 경로에 전달되지 않기 때문이다 —
     * 받아 두면 사용자는 자기가 준 후보로 비교됐다고 읽는다. 대신 <b>실제로 쓰이는 범위</b>를
     * 적어 둔다.
     */
    private static void disclose(String scenarioType, List<String> assumptions) {
        if (scenarioType == null || "single-run".equals(scenarioType)) return;
        if ("collection-sweep".equals(scenarioType)) {
            assumptions.add("수거 시각 비교는 서버에 정의된 후보로 실행된다 — 06:00~18:00을 60분 간격으로 "
                    + "훑는다(13개 시각). 임의의 후보 시각을 받지 않는다.");
            return;
        }
        assumptions.add("비교 축 값을 받지 않으므로 " + scenarioType
                + " 시나리오의 기본 비교 범위로 실행된다.");
    }

    /** 세트에 그 질문이 아예 없어서 채운 값만 기록한다. */
    private static void recordUnaskedDefault(JangnyangSubtaskDefinition def,
                                             Map<String, JangnyangSubtaskAnswer> answers,
                                             List<AppliedDefault> defaults,
                                             String field, Object value, String reason) {
        JangnyangSubtask st = def.byAnswerField(field);
        if (st != null && answers.containsKey(st.id())) return;
        defaults.add(new AppliedDefault(field, value, reason));
    }

    // ── 도구·엔진 선택(FR-134) ─────────────────────────────────────────────

    /** 엔진은 서버가 정한다. 사용자가 골랐으면 그 값, 아니면 기본 Java 엔진 + 가정 기록. */
    private static String engineOf(JangnyangSubtaskDefinition def,
                                   Map<String, JangnyangSubtaskAnswer> answers,
                                   List<AppliedDefault> defaults, List<String> assumptions) {
        String engine = new Fields(def, answers).str("engine");
        if ("python".equals(engine)) return EngineSelectionDetector.PYTHON_MODEL_ID;
        if (engine == null) {
            defaults.add(new AppliedDefault("engine", "java",
                    "엔진을 고르지 않았다 — 기존 채팅 경로와 같은 기본값(Java 재구현 엔진)"));
            assumptions.add("엔진을 지정하지 않아 Java 재구현 엔진으로 실행한다(Python 참조 엔진은 같은 조건에서 훨씬 느리다).");
        }
        return SimulationModelRegistry.DEFAULT_MODEL_ID;
    }

    /**
     * 실행 도구는 기존 것을 그대로 고른다 — 새 실행 경로를 만들지 않는다(FR-134).
     * 단일 실행은 엔진에 따라 모델 어댑터가 갈리고, 나머지 11종은 {@code run_scenario}다.
     */
    private static String toolFor(String scenarioType, String engineId) {
        if ("single-run".equals(scenarioType)) {
            return EngineSelectionDetector.PYTHON_MODEL_ID.equals(engineId)
                    ? "run_waste_simulation_devs" : "run_waste_simulation";
        }
        return "run_scenario";
    }

    // ── 값 꺼내기는 Fields 레코드가 담당한다 ──────────────────────────────

    /**
     * 조립 결과. 성공이면 명세가, 실패면 <b>왜 아직 못 만드는지</b>가 담긴다.
     *
     * <p>{@code missing}과 {@code errors}를 나눠 두는 이유: 전자는 "더 물어야 한다"이고
     * 후자는 "이 조합으로는 못 돈다"다. 호출부의 대응이 다르다 — 전자는 재질문, 후자는
     * 앞 답변으로 되돌아가기다.
     */
    public record BuildOutcome(JangnyangScenarioSpec spec,
                               List<String> missing,
                               List<SubtaskError> retryPrompts,
                               List<ValidationError> configErrors) {

        public static BuildOutcome built(JangnyangScenarioSpec spec) {
            return new BuildOutcome(spec, List.of(), List.of(), List.of());
        }

        public static BuildOutcome incomplete(List<String> missing, List<SubtaskError> reasons) {
            return new BuildOutcome(null, List.copyOf(missing), List.copyOf(reasons), List.of());
        }

        public static BuildOutcome invalidConfig(List<ValidationError> errors) {
            return new BuildOutcome(null, List.of(), List.of(), List.copyOf(errors));
        }

        public boolean ok() { return spec != null; }

        /** 실패 사유를 구조화 오류로 평탄화 — MCP 응답이 그대로 쓴다. */
        public List<ValidationError> asValidationErrors() {
            List<ValidationError> out = new ArrayList<>(configErrors);
            for (SubtaskError e : retryPrompts) {
                out.add(new ValidationError(e.code() == null ? ErrorCode.MISSING_FIELD : e.code(),
                        e.subtaskId(), e.reason() + " / " + e.retryQuestion()));
            }
            return out;
        }
    }
}
