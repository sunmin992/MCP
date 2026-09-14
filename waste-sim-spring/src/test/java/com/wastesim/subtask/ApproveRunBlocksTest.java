package com.wastesim.subtask;

import com.wastesim.ledger.JangnyangRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미해결 필수값이 있으면 실행 패키지를 발행하지 않는다 — 앞 스펙이 약속하고 지금까지
 * 지켜지지 않던 자리다.
 *
 * <p>기존 반환 계약({@code null} 또는 spec)을 바꾸지 않는다. 차단은 BUILT가 아닐 때와
 * 같은 {@code null}이고, 사유는 {@code approveRunChecked}가 들고 나온다.
 */
class ApproveRunBlocksTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    private void buildWithUnresolvedTraffic() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        // trafficMode 허용값은 ["APPLY","NONE"]이고 answerEverything이 이미 APPLY로
        // 채워 둔다 — 같은 값을 다시 내면 변화가 아니라서 프로필이 그대로 남는다.
        // NONE을 먼저 넣어 프로필을 "규칙에 의해 해당 없음"으로 떨어뜨린 뒤 APPLY로
        // 되돌려야 ACTIVE 분기가 미해결 프로필을 다시 연다(BuildWarnsNotBlocksTest와
        // 같은 순서).
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                "NONE", null);
        sessions.submit("k",
                SubtaskTestSupport.idOfField(sessions, "k", JangnyangRules.TRAFFIC_MODE_FIELD),
                JangnyangRules.TRAFFIC_APPLY_VALUE, null);
        assertTrue(sessions.build("k").ok(), "전제 조건: 조립은 성공해야 한다");
    }

    @Test
    void 원장이_막으면_실행이_열리지_않는다() {
        buildWithUnresolvedTraffic();
        assertNull(sessions.approveRun("k"),
                "미해결 필수값이 있는데 실행 설정이 나가면 fail-closed가 무너진다");
    }

    @Test
    void 차단_사유가_남는다() {
        buildWithUnresolvedTraffic();
        RunApproval approval = sessions.approveRunChecked("k");

        assertFalse(approval.approved());
        assertTrue(approval.blocks().stream().anyMatch(b -> b.contains("trafficProfileId")),
                "무엇이 막는지 알 수 없으면 사용자가 고칠 수 없다: " + approval.blocks());
    }

    @Test
    void 막힌_뒤에도_세션은_실행_상태로_넘어가지_않는다() {
        buildWithUnresolvedTraffic();
        sessions.approveRun("k");
        assertEquals(SubtaskState.BUILT, sessions.activeSession("k").state(),
                "차단했는데 RUNNING으로 올라가면 다음 호출이 실행을 믿는다");
    }

    @Test
    void 정상_구성은_실행이_열린다() {
        SubtaskTestSupport.answerEverything(sessions, "k");
        assertTrue(sessions.build("k").ok());

        assertNotNull(sessions.approveRun("k"),
                "막을 이유가 없는 구성을 막으면 과차단이다");
    }

    @Test
    void BUILT가_아니면_지금까지처럼_null이다() {
        sessions.start("k");
        assertNull(sessions.approveRun("k"), "기존 계약이 바뀌면 호출부가 조용히 깨진다");
    }
}
