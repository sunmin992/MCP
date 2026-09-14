package com.wastesim.subtask;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 부품이 아니라 <b>흐름</b>을 지킨다 — 답변 제출부터 조립·실행 승인까지 결정기록을 낀 전체
 * 경로를 오류 주입과 과차단을 짝으로 둔다. 오류 주입만 있으면 "무엇이든 막는 문지기"가
 * 같은 점수를 받는다(필수값 누락 0, 실행 성공률 무관). 과차단 쪽 테스트가 그 착시를 깬다.
 *
 * <p>{@link BuildWarnsNotBlocksTest}가 이미 "조립이 막히고 다시 열리는" 경로와 "정상
 * 조립"을 각각 지킨다. 이 파일은 그 위에서 <b>조립 이후 실행 승인까지</b> 이어지는 것과,
 * 조립 단계에서 끝나지 않는 결정기록 고유의 성질(막힌 상태에서의 실행 차단, 재계산 후 실행
 * 재개, 이력 보존)만 더한다 — 같은 시나리오를 다시 조립하는 부분은 반복하지 않는다.
 */
class LedgerIntegrationFlowTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    // ---- 과차단: 정상 흐름은 조립을 지나 실행 승인까지 끝까지 간다 ----

    @Test
    void 전부_답한_구성은_조립도_실행도_열린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");

        SubtaskSessionService.BuildStep build = sessions.build("k");
        assertTrue(build.ok(), build.message());

        RunApproval approval = sessions.approveRunChecked("k");
        assertTrue(approval.approved(), approval.message());
    }

    @Test
    void 교통을_끈_구성도_실행까지_끝까지_간다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);

        assertTrue(sessions.build("k").ok());
        assertTrue(sessions.approveRunChecked("k").approved(),
                "비활성 가지가 실행을 막으면 과차단이다");
    }

    // ---- 오류 주입: 알려진 구멍은 실행 전에, 조립 단계에서부터 막힌다 ----

    @Test
    void 교통을_켜고_프로필을_답하지_않으면_조립도_실행도_막힌다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        // trafficMode를 NONE으로 돌렸다가 다시 APPLY로 바꿔야 확실히 "변경"으로 잡힌다 —
        // answerEverything이 이미 APPLY를 답했을 수 있어, 그 위에 그대로 APPLY를 다시
        // 제출하면 결정기록이 값의 "도착"이 아니라 무변화로 읽어 아무 재계산도 일으키지 않는다.
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);

        // 조립은 이제 경고만 하지 않는다 — 결정기록에 미해결 필수값이 있으면 막는다(fail-closed).
        SubtaskSessionService.BuildStep build = sessions.build("k");
        assertFalse(build.ok(), "미해결 프로필을 두고 조립이 열리면 과차단 대신 오류 주입이 뚫린 것이다");
        assertTrue(build.message().contains("trafficProfileId"), build.message());

        // 조립이 막혔으니 세션엔 조립된 시나리오가 없다 — 실행도 당연히 열리지 않는다.
        assertNull(sessions.approveRun("k"), "조립되지 않은 세션의 실행은 막혀야 한다");
    }

    @Test
    void 프로필을_답하면_조립도_실행도_다시_열린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);
        assertFalse(sessions.build("k").ok());
        assertNull(sessions.approveRun("k"));

        // 막힌 자리(트래픽 프로필)를 채운다 — currentStep이 그 질문을 되돌려주고 있어야 한다.
        assertEquals("trafficProfileId", sessions.currentStep("k").question().answerField());
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", "trafficProfileId"),
                SubtaskTestSupport.sampleAnswerFor(
                        sessions.definitionOf(sessions.activeSession("k"))
                                .byId(SubtaskTestSupport.idOfField(sessions, "k", "trafficProfileId"))),
                null);

        SubtaskSessionService.BuildStep repaired = sessions.build("k");
        assertTrue(repaired.ok(), repaired.message());

        RunApproval approval = sessions.approveRunChecked("k");
        assertTrue(approval.approved(),
                "막힌 자리를 채웠는데도 열리지 않으면 재계산이 갱신되지 않은 것이다: "
                        + approval.message());
    }

    @Test
    void 답을_고치면_결정기록에_이력이_남는다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        String daysId = SubtaskTestSupport.idOfField(sessions, "k", "days");
        sessions.submit("k", daysId, 3, null);
        sessions.submit("k", daysId, 5, null);

        var history = sessions.activeSession("k").ledger()
                .history(JangnyangLedgerWiring.parameterIdOf("days"));

        assertTrue(history.size() >= 2, "값을 고친 이력이 남지 않았다");
    }
}
