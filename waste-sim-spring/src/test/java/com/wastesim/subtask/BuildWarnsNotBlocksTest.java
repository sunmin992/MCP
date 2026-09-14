package com.wastesim.subtask;

import com.wastesim.ledger.JangnyangRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 단계적 강제의 경계를 고정한다.
 *
 * <p>원장과 기존 checker는 기준이 다르다 — 원장은 {@code BasisKind.NONE}을 막지만 checker는
 * 그 필드를 {@code required=false}로 통과시킬 수 있다. 조립부터 강제하면 지금 통과하던
 * 구성이 갑자기 막히고, 그것이 진짜 결함인지 두 기준의 차이인지 구분할 데이터가 없다.
 */
class BuildWarnsNotBlocksTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    @Test
    void 원장이_막아도_조립은_성공한다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        // trafficMode를 NONE으로 한 번 넣어 프로필을 "규칙에 의해 해당 없음"으로 떨어뜨린
        // 뒤 APPLY로 되돌린다 — 그래야 ACTIVE 분기가 미해결 프로필을 다시 연다. APPLY를
        // 곧장 다시 내면(이미 answerEverything이 APPLY로 채워 둔 값이라) 실행 가능한 값이
        // 이미 있어 아무 일도 일어나지 않는다.
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);

        SubtaskSessionService.BuildStep build = sessions.build("k");

        assertTrue(build.ok(), "조립 단계에서 원장이 막으면 단계적 강제가 아니다: "
                + build.message());
    }

    @Test
    void 막힌_사유는_경고로_실린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);

        SubtaskSessionService.BuildStep build = sessions.build("k");

        assertFalse(build.ledgerWarnings().isEmpty(),
                "막을 이유가 있는데 아무 말도 하지 않으면 데이터가 모이지 않는다");
        assertTrue(build.ledgerWarnings().stream().anyMatch(w -> w.contains("trafficProfileId")),
                "무엇이 막는지 알 수 없는 경고는 쓸모가 없다: " + build.ledgerWarnings());
    }

    @Test
    void 막을_것이_없으면_경고도_없다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        // v5에서 trafficProfileId(order 21)는 trafficMode(order 29)보다 먼저 묻는다 —
        // answerEverything이 trafficMode의 첫 허용값(APPLY)을 고르기 전에 이미
        // trafficProfileId를 답한 상태라, 그 시점의 활성 판정은 RuleRegistry.fieldEquals가
        // "미답 = UNKNOWN"으로 보고 일단 막아 둔다. trafficMode가 뒤늦게 APPLY로 확정돼도
        // 그 막힘은 "활성/비활성"이 아니라 "필수값 미해결"로 이름만 바뀌어 그대로 남는다
        // (LedgerRecalculator.decisionFor의 ACTIVE 분기는 현재 결정이 이미 실행 가능한
        // 상태일 때만 손대지 않는다). 이 항목이 실제로 막을 이유가 없는 "정상 구성"이 되려면
        // 교통을 아예 끄고(trafficMode=NONE) 그 가지를 비활성으로 확정해야 한다 — 그러면
        // 원장이 trafficProfileId를 "규칙에 의해 해당 없음"으로 채우고, 그 자리표시자는
        // 실행 가능한 상태라 막지 않는다.
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);

        SubtaskSessionService.BuildStep build = sessions.build("k");

        assertTrue(build.ok(), build.message());
        assertEquals(java.util.List.of(), build.ledgerWarnings(),
                "정상 구성에 경고가 붙으면 경고가 잡음이 된다");
    }

    @Test
    void 모든_질문에_답하면_READY에_도달한다() {
        SubtaskTestSupport.answerEverything(sessions, "k");

        JangnyangSubtaskSession session = sessions.activeSession("k");

        // 이 확인이 없으면 sampleAnswerFor가 조용히 못 미친 채 멈춰도(재질문 고리에
        // 빠지지 않는 한) 아무도 알아채지 못한다 — Task 6·7·8이 전부 이 헬퍼가 실제로
        // READY까지 보낸다는 것을 전제로 쌓인다.
        assertEquals(SubtaskState.READY, session.state(),
                "answerEverything이 끝났는데 READY가 아니면 뒤 작업들의 전제가 깨진다");
    }
}
