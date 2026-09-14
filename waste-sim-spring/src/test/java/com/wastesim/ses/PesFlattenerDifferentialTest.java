package com.wastesim.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.TrafficDataService;
import com.wastesim.subtask.JangnyangSubtaskValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 측정 2 — SES 경로만으로 같은 시뮬레이션을 구성할 수 있는가.
 *
 * <p>숫자가 아니라 {@link SimulationConfig}를 비교한다. 결과를 비교하면 "다르다"까지만
 * 알 수 있지만 설정을 비교하면 <b>어느 필드가</b> 다른지 바로 나온다.
 *
 * <p>세 그룹으로 나눈다.
 * <ul>
 *   <li>{@link #sesPathProducesTheSameConfigAsTheBuilder} — 소비되는 값을 다 답한 조합들.
 *       완전히 같아야 한다. "해당 없음"이 <b>양쪽 다 안전하게 걸러지는</b> 필드를 답한
 *       조합도 여기 포함한다(I5) — {@code dischargeWindow}·{@code collectionTimes}·
 *       {@code routeAvailableCapacityKg}·{@code dispatchIntervalMinutes}·
 *       {@code serviceMinutesPerSite}·{@code intraZoneTravelMinutes}·{@code routeSequence}는
 *       {@code PesFlattener}·{@code toConfig()} 둘 다 {@code instanceof Number}/
 *       {@code instanceof List} 검사로 마커 문자열을 세터 이전에 걸러 내므로 갈라지지
 *       않는다 — 이것도 측정 결과다.</li>
 *   <li>{@link #trafficDefaultProfileFallbackDiffersOnlyInTrafficProfileId},
 *       {@link #travelTimeFloorDiffersOnlyInRouteTravelMinutes},
 *       {@link #notApplicableZoneAssignmentRuleLeaksAsLiteralMarker} — 평탄화기가
 *       <b>일부러</b> 옮기지 않은 서버 계산 기본값이나, 걸러 내지 않은 "해당 없음" 마커가
 *       실제로 새는 자리들(task-9-report.md, I5). 그 필드 하나만 달라야 한다 — 다른
 *       필드까지 갈라지면 새 버그다.</li>
 *   <li>측정 2의 범위 한정 — 이 테스트가 직접 부르는 것은 {@code SesPruner}·
 *       {@code PesFlattener}뿐이고, 제품 경로가 "해당 없음"을 걸러 내는 어댑터
 *       ({@code JangnyangScenarioBuilder.answersByFieldForPruning}, {@code SesPathWiringTest}가
 *       지킨다)를 거치지 않는다. 그래서 위 "새는" 테스트가 보여주는 격차는 <b>제품
 *       경로에는 없다</b> — 이 어댑터를 우회했을 때만 드러나는, {@code SesPruner}·
 *       {@code PesFlattener} 두 클래스 자체의 성질이다. 자세한 범위는
 *       {@code docs/research/s1-ses-extraction/유도본-v4-대조.md}의 "측정 2" 절 참고.
 *       (trafficMode=APPLY에서 trafficProfileId를 "해당 없음"으로 답하는 조합도 시도해
 *       봤지만, {@code ReferenceConfigPath}가 쓰는 기존 조립기 자체가
 *       {@code JangnyangCompletenessChecker.trafficWithoutProfile}에서 그 조합을 미답과
 *       똑같이 거부해 대조할 기준선을 만들 수 없었다 — 이 조합은 애초에 어느 경로로도
 *       끝까지 갈 수 없다는 것 자체가 측정 결과다.)</li>
 * </ul>
 */
class PesFlattenerDifferentialTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** {@link JangnyangSubtaskValidator#NOT_APPLICABLE} 축약 — 아래 답변 조합들이 반복해서 쓴다. */
    private static final String NA = JangnyangSubtaskValidator.NOT_APPLICABLE;

    /**
     * 평탄화기·조립기 둘 다 실제로 소비하는 값을 전부 채운 기준 답변. 여기서 값을 하나씩
     * 바꿔 가며 분기를 밟는다.
     *
     * <p><b>기준 답변 자체는 "해당 없음"을 쓰지 않는다.</b> {@code SesPruner}는 이 마커
     * 문자열을 속성값으로 걸러 내지 않는데({@code toConfig()}의 {@code Fields.value()}는
     * 걸러낸다) 그대로 두면 마커 자체가 세터로 흘러들어 이 조합이 확인하려는 것(같은 답 →
     * 같은 설정)과 무관한 자리에서 실패한다. 그래서 이 기준 답변은 실제로 반영되는
     * 구체값으로 채운다 — 대신 "해당 없음"은 아래 별도 조합들이 다룬다(I5): 걸러지는
     * 필드는 {@link #answerSets}에 포함해 "같아야 한다" 그룹에서 확인하고, 걸러지지
     * 않는 필드(zoneAssignmentRule)는 {@link #notApplicableZoneAssignmentRuleLeaksAsLiteralMarker}가
     * 이름 붙여 고정한다.
     */
    private static Map<String, Object> base() {
        Map<String, Object> a = new LinkedHashMap<>();
        // SES와 무관한 절차 제어 필드(SesFieldMapping.nonSesFields) — v4 완전성 판정만
        // 통과하면 되므로 계산에 영향 없는 값을 채운다.
        a.put("simulationGoal", "차등 테스트용 목적 문장");
        a.put("engine", "java");
        a.put("defaultApproval", "ALL");

        a.put("scenarioType", "single-run");
        a.put("numBuildings", 10);
        // 20이 아니라 10으로 둔다 — EVERY_7_DAYS처럼 수거 주기가 긴 조합까지 밟다 보면
        // 공통 검증기(V-T2, 예측 적재율 120% 초과)가 PesFlattener와 무관하게 막는다.
        // 주 1회 수거에서도 여유가 있도록 하루 배출량을 낮춰 둔다.
        a.put("residentsPerBuilding", 10);
        a.put("occupationPreset", "BALANCED");
        a.put("days", 7);
        a.put("seeds", 3);
        a.put("wasteMeanKg", 0.9);
        a.put("wasteSigma", 0.3);
        a.put("leaveSigma", 30.0);
        a.put("dischargeTimeMode", "PAPER_BASELINE");
        a.put("dischargeWindow", List.of(1200, 360));
        a.put("capacity", 60.0);
        a.put("threshold", 0.8);
        a.put("collectionTime", 720);
        a.put("collectionTimes", List.of(600));
        a.put("collectionSchedule", "EVERY_DAY");
        a.put("truckType", "LARGE_5TON");
        a.put("truckCount", 1);
        // 세 차종(5톤·2.5톤·1톤) 중 가장 작은 정격용량(1000kg)보다 작게 둔다 — 차종을
        // 바꿔 가며 밟는 조합에서도 공통 검증기(경로 배정용량 ≤ 정격용량)를 통과해야 한다.
        a.put("routeAvailableCapacityKg", 900.0);
        a.put("initialTruckLoadKg", 0.0);
        a.put("dispatchIntervalMinutes", 0);
        a.put("trafficMode", "NONE");
        a.put("trafficProfileId", "jangryang-weekday");
        a.put("travelTimeMode", "LEGACY_CONSTANT");
        a.put("routeTravelMinutes", 15);
        a.put("serviceMinutesPerSite", 5);
        a.put("intraZoneTravelMinutes", 8);
        a.put("zoneAssignmentRule", "NONE");
        // STRING_LIST는 "해당 없음"이 들어와도 안전하다 — instanceof List 검사가 걸러
        // 주므로 두 경로 모두 세터를 부르지 않는다. 건물 수(10동)와 정확히 일치하는
        // 순열을 매번 만드는 대신 자동 생성 순서에 맡긴다.
        a.put("routeSequence", JangnyangSubtaskValidator.NOT_APPLICABLE);
        return a;
    }

    private static Map<String, Object> answers(Consumer<Map<String, Object>> tweak) {
        Map<String, Object> a = base();
        tweak.accept(a);
        return a;
    }

    /** 답변 조합. 분기가 있는 필드는 값을 바꿔 가며 모든 갈래를 밟는다. */
    private static List<Map<String, Object>> answerSets() {
        return List.of(
                answers(m -> { }),
                answers(m -> m.put("collectionSchedule", "EVERY_2_DAYS")),
                answers(m -> m.put("collectionSchedule", "EVERY_3_DAYS")),
                answers(m -> m.put("collectionSchedule", "EVERY_7_DAYS")),
                answers(m -> m.put("collectionSchedule", "WEEKDAYS_MON_FRI")),
                answers(m -> m.put("collectionSchedule", "MON_WED_FRI")),
                answers(m -> m.put("collectionSchedule", "POHANG_MON_TUE_THU_FRI")),
                // ZONE_PROXY_HYBRID의 구역 간 자유주행시간 실측표는 4동 기본 배정에서만
                // 전 구간이 채워져 있다(ZoneAssignmentRuleTest 참고) — 10동으로 두면 이
                // 테스트와 무관한 공통 검증기 오류로 막힌다.
                answers(m -> {
                    m.put("travelTimeMode", "ZONE_PROXY_HYBRID");
                    m.put("numBuildings", 4);
                }),
                // OSRM_HYBRID는 뺀다 — 이 테스트 환경에는 수거 지점 좌표가 하나도 없어서
                // (empty-sites.json) 공통 검증기가 PesFlattener와 무관하게 항상 거부한다.
                // 어느 조합을 넣어도 두 경로를 비교할 수조차 없다.
                answers(m -> {
                    m.put("trafficMode", "APPLY");
                    m.put("trafficProfileId", "jangryang-volume-weekday");
                }),
                answers(m -> m.put("collectionTimes", List.of(360, 1080))),
                answers(m -> m.put("dischargeTimeMode", "POHANG_ACTUAL")),
                answers(m -> m.put("occupationPreset", "UNIVERSITY")),
                answers(m -> m.put("occupationPreset", "INDUSTRIAL")),
                answers(m -> m.put("occupationPreset", "FAMILY")),
                answers(m -> m.put("truckType", "MEDIUM_2P5T")),
                answers(m -> m.put("truckType", "SMALL_1TON")),
                answers(m -> m.put("truckCount", 2)),
                // I5 — "해당 없음"이 실제로 안전한 필드들을 한꺼번에 답한다. 여섯 필드
                // 모두 PesFlattener·toConfig() 양쪽에서 instanceof Number/List 검사가
                // 마커 문자열을 세터 이전에 걸러 낸다 — 걸러지지 않는 두 필드
                // (zoneAssignmentRule·trafficMode=APPLY의 trafficProfileId)는 여기 넣지
                // 않고 아래 별도 테스트로 고정한다.
                answers(m -> {
                    m.put("dischargeWindow", NA);
                    m.put("collectionTimes", NA);
                    m.put("routeAvailableCapacityKg", NA);
                    m.put("dispatchIntervalMinutes", NA);
                    m.put("serviceMinutesPerSite", NA);
                    m.put("intraZoneTravelMinutes", NA);
                }),
                // I5 — routeSequence가 "해당 없음"이 아니라 실제 목록으로 대조된 적이 한
                // 번도 없었다. 건물 수(10동)와 정확히 일치하는 순열을 답한다.
                answers(m -> m.put("routeSequence", List.of(
                        "Node_A", "Node_B", "Node_C", "Node_D", "Node_E",
                        "Node_F", "Node_G", "Node_H", "Node_I", "Node_J")))
        );
    }

    @Test
    void sesPathProducesTheSameConfigAsTheBuilder() throws Exception {
        for (Map<String, Object> answers : answerSets()) {
            SimulationConfig viaSes = PesFlattener.flatten(SesPruner.prune(answers));
            SimulationConfig viaBuilder = ReferenceConfigPath.build(answers);
            assertEquals(MAPPER.writeValueAsString(viaBuilder), MAPPER.writeValueAsString(viaSes),
                    "답변 " + answers + " 에서 두 경로의 설정이 다르다");
        }
    }

    // ── 알려진 두 갈래(Ruling 8) — 숨기지 않고 이름 붙은 테스트로 고정한다 ─────────────

    /**
     * (a) {@code trafficMode=APPLY}인데 {@code trafficProfileId}가 "default"인 경우.
     *
     * <p>완전성 판정({@code JangnyangCompletenessChecker.trafficWithoutProfile})은
     * 미답이나 "해당 없음"은 막지만 "default"라는 문자열 자체는 유효한 답으로 통과시킨다
     * (v4의 실제 허용값 목록에는 없지만, 이 조립기는 서브태스크 검증을 다시 하지 않는다).
     * {@code toConfig()}는 그 문자열을 특별 취급해 실측 기본 프로파일로 바꿔치기하지만,
     * {@code PesFlattener}는 PES가 가진 값을 그대로 옮기므로 "default" 문자열이 그대로
     * 세터에 들어간다 — trafficProfileId 딱 한 필드만 달라야 한다.
     */
    @Test
    void trafficDefaultProfileFallbackDiffersOnlyInTrafficProfileId() throws Exception {
        Map<String, Object> answers = answers(m -> {
            m.put("trafficMode", "APPLY");
            m.put("trafficProfileId", "default");
        });

        SimulationConfig viaSes = PesFlattener.flatten(SesPruner.prune(answers));
        SimulationConfig viaBuilder = ReferenceConfigPath.build(answers);

        assertNotEquals(viaBuilder.getTrafficProfileId(), viaSes.getTrafficProfileId(),
                "trafficProfileId가 갈라지는 게 이 테스트의 요점인데 같게 나왔다 — 조립기가 바뀌었을 수 있다");
        assertEquals("default", viaSes.getTrafficProfileId(),
                "PesFlattener는 PES가 가진 값을 그대로 옮긴다 — \"default\" 문자열 자체가 넘어가야 한다");
        assertEquals(new TrafficDataService().defaultProfileId(), viaBuilder.getTrafficProfileId(),
                "toConfig()는 \"default\"를 실측 기본 프로파일로 바꿔치기한다");

        assertSameExceptTrafficProfileId(viaBuilder, viaSes);
    }

    /** Explicit zero is now preserved by both construction paths. */
    @Test
    void explicitZeroTravelTimeMatchesBothPaths() throws Exception {
        Map<String, Object> answers = answers(m -> {
            m.put("trafficMode", "APPLY");
            m.put("routeTravelMinutes", 0);
        });
        SimulationConfig viaSes = PesFlattener.flatten(SesPruner.prune(answers));
        SimulationConfig viaBuilder = ReferenceConfigPath.build(answers);
        assertEquals(0, viaSes.getRouteTravelMinutes());
        assertEquals(0, viaBuilder.getRouteTravelMinutes());
        assertSameExceptRouteTravelMinutes(viaBuilder, viaSes);
    }

    /**
     * (c) I5 — {@code zoneAssignmentRule}을 "해당 없음"으로 답한 경우.
     *
     * <p>{@code PesFlattener.flatten}은 {@code strOf(pes, "zoneAssignmentRule", ...)}를
     * <b>무조건</b>(travelTimeMode 분기 밖에서) 부르고, {@code strOf}는 값이 null이
     * 아니기만 하면 그대로 세터에 넘긴다 — {@code SesPruner}가 "해당 없음" 마커를
     * 속성값에서 걸러 내지 않으므로 마커 문자열 자체가 세터에 들어간다.
     * {@code toConfig()}는 {@code Fields.str}가 마커를 null로 걸러 준 뒤 "null이 아닐
     * 때만 세팅"하므로 답하지 않은 것으로 보고 기본값(null)을 그대로 둔다 —
     * zoneAssignmentRule 딱 한 필드만 달라야 한다.
     */
    @Test
    void notApplicableZoneAssignmentRuleLeaksAsLiteralMarker() throws Exception {
        Map<String, Object> answers = answers(m -> m.put("zoneAssignmentRule", NA));

        SimulationConfig viaSes = PesFlattener.flatten(SesPruner.prune(answers));
        SimulationConfig viaBuilder = ReferenceConfigPath.build(answers);

        assertNotEquals(viaBuilder.getZoneAssignmentRule(), viaSes.getZoneAssignmentRule(),
                "zoneAssignmentRule이 갈라지는 게 이 테스트의 요점인데 같게 나왔다 — "
                        + "PesFlattener나 SesPruner가 마커를 걸러 내도록 바뀌었을 수 있다");
        assertEquals(NA, viaSes.getZoneAssignmentRule(),
                "PesFlattener는 PES가 가진 값을 그대로 옮긴다 — 마커 문자열 자체가 넘어가야 한다");
        assertNull(viaBuilder.getZoneAssignmentRule(),
                "toConfig()는 마커를 걸러 내 세터를 부르지 않는다 — 기본값(null)이 남아야 한다");

        assertSameExcept(viaBuilder, viaSes, "zoneAssignmentRule");
    }

    // (d) I5 — trafficMode=APPLY에서 trafficProfileId를 "해당 없음"으로 답하는 조합도
    // 시도해 봤지만 대조 대상이 될 수 없었다. ReferenceConfigPath가 쓰는 기존 조립기는
    // JangnyangCompletenessChecker.trafficWithoutProfile을 거치는데, 그 판정은 "교통을
    // 켰는데 프로파일이 없거나 해당 없음이면 막는다"고 명시돼 있다 — 이 조합은 v4
    // 경로에서 애초에 조립까지 가지 못하고 missing으로 거부된다(실제로 실행해 확인했다:
    // ReferenceConfigPath.build가 IllegalStateException을 던진다). SesPruner·
    // PesFlattener만 단독으로 부르면 마커가 그대로 세터에 들어간 SimulationConfig를
    // 만들어 내지만, 비교할 기존 경로 결과 자체가 없어 "갈라진다/안 갈라진다"를 애초에
    // 물을 수 없다 — zoneAssignmentRule과 달리 이 조합은 어느 경로로도 끝까지 갈 수
    // 없다는 것 자체가 측정 결과다(유도본-v4-대조.md 측정 2 절 참고).

    /** {@code trafficProfileId}를 뺀 나머지 필드가 전부 같은지 JSON 트리로 비교한다. */
    private static void assertSameExceptTrafficProfileId(SimulationConfig a, SimulationConfig b)
            throws Exception {
        assertSameExcept(a, b, "trafficProfileId");
    }

    private static void assertSameExceptRouteTravelMinutes(SimulationConfig a, SimulationConfig b)
            throws Exception {
        assertSameExcept(a, b, "routeTravelMinutes");
    }

    /** 지정한 필드 하나를 지우고 나머지가 완전히 같은지 비교한다. */
    private static void assertSameExcept(SimulationConfig a, SimulationConfig b, String field)
            throws Exception {
        ObjectNode nodeA = (ObjectNode) MAPPER.valueToTree(a);
        ObjectNode nodeB = (ObjectNode) MAPPER.valueToTree(b);
        nodeA.remove(field);
        nodeB.remove(field);
        assertEquals(MAPPER.writeValueAsString(nodeA), MAPPER.writeValueAsString(nodeB),
                field + "를 제외한 나머지 필드에서 예상치 못한 차이가 생겼다");
    }
}
