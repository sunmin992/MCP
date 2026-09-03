package com.wastesim.subtask;

import com.wastesim.model.DischargeTimeMode;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.TravelTimeMode;
import com.wastesim.tool.ValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 세트의 <b>확정 기준</b>을 고정한다.
 *
 * <p>v3를 만든 이유가 "묻기만 하고 실행에 닿지 않는 질문을 없애는 것"이라, 이 테스트의
 * 중심은 개수가 아니라 <b>연결</b>이다 — 고정 질문 → 입력 필드 → 검증 규칙 → 실행 설정
 * → 실제 엔진 동작. 질문 하나가 그 사슬 어딘가에서 끊기면 사용자는 답을 주고도 그 값이
 * 반영되지 않은 결과를 받는데, 화면에는 아무 표시도 남지 않는다.
 *
 * <p>그래서 {@link #everyCollectQuestionReachesTheEngineOrIsDeclaredRecordOnly()}가
 * 이 파일의 핵심이다. 질문을 하나 더 넣으면 <b>연결을 증명하거나 기록용이라고 선언하지
 * 않는 한</b> 그 테스트가 깨진다.
 */
class JangnyangSubtaskV3Test {

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final JangnyangSubtaskDefinition def = catalog.byVersion(3);
    private final JangnyangSubtaskValidator validator = new JangnyangSubtaskValidator();
    private final JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
    private final JangnyangScenarioBuilder builder = TestSubtaskFixtures.builder(checker);

    /**
     * 계산에 쓰이지 않는다고 <b>세트가 스스로 밝힌</b> 항목들. 목적 기록과 실행 제어다.
     *
     * <p>이 목록에 이름을 올리는 것이 곧 "이 질문은 엔진에 닿지 않는다"는 선언이다 —
     * 연결을 증명할 수 없는 질문을 조용히 통과시키는 우회로가 아니라, 어디에 쓰이는지를
     * 적어 두는 자리다.
     */
    private static final Set<String> RECORD_OR_CONTROL_ONLY = Set.of(
            "simulationGoal",              // 목적 기록 — 결과를 어떤 질문의 답으로 읽을지
            "defaultApproval",             // 기본값 적용 여부 제어
            "inputAndScenarioConfirmed",   // 미리보기 확인 기록
            "executionApproval");          // 실행·수정·취소 제어

    /** 답변 필드 하나가 실행 설정에 닿는다는 증거. */
    private record Wiring(String field, Object answer, Predicate<SimulationConfig> reaches) {}

    /**
     * 각 질문에 <b>기준 묶음과 다른 값</b>을 넣고, 그 차이가 설정에 나타나는지 본다.
     * 같은 값을 넣으면 연결이 끊겨 있어도 통과하므로 전부 다른 값이다.
     */
    private static List<Wiring> wirings() {
        return List.of(
                new Wiring("scenarioType", "multi-truck", c -> true),   // 아래에서 spec으로 따로 확인
                new Wiring("engine", "python", c -> true),              // 같음
                new Wiring("numBuildings", 7, c -> c.getNumBuildings() == 7),
                new Wiring("residentsPerBuilding", 40, c -> c.getResidentsPerBuilding() == 40),
                new Wiring("occupationPreset", "UNIVERSITY",
                        c -> c.getOccupationMix() != null && c.getOccupationMix().size() == 10),
                new Wiring("days", 45, c -> c.getDays() == 45),
                new Wiring("seeds", 7, c -> c.getSeeds() == 7),
                new Wiring("wasteMeanKg", 1.4, c -> c.getWasteMeanKg() == 1.4),
                new Wiring("wasteSigma", 0.55, c -> c.getWasteSigma() == 0.55),
                new Wiring("leaveSigma", 45.0, c -> c.getLeaveSigma() == 45.0),
                new Wiring("dischargeTimeMode", "POHANG_ACTUAL",
                        c -> c.resolveDischargeTimeMode() == DischargeTimeMode.POHANG_ACTUAL),
                new Wiring("dischargeWindow", "21:00~05:00",
                        c -> c.getDischargeWindowStartMinutes() == 21 * 60
                                && c.getDischargeWindowEndMinutes() == 5 * 60),
                new Wiring("capacity", 55.0, c -> c.getCapacity() == 55.0),
                new Wiring("threshold", 65, c -> Math.abs(c.getThreshold() - 0.65) < 1e-9),
                new Wiring("collectionTime", "07:15", c -> c.getCollectionTimeMinutes() == 7 * 60 + 15),
                new Wiring("collectionTimes", "07:00, 19:00",
                        c -> c.getCollectionTimesMinutes() != null
                                && c.getCollectionTimesMinutes().equals(List.of(420, 1140))),
                new Wiring("collectionSchedule", "EVERY_3_DAYS", c -> c.getCollectionIntervalDays() == 3),
                new Wiring("truckType", "SMALL_1TON", c -> "SMALL_1TON".equals(c.getTruckType())),
                new Wiring("truckCount", 3, c -> c.getNumTrucks() == 3),
                new Wiring("routeAvailableCapacityKg", 900.0,
                        c -> c.getRouteAvailableCapacityKg() != null
                                && c.getRouteAvailableCapacityKg() == 900.0),
                new Wiring("initialTruckLoadKg", 120.0, c -> c.getInitialTruckLoadKg() == 120.0),
                new Wiring("dispatchIntervalMinutes", 25, c -> c.getDispatchIntervalMinutes() == 25),
                new Wiring("trafficMode", "APPLY", SimulationConfig::isTrafficEnabled),
                new Wiring("trafficProfileId", "jangryang-volume-weekday",
                        c -> "jangryang-volume-weekday".equals(c.getTrafficProfileId())),
                new Wiring("travelTimeMode", "ZONE_PROXY_HYBRID",
                        c -> c.resolveTravelTimeMode() == TravelTimeMode.ZONE_PROXY_HYBRID),
                new Wiring("routeTravelMinutes", 17, c -> c.getRouteTravelMinutes() == 17),
                new Wiring("serviceMinutesPerSite", 4, c -> c.getServiceMinutesPerSite() == 4),
                new Wiring("intraZoneTravelMinutes", 2,
                        c -> c.hasIntraZoneTravelMinutes() && c.getIntraZoneTravelMinutes() == 2),
                new Wiring("routeSequence", List.of("Node_C", "Node_A", "Node_B", "Node_D"),
                        c -> List.of("Node_C", "Node_A", "Node_B", "Node_D").equals(c.getRouteSequence())));
    }

    @Test
    @DisplayName("v3의 모든 수집 질문은 실행 설정에 닿거나, 기록·제어용이라고 선언돼 있다")
    void everyCollectQuestionReachesTheEngineOrIsDeclaredRecordOnly() {
        Set<String> covered = new LinkedHashSet<>(RECORD_OR_CONTROL_ONLY);
        for (Wiring w : wirings()) {
            covered.add(w.field());
        }
        List<String> unaccounted = new ArrayList<>();
        for (JangnyangSubtask s : def.collectSubtasks()) {
            if (!covered.contains(s.answerField())) unaccounted.add(s.id() + "/" + s.answerField());
        }
        assertTrue(unaccounted.isEmpty(),
                "실행에 닿는다는 증거도 없고 기록용이라는 선언도 없는 질문이 있다: " + unaccounted
                        + " — 답을 받고도 반영되지 않는 질문이 v3를 만든 이유다");

        // 반대 방향도 본다: 세트에 없는 필드를 배선해 두면 그 배선은 죽은 코드다.
        Set<String> fields = new LinkedHashSet<>();
        for (JangnyangSubtask s : def.ordered()) fields.add(s.answerField());
        for (String field : covered) {
            assertTrue(fields.contains(field), "세트에 없는 필드가 배선 목록에 있다: " + field);
        }
    }

    @Test
    @DisplayName("각 질문의 답이 실제로 실행 설정을 바꾼다 — 기준값과 다른 값을 넣어 확인한다")
    void answersActuallyChangeTheSimulationConfig() {
        SimulationConfig baseline = build(V3Answers.all()).spec().toSimulationConfig();
        for (Wiring w : wirings()) {
            Map<String, Object> answers = withField(w.field(), w.answer());
            // 이동시간 방식·엔진처럼 다른 항목과 조합 조건이 걸린 값은 짝을 맞춰 준다.
            if ("travelTimeMode".equals(w.field())) {
                answers.put(idOf("intraZoneTravelMinutes"), 2);
            }
            if ("trafficMode".equals(w.field())) {
                answers.put(idOf("trafficProfileId"), "jangryang-weekday");
            }
            if ("trafficProfileId".equals(w.field())) {
                // 프로파일은 교통을 켰을 때만 설정에 실린다 — 끈 상태로 고른 프로파일이
                // 설정에 들어가면 "교통 미반영인데 프로파일이 있다"는 모순이 남는다.
                answers.put(idOf("trafficMode"), "APPLY");
            }
            if ("dischargeTimeMode".equals(w.field())) {
                answers.put(idOf("dischargeWindow"), "20:00~06:00");
            }
            if ("engine".equals(w.field()) || "collectionTimes".equals(w.field())) {
                // python은 다회 수거를 지원하지 않으므로 둘을 같은 묶음에 넣지 않는다.
                answers.put(idOf("engine"), "collectionTimes".equals(w.field()) ? "java" : "python");
            }
            if ("routeAvailableCapacityKg".equals(w.field())) {
                answers.put(idOf("engine"), "java");
            }
            JangnyangScenarioBuilder.BuildOutcome out = build(answers);
            assertTrue(out.ok(), () -> w.field() + " 답변으로 조립이 막혔다: " + out.asValidationErrors());
            assertTrue(w.reaches().test(out.spec().toSimulationConfig()),
                    () -> w.field() + "에 답했는데 실행 설정이 기준값과 같다 — 값이 엔진에 닿지 않는다");
        }
        // 기준 묶음 자체는 논문 기준선이어야 한다(위 비교의 대조군).
        assertEquals(4, baseline.getNumBuildings());
        assertEquals(25, baseline.getResidentsPerBuilding());
        assertEquals(8 * 60 + 30, baseline.getCollectionTimeMinutes());
    }

    @Test
    @DisplayName("실행 유형과 엔진은 도구 선택으로 이어진다 — 새 실행 경로를 만들지 않는다")
    void scenarioTypeAndEngineChooseExistingTools() {
        JangnyangScenarioSpec single = build(V3Answers.all()).spec();
        assertEquals("single-run", single.scenarioType());
        assertEquals("run_waste_simulation", single.toolName());
        assertEquals("java-devs", single.engineId());

        JangnyangScenarioSpec python = build(withField("engine", "python")).spec();
        assertEquals("run_waste_simulation_devs", python.toolName());
        assertEquals("python-devs", python.engineId());

        JangnyangScenarioSpec sweep = build(withField("scenarioType", "collection-sweep")).spec();
        assertEquals("run_scenario", sweep.toolName());
    }

    @Test
    @DisplayName("수거 스케줄 한 질문이 주기 또는 요일 집합 <b>하나만</b> 설정한다")
    void scheduleSetsEitherIntervalOrDaysOfWeekNeverBoth() {
        Map<String, List<Integer>> weekdaySets = Map.of(
                "WEEKDAYS_MON_FRI", List.of(0, 1, 2, 3, 4),
                "MON_WED_FRI", List.of(0, 2, 4),
                "POHANG_MON_TUE_THU_FRI", List.of(0, 1, 3, 4));
        Map<String, Integer> intervals = Map.of(
                "EVERY_DAY", 1, "EVERY_2_DAYS", 2, "EVERY_3_DAYS", 3, "EVERY_7_DAYS", 7);

        for (String value : def.byAnswerField("collectionSchedule").allowedRange().valuesOrEmpty()) {
            SimulationConfig cfg = build(withField("collectionSchedule", value))
                    .spec().toSimulationConfig();
            if (weekdaySets.containsKey(value)) {
                assertEquals(weekdaySets.get(value), cfg.getCollectionDaysOfWeek(), value);
                assertEquals(1, cfg.getCollectionIntervalDays(),
                        value + ": 요일 집합과 주기를 함께 지정하면 검증기가 거부한다");
            } else {
                assertEquals(intervals.get(value), cfg.getCollectionIntervalDays(), value);
                assertFalse(cfg.usesDaysOfWeek(), value + ": 주기 방식은 요일 집합을 건드리지 않는다");
            }
        }
    }

    @Test
    @DisplayName("배출 허용 창은 정렬되지 않는다 — 20:00~06:00이 06:00~20:00으로 뒤집히면 정반대 창이다")
    void dischargeWindowKeepsItsDirection() {
        SimulationConfig cfg = build(withField("dischargeTimeMode", "POHANG_ACTUAL",
                "dischargeWindow", "20:00~06:00")).spec().toSimulationConfig();
        assertEquals(20 * 60, cfg.getDischargeWindowStartMinutes());
        assertEquals(6 * 60, cfg.getDischargeWindowEndMinutes());

        // 시작과 종료가 같은 창은 받지 않는다 — 모든 주민이 같은 순간에 버린다.
        JangnyangSubtaskAnswer zeroLength =
                validator.coerce(def.byAnswerField("dischargeWindow"), "20:00~20:00");
        assertFalse(zeroLength.valid());
        // 형식이 아닌 값도 거부한다.
        assertFalse(validator.coerce(def.byAnswerField("dischargeWindow"), "20시부터 새벽까지").valid());
        assertFalse(validator.coerce(def.byAnswerField("dischargeWindow"), "25:00~06:00").valid());
    }

    @Test
    @DisplayName("고른 모델·방식이 요구하는 값을 비워 두면 조립을 막는다 — 기본값으로 채우지 않는다")
    void modeSpecificInputsAreRequiredNotDefaulted() {
        // 포항 규정 모델인데 배출 창이 없다 — 규정값으로 조용히 돌아가면 답하지 않은
        // 조건으로 계산된 결과를 받는다.
        JangnyangScenarioBuilder.BuildOutcome noWindow = build(
                withField("dischargeTimeMode", "POHANG_ACTUAL"));
        assertFalse(noWindow.ok());
        assertTrue(message(noWindow).contains("dischargeWindow")
                        || message(noWindow).contains("배출 허용"),
                () -> "무엇을 더 답해야 하는지 알려야 한다: " + message(noWindow));

        // 구역 근사 방식인데 구역 내 이동시간이 없다 — 비우면 0분으로 계산된다.
        JangnyangScenarioBuilder.BuildOutcome noIntraZone = build(
                withField("travelTimeMode", "ZONE_PROXY_HYBRID"));
        assertFalse(noIntraZone.ok());

        // 상수 방식인데 기본 이동시간이 없다 — 그 값이 유일한 이동시간 출처다.
        JangnyangScenarioBuilder.BuildOutcome noTravel = build(
                withField("routeTravelMinutes", V3Answers.NA));
        assertFalse(noTravel.ok());

        // 짝을 맞춰 주면 통과한다(위 실패가 다른 이유 때문이 아님을 확인).
        assertTrue(build(withField("travelTimeMode", "ZONE_PROXY_HYBRID",
                "intraZoneTravelMinutes", 2)).ok());
    }

    @Test
    @DisplayName("엔진이 지원하지 않는 설정은 실행 전에 막는다 — 무시하거나 다른 엔진으로 바꾸지 않는다")
    void unsupportedEngineSettingsAreBlockedBeforeExecution() {
        // 참조 엔진은 다회 수거를 지원하지 않는다. 예전에는 기준 시각 하나만 보내고
        // 나머지를 조용히 버렸다.
        JangnyangScenarioBuilder.BuildOutcome multiTime = build(
                withField("engine", "python", "collectionTimes", "07:00, 19:00"));
        assertFalse(multiTime.ok());
        assertTrue(message(multiTime).contains("collectionTimes"), message(multiTime));

        // 정책 비교는 run_scenario 한 경로뿐이고 그 경로는 Java 엔진을 쓴다.
        JangnyangScenarioBuilder.BuildOutcome pythonSweep = build(
                withField("engine", "python", "scenarioType", "collection-sweep"));
        assertFalse(pythonSweep.ok());
        assertTrue(message(pythonSweep).contains("Java"), message(pythonSweep));

        // 같은 설정을 Java로 고르면 통과한다 — 막은 것이 엔진 차이임을 확인한다.
        assertTrue(build(withField("engine", "java", "collectionTimes", "07:00, 19:00")).ok());
    }

    @Test
    @DisplayName("기본값은 동의 없이 적용하지 않는다 — 채울 값이 있는데 NONE이면 무엇을 채우려 했는지 알려준다")
    void serverDefaultsNeedConsent() {
        // 교통을 켜고 프로파일을 기본값에 맡기면 서버가 채운다.
        Map<String, Object> needsDefault = withField("trafficMode", "APPLY",
                "trafficProfileId", "jangryang-weekday");
        needsDefault.put(idOf("routeTravelMinutes"), 0);   // 0이면 서버가 15분으로 채운다
        JangnyangScenarioBuilder.BuildOutcome approved = build(needsDefault);
        assertTrue(approved.ok());
        assertFalse(approved.spec().appliedDefaults().isEmpty(), "채운 값이 기록돼야 한다(D-53)");

        Map<String, Object> rejected = new java.util.LinkedHashMap<>(needsDefault);
        rejected.put(idOf("defaultApproval"), "NONE");
        JangnyangScenarioBuilder.BuildOutcome blocked = build(rejected);
        assertFalse(blocked.ok(), "동의하지 않았는데 기본값을 적용하면 안 된다");
        assertTrue(message(blocked).contains("routeTravelMinutes"), message(blocked));
    }

    @Test
    @DisplayName("직업 구성 프리셋의 비율이 배정 목록으로 그대로 반영된다")
    void occupationPresetKeepsItsRatio() {
        SimulationConfig university = build(withField("occupationPreset", "UNIVERSITY"))
                .spec().toSimulationConfig();
        List<String> mix = university.getOccupationMix();
        assertEquals(10, mix.size(), "대학가형은 학생 7 : 생산직 2 : 주부 1 — 목록 길이가 곧 비율이다");
        assertEquals(7, mix.stream().filter("Student"::equals).count());
        assertEquals(2, mix.stream().filter("BlueCollar"::equals).count());
        assertEquals(1, mix.stream().filter("Housewife"::equals).count());

        SimulationConfig balanced = build(V3Answers.all()).spec().toSimulationConfig();
        assertEquals(List.of("BlueCollar", "Student", "Housewife"), balanced.getOccupationMix());
    }

    @Test
    @DisplayName("묻지 않고 계산한 값과 비교 범위를 미리보기에 밝힌다")
    void derivedValuesAndComparisonRangeAreDisclosed() {
        JangnyangScenarioSpec spec = build(V3Answers.all()).spec();
        String preview = spec.previewText();
        assertTrue(preview.contains("총 주민 수 100명"), preview);
        assertTrue(preview.contains("Node_A, Node_B, Node_C, Node_D"), preview);
        assertTrue(preview.contains("계산에 쓰이지 않는 항목"), preview);

        // 비교 실험은 기본 비교 범위로 돌아간다는 사실을 밝힌다 — 사용자가 후보를 준 것으로
        // 오해하면 결과를 자기 조건의 답으로 읽는다.
        String sweep = build(withField("scenarioType", "collection-sweep")).spec().previewText();
        assertTrue(sweep.contains("06:00~18:00"), sweep);
    }

    @Test
    @DisplayName("v2에서 실행에 닿지 않던 질문이 v3에는 없고, v2 세트는 그대로 남아 있다")
    void unwiredV2QuestionsAreGoneButV2SetIsPreserved() {
        // 답만 저장되고 실행에는 반영되지 않던 항목들, 그리고 평균 등으로 형태를 바꿔
        // 넘기던 항목들이다.
        List<String> dropped = List.of(
                "decisionTargets", "targetArea", "collectionNodes", "timeUnit", "calendarMode",
                "totalResidents", "residentsPerBuildingMap", "occupationRatios",
                "wasteTypeRatios", "wasteSeparationMode", "initialLoadMode",
                "weekendHolidayPolicy", "routeSearchMode", "executionType",
                "baselineScenario", "alternativeScenarios", "comparisonValues",
                "primaryMetrics", "optimizationCriteria", "outputFormat",
                "reproducibilityMode", "userProvidedData", "assumptionApproval",
                "inputSummaryConfirmed", "scenarioPreviewConfirmed");
        JangnyangSubtaskDefinition v2 = catalog.byVersion(2);
        for (String field : dropped) {
            assertNull(def.byAnswerField(field), "v3에 남아 있으면 안 되는 질문이다: " + field);
            assertNotNull(v2.byAnswerField(field),
                    "v2는 덮어쓰지 않는다 — 그 세트로 진행된 세션을 되짚을 수 있어야 한다: " + field);
        }

        // 지원하지 않는 선택지도 빼 둔다 — 고르면 다른 것이 실행되던 값들이다.
        assertFalse(def.byAnswerField("trafficMode").allowedRange().valuesOrEmpty().contains("COMPARE"),
                "교통 반영·미반영 비교는 실행 경로가 없다 — 고르면 단일 실행이 돌아간다");
        assertEquals(List.of("java", "python"),
                def.byAnswerField("engine").allowedRange().valuesOrEmpty(),
                "엔진 비교(compare)는 실행 경로가 없다");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    private String idOf(String field) {
        JangnyangSubtask s = def.byAnswerField(field);
        assertNotNull(s, "v3에 없는 답변 필드다: " + field);
        return s.id();
    }

    private Map<String, Object> withField(String field, Object value) {
        return V3Answers.with(idOf(field), value);
    }

    private Map<String, Object> withField(String f1, Object v1, String f2, Object v2) {
        return V3Answers.with(idOf(f1), v1, idOf(f2), v2);
    }

    private JangnyangScenarioBuilder.BuildOutcome build(Map<String, Object> raw) {
        SubtaskValidationResult r = validator.validate(def, raw, Map.of());
        assertTrue(r.valid(), () -> "이 테스트의 입력 자체가 검증에서 걸렸다: " + r.errors());
        return builder.build(def, r.accepted());
    }

    private static String message(JangnyangScenarioBuilder.BuildOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        for (ValidationError e : outcome.asValidationErrors()) {
            sb.append(e.field()).append(": ").append(e.message()).append('\n');
        }
        return sb.toString();
    }
}
