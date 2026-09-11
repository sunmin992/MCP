package com.wastesim.ses;

import com.wastesim.subtask.JangnyangCompletenessChecker;
import com.wastesim.subtask.JangnyangScenarioBuilder;
import com.wastesim.subtask.JangnyangScenarioSpec;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskAnswer;
import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.JangnyangSubtaskValidator;
import com.wastesim.subtask.SubtaskAnswerSource;
import com.wastesim.subtask.TestSubtaskFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11 — 유도한 v5가 실제로 최신 세트로 쓰이는가(연결 확인).
 */
class SesPathWiringTest {

    @Test
    void latestSetIsTheDerivedV5() {
        JangnyangSubtaskDefinition latest = new JangnyangSubtaskCatalog().latest();
        assertEquals("jangnyang-simulator-v5", latest.subtaskSetId());
        assertEquals(34, latest.subtasks().size());
    }

    @Test
    void v4IsStillAvailableForOldSessions() {
        assertNotNull(new JangnyangSubtaskCatalog().byVersion(4),
                "진행 중이던 세션이 무엇을 물어서 받은 답인지 알 수 없게 되면 안 된다");
    }

    /**
     * 넘겨받은 미해결 사항 1 — "관측" 가지에 결정 지점이 없어서 생기는 빈 화면 단계가
     * 실제로 사라졌는가. 그룹 번호가 1부터 연속이고, 그 어떤 그룹도 문항이 0개면 안 된다.
     */
    @Test
    void noGroupIsEmpty() {
        JangnyangSubtaskDefinition v5 = new JangnyangSubtaskCatalog().byVersion(5);
        assertNotNull(v5);
        for (int g = 1; g <= v5.groupCount(); g++) {
            assertNotNull(v5.group(g), g + "단계 정의가 없다");
            assertFalse(v5.subtasksInGroup(g).isEmpty(), g + "단계에 문항이 하나도 없다");
        }
    }

    /**
     * 넘겨받은 미해결 사항 2 — "해당 없음"이 PES까지 새지 않는가.
     *
     * <p>{@code SesPruner}는 이 표식을 모른다(트리 지식만 갖는다). 그래서 연결부
     * ({@code JangnyangScenarioBuilder})가 걸러내야 하는데, 걸러내지 않으면
     * "해당 없음" 문자열이 그대로 PES 값에 들어간다. {@code routeSequence}를
     * 해당 없음으로 답하고, 조립된 명세의 PES에 그 필드가 아예 없는지 본다 —
     * 있다면(값이 무엇이든) 걸러내기가 빠진 것이다.
     */
    @Test
    void notApplicableAnswersDoNotLeakIntoThePrunedStructure() {
        JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
        JangnyangScenarioBuilder builder = TestSubtaskFixtures.builder(checker);
        JangnyangSubtaskDefinition v5 = new JangnyangSubtaskCatalog().byVersion(5);

        Map<String, Object> byField = new LinkedHashMap<>();
        byField.put("scenarioType", "single-run");
        byField.put("dischargeTimeMode", "PAPER_BASELINE");
        byField.put("truckType", "LARGE_5TON");
        byField.put("travelTimeMode", "LEGACY_CONSTANT");
        byField.put("numBuildings", 10);
        byField.put("residentsPerBuilding", 10);
        byField.put("truckCount", 1);
        byField.put("trafficMode", "NONE");
        byField.put("occupationPreset", "BALANCED");
        byField.put("days", 7);
        byField.put("seeds", 3);
        byField.put("wasteMeanKg", 0.9);
        byField.put("wasteSigma", 0.3);
        byField.put("leaveSigma", 30.0);
        byField.put("dischargeWindow", List.of(1200, 360));
        byField.put("capacity", 60.0);
        byField.put("threshold", 0.8);
        byField.put("collectionTime", 720);
        byField.put("collectionTimes", List.of(600));
        byField.put("collectionSchedule", "EVERY_DAY");
        byField.put("routeAvailableCapacityKg", 900.0);
        byField.put("initialTruckLoadKg", 0.0);
        byField.put("dispatchIntervalMinutes", 0);
        byField.put("serviceMinutesPerSite", 5);
        byField.put("trafficProfileId", "jangryang-weekday");
        byField.put("routeTravelMinutes", 15);
        byField.put("intraZoneTravelMinutes", 8);
        byField.put("zoneAssignmentRule", "NONE");
        // 이 필드만 "해당 없음"이다 — 이 테스트가 확인하려는 자리.
        byField.put("routeSequence", JangnyangSubtaskValidator.NOT_APPLICABLE);
        // 세트에 없는 엔진("python" 어댑터만 등록돼 있다)을 고르면 조립기가 지원
        // 여부까지 판정하려 들어 이 테스트와 무관한 자리에서 막힐 수 있다 — 등록되지
        // 않은 엔진 ID를 골라 그 판정 자체를 건너뛴다.
        byField.put("engine", "java");
        byField.put("simulationGoal", "PES 연결부 테스트용 목적 문장");
        byField.put("defaultApproval", "ALL");
        byField.put("inputAndScenarioConfirmed", "CONFIRMED");
        byField.put("executionApproval", "RUN");

        Map<String, JangnyangSubtaskAnswer> answers = new LinkedHashMap<>();
        for (JangnyangSubtask st : v5.subtasks()) {
            assertTrue(byField.containsKey(st.answerField()),
                    "테스트 답변이 v5 필드를 빠뜨렸다: " + st.answerField());
            Object value = byField.get(st.answerField());
            answers.put(st.id(), JangnyangSubtaskAnswer.accepted(
                    st.id(), String.valueOf(value), value, SubtaskAnswerSource.USER_DIRECT));
        }

        JangnyangScenarioBuilder.BuildOutcome outcome = builder.build(v5, answers);
        assertTrue(outcome.ok(), () -> "조립이 실패했다 — missing=" + outcome.missing()
                + ", configErrors=" + outcome.configErrors());

        JangnyangScenarioSpec spec = outcome.spec();
        assertNotNull(spec.prunedStructure(), "v5는 가지친 결과를 명세에 실어야 한다");
        assertFalse(spec.prunedStructure().attributeValues().containsKey("routeSequence"),
                "\"해당 없음\"이 연결부에서 걸러지지 않고 PES까지 샜다");
        // 걸러내기가 값을 통째로 지운 게 아니라 이 필드만 걸렀다는 것도 함께 본다.
        assertEquals(0.9, spec.prunedStructure().value("wasteMeanKg"));
    }
}
