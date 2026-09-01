package com.wastesim.subtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-320~327 — <b>시나리오 조립</b>(TDD 3.17.4).
 *
 * <p>조립기가 지켜야 할 것은 셋이다 — 유형을 지어내지 않고, 채운 값을 숨기지 않으며,
 * 수집 산물을 계산 계층으로 흘려보내지 않는다.
 */
class JangnyangScenarioBuilderTest {

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final JangnyangSubtaskDefinition def = catalog.latest();
    private final JangnyangSubtaskValidator validator = new JangnyangSubtaskValidator();
    private final JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
    private final JangnyangScenarioBuilder builder = TestSubtaskFixtures.builder(checker);

    private Map<String, JangnyangSubtaskAnswer> accept(Map<String, Object> raw) {
        SubtaskValidationResult r = validator.validate(def, raw, Map.of());
        assertTrue(r.valid(), () -> "이 테스트의 입력 자체가 검증에서 걸렸다: " + r.errors());
        return r.accepted();
    }

    private static Map<String, Object> baseAnswers() {
        return new LinkedHashMap<>(V2Answers.all());
    }

    @Test
    @DisplayName("UT-320 답변이 정확히 한 시나리오 유형으로 매핑되고, 애매하면 지어내지 않는다")
    void scenarioTypeMapsExactlyOnce() {
        var built = builder.build(def, accept(baseAnswers()));
        assertTrue(built.ok());
        assertEquals("single-run", built.spec().scenarioType());

        Map<String, Object> multi = baseAnswers();
        multi.put("ST-035", "multi-truck");
        multi.put("ST-025", 3);
        multi.put("ST-028", 45);
        var multiBuilt = builder.build(def, accept(multi));
        assertTrue(multiBuilt.ok());
        assertEquals("multi-truck", multiBuilt.spec().scenarioType());

        // 유형을 안 정한 채로는 조립하지 않는다 — 답변 조합을 보고 "아마 이것일 것"이라고
        // 추정하는 경로가 없다는 확인이다.
        Map<String, Object> noType = baseAnswers();
        noType.remove("ST-035");
        var ambiguous = builder.build(def, accept(noType));
        assertFalse(ambiguous.ok());
        assertTrue(ambiguous.missing().contains("ST-035"));
        assertFalse(ambiguous.retryPrompts().isEmpty(), "되물을 문장이 함께 와야 한다");
    }

    @Test
    @DisplayName("UT-321 서버가 채운 값은 appliedDefaults에 근거와 함께 기록된다")
    void appliedDefaultsAreRecorded() {
        // v2는 값을 거의 다 묻는다. 그래서 서버가 채우는 자리는 사용자가 "기본값 사용"으로
        // 넘긴 항목뿐이다 — 엔진을 고르지 않으면 Java 재구현 엔진으로 채우고 그 사실을 남긴다.
        //
        // 교통 프로파일은 여기 해당하지 않는다. 교통을 켰는데 프로파일이 없으면 충분성
        // 판정이 먼저 막기 때문이다(ST-030의 검증 규칙) — 조용히 채우는 대신 되묻는다.
        var built = builder.build(def, accept(V2Answers.with("ST-042", "기본값 사용")));
        assertTrue(built.ok(), () -> "조립이 거부됐다: " + built.configErrors());

        List<AppliedDefault> defaults = built.spec().appliedDefaults();
        assertTrue(defaults.stream().anyMatch(d -> "engine".equals(d.field())),
                "엔진을 고르지 않았으면 채운 사실이 남아야 한다");
        assertEquals("java-devs", built.spec().engineId());
        // 근거가 "기본값"이라는 동어반복이 아니어야 한다.
        for (AppliedDefault d : defaults) {
            assertFalse(d.reason().isBlank());
            assertNotEquals("기본값", d.reason());
        }

        // 교통을 켜고 프로파일을 비우면 채우는 게 아니라 되묻는다.
        Map<String, Object> noProfile = V2Answers.with("ST-029", "APPLY");
        noProfile.put("ST-030", "해당 없음");
        var blocked = builder.build(def, accept(noProfile));
        assertFalse(blocked.ok(), "교통 프로파일이 없으면 조용히 채우지 않고 되묻는다");
        assertTrue(blocked.missing().contains("ST-030"));

    }

    @Test
    @DisplayName("UT-322 변환 결과가 기존 SimulationConfigValidator를 통과하고, 못 하는 조합은 차단된다")
    void conversionPassesSharedValidator() {
        var built = builder.build(def, accept(baseAnswers()));
        assertTrue(built.ok());

        TrafficDataService traffic = new TrafficDataService();
        SimulationConfig cfg = built.spec().toSimulationConfig();
        assertTrue(new SimulationConfigValidator(traffic).validate(cfg).ready(),
                "조립 결과는 공통 검증을 통과해야 한다");
        assertEquals(510, cfg.getCollectionTimeMinutes());
        assertEquals(30, cfg.getDays());
        assertEquals(4, cfg.getNumBuildings());

        // 교통을 켜면 프로파일이 반드시 붙어야 한다 — 붙지 않으면 교통 레이어가 조용히
        // 무력화된다. 서브태스크로 묻지 않는 값이므로 서버가 채우고 그 사실을 남긴다.
        Map<String, Object> withTraffic = baseAnswers();
        withTraffic.put("ST-029", "APPLY");
        withTraffic.put("ST-030", "jangryang-weekday");
        withTraffic.put("ST-024", "SMALL_1TON");
        var trafficBuilt = builder.build(def, accept(withTraffic));
        assertTrue(trafficBuilt.ok(), () -> "조립이 거부됐다: " + trafficBuilt.configErrors());
        assertEquals("jangryang-weekday",
                trafficBuilt.spec().toSimulationConfig().getTrafficProfileId());
        assertTrue(trafficBuilt.spec().appliedDefaults().stream()
                .noneMatch(d -> "trafficProfileId".equals(d.field())),
                "사용자가 고른 값은 서버가 채운 값이 아니다");

        // 두 겹 검증 중 <b>두 번째 겹</b>이 실제로 동작하는지 — 서브태스크 규칙은 항목별로만
        // 보므로 "차종은 유효하고 접근성도 각각 유효한데 둘을 함께 놓으면 불가능"한 조합은
        // 여기서만 잡힌다(D-48). 5톤 차량은 골목 진입이 안 된다.
        //
        // 골목 정보는 이제 수거 지점에 있고 운영 데이터에는 골목이 하나도 없으므로(실제 네
        // 지점이 전부 간선에 접한다), 이 조합을 만들려면 가상 지점 집합을 물려야 한다.
        JangnyangScenarioBuilder alleyAware = new JangnyangScenarioBuilder(
                checker,
                new SimulationConfigValidator(traffic, com.wastesim.site.TestSites.withAlleys()),
                traffic);
        Map<String, Object> infeasible = baseAnswers();
        infeasible.put("ST-029", "APPLY");
        infeasible.put("ST-030", "jangryang-weekday");
        infeasible.put("ST-024", "LARGE_5TON");
        var blocked = alleyAware.build(def, accept(infeasible));
        assertFalse(blocked.ok(), "항목별로 다 맞아도 조합이 불가능하면 실행 전에 막아야 한다");
        assertNull(blocked.spec());
        assertTrue(blocked.configErrors().stream().anyMatch(e -> "truckType".equals(e.field())));
    }

    @Test
    @DisplayName("UT-323 assumptions가 미리보기와 최종 결과에 함께 실린다")
    void assumptionsRideAlongWithPreview() {
        var built = builder.build(def, accept(baseAnswers()));
        JangnyangScenarioSpec spec = built.spec();
        assertFalse(spec.assumptions().isEmpty());

        String preview = spec.previewText();
        for (String a : spec.assumptions()) {
            assertTrue(preview.contains(a), "가정이 미리보기에 없다: " + a);
        }
        for (AppliedDefault d : spec.appliedDefaults()) {
            assertTrue(preview.contains(d.field()), "채운 값이 미리보기에 없다: " + d.field());
        }
        // 구조화 미리보기에도 같은 것이 실려야 한다 — 프런트엔드가 문구를 파싱하지
        // 않도록(SDD 2.18.10).
        Map<String, Object> map = spec.toPreviewMap();
        assertEquals(spec.assumptions(), map.get("assumptions"));
        assertNotNull(map.get("appliedDefaults"));
    }

    @Test
    @DisplayName("UT-324 필수 항목이 빈 상태의 조립 요청은 부분 명세를 만들지 않고 거부한다")
    void incompleteBuildIsRejectedWithoutPartialSpec() {
        Map<String, Object> partial = baseAnswers();
        partial.remove("ST-020");
        partial.remove("ST-018");

        var outcome = builder.build(def, accept(partial));
        assertFalse(outcome.ok());
        assertNull(outcome.spec(), "반쯤 채워진 명세를 돌려주면 호출부가 진행해 버린다");
        assertTrue(outcome.missing().containsAll(List.of("ST-020", "ST-018")));
        // 되물을 문장이 항목마다 함께 와야 한다.
        assertEquals(outcome.missing().size(), outcome.retryPrompts().size());
        for (SubtaskError e : outcome.retryPrompts()) {
            assertEquals(def.byId(e.subtaskId()).retryQuestion(), e.retryQuestion());
        }
    }

    @Test
    @DisplayName("UT-325 조합 조건 — 항목별로는 유효한데 함께 놓으면 성립하지 않는 것들")
    void combinationConditionsAreChecked() {
        // v2는 시나리오와 무관하게 전부 묻는다. 그래서 "이 시나리오에는 이 질문을 안 묻는다"는
        // 판정이 사라지고, 대신 <b>모아 놓아야 드러나는</b> 모순만 여기서 잡는다.

        // 건물별 인원의 합이 총 거주민 수와 다르다.
        var sumMismatch = builder.build(def, accept(
                V2Answers.with("ST-010", "Node_A=10, Node_B=10, Node_C=10, Node_D=10")));
        assertFalse(sumMismatch.ok(), "합이 40인데 총원이 100이면 어느 쪽이 맞는지 서버가 정하지 않는다");
        assertTrue(sumMismatch.missing().contains("ST-010"));

        // 수거 지점 수가 건물 수와 다르다.
        var nodeMismatch = builder.build(def, accept(
                V2Answers.with("ST-005", List.of("Node_A", "Node_B"))));
        assertFalse(nodeMismatch.ok());

        // monthly-waste인데 시드가 하나면 잡음 위에 순위를 세우게 된다(D-40).
        Map<String, Object> oneSeed = V2Answers.with("ST-035", "monthly-waste");
        oneSeed.put("ST-043", 1);
        var seedShort = builder.build(def, accept(oneSeed));
        assertFalse(seedShort.ok());
        assertTrue(seedShort.missing().contains("ST-043"));

        // 대조군 — single-run은 시드 1로도 성립한다(이 제약은 monthly-waste만의 것이다).
        assertTrue(builder.build(def, accept(V2Answers.with("ST-043", 1))).ok());

        // 모델 가정을 확인하지 않으면 실행하지 않는다.
        assertFalse(builder.build(def, accept(
                V2Answers.with("ST-047", "NEEDS_CHANGE"))).ok());
    }

    @Test
    @DisplayName("미리보기의 시각은 계산용 분이 아니라 HH:MM으로 보인다(조건 확인이 목적인 화면이므로)")
    void previewRendersTimesAsClockValues() {
        var spec = builder.build(def, accept(baseAnswers())).spec();

        // 계산에 쓰이는 값은 분 그대로여야 한다 — 표시 때문에 값을 바꾸면 엔진이
        // 다시 파싱해야 한다.
        assertEquals(510, spec.answers().get("ST-020").value());
        assertEquals(510, spec.toSimulationConfig().getCollectionTimeMinutes());

        // 사람이 보는 자리에서는 08:30이어야 한다.
        assertEquals("08:30", spec.answers().get("ST-020").display());
        assertTrue(spec.previewText().contains("collectionTime: 08:30"));
        assertFalse(spec.previewText().contains("collectionTime: 510"));

        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) spec.toPreviewMap().get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> time = (Map<String, Object>) answers.get("ST-020");
        assertEquals(510, time.get("value"), "구조화 값은 그대로 실린다");
        assertEquals("08:30", time.get("display"));

        // 목록형도 읽을 수 있게 펼쳐진다.
        assertEquals("collectionTime", spec.answers().get("ST-002").display());
    }

    @Test
    @DisplayName("UT-326 SimulationConfig에 수집 산물(원문 답변·가정)이 실리지 않는다")
    void collectionArtifactsDoNotLeakIntoConfig() throws Exception {
        Map<String, Object> answers = baseAnswers();
        answers.put("ST-001", "이_문장은_설정에_들어가면_안_된다");
        var built = builder.build(def, accept(answers));
        SimulationConfig cfg = built.spec().toSimulationConfig();

        String serialized = new ObjectMapper().writeValueAsString(cfg);
        assertFalse(serialized.contains("이_문장은_설정에_들어가면_안_된다"),
                "목적 문장은 계산에 쓰이지 않는다 — 설정에 실리면 안 된다(D-50)");
        assertFalse(serialized.contains("simulationGoal"));
        assertFalse(serialized.contains("assumptions"));
        assertFalse(serialized.contains("appliedDefaults"));
        assertFalse(serialized.contains("subtaskSetId"));

        // 반대로 명세에는 남아 있어야 한다 — 감사(NFR-20)가 되짚는 자리다.
        assertEquals("이_문장은_설정에_들어가면_안_된다",
                built.spec().answers().get("ST-001").value());
        assertEquals("simulationGoal", built.spec().answers().get("ST-001").field());
    }

    @Test
    @DisplayName("UT-327 선택된 도구·시나리오 유형·엔진이 명세에 기록되고 미리보기에 표시된다")
    void toolAndEngineSelectionIsRecorded() {
        var single = builder.build(def, accept(baseAnswers())).spec();
        assertEquals("run_waste_simulation", single.toolName());
        assertEquals(SimulationModelRegistry.DEFAULT_MODEL_ID, single.engineId());
        assertTrue(single.previewText().contains("run_waste_simulation"));
        assertTrue(single.previewText().contains(single.engineId()));

        // Python 엔진을 고르면 <b>기존</b> Python 모델 도구로 간다 — 새 실행 경로를
        // 만들지 않는다(FR-134).
        Map<String, Object> python = baseAnswers();
        python.put("ST-042", "python");
        var pySpec = builder.build(def, accept(python)).spec();
        assertEquals("run_waste_simulation_devs", pySpec.toolName());
        assertEquals("python-devs", pySpec.engineId());

        // 시나리오 실험은 유형과 무관하게 기존 run_scenario로 간다.
        Map<String, Object> sweep = V2Answers.with("ST-035", "collection-sweep");
        var sweepSpec = builder.build(def, accept(sweep)).spec();
        assertEquals("run_scenario", sweepSpec.toolName());
        assertEquals("collection-sweep", sweepSpec.scenarioType());
    }
}
