package com.wastesim.subtask;

import com.wastesim.ledger.BlockingReasons;
import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구조를 바꾸는 답변이 이미 받은 답변의 전제를 무너뜨렸는지 따지는가.
 *
 * <p>지금까지는 교통을 껐다 켜도 이전 프로필 답변이 그대로 남았다. 그 답은 다른 구조에서
 * 고른 값이라 더 믿을 수 없다.
 */
class SubmitRecalculatesTest {

    private final SubtaskSessionService sessions = SubtaskTestSupport.service();

    private static final String PROFILE =
            JangnyangLedgerWiring.parameterIdOf("trafficProfileId");

    private void answer(String field, Object value) {
        sessions.submit("k", SubtaskTestSupport.idOfField(sessions, "k", field), value, null);
    }

    @Test
    void 교통을_켜면_프로필이_물어야_할_자리로_드러난다() {
        sessions.start("k");
        // trafficMode의 허용값은 [APPLY, NONE] — APPLY가 먼저다. 그런데 ACTIVE 가지는
        // 이미 실행 가능한 결정이 있으면 손대지 않으므로, 한 번도 건드리지 않은 프로필에
        // 곧바로 APPLY를 주면 결정기록에 아무 기록도 남지 않을 수 있다. NONE을 먼저 넣어
        // "규칙이 만든 해당 없음" 자리로 만들고, 그 다음 APPLY로 되돌려 ACTIVE 가지가
        // 그 자리를 UNRESOLVED로 다시 여는 전이를 명시적으로 일으킨다.
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, "NONE");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE);

        ParameterDecision d = sessions.activeSession("k").ledger().current(PROFILE);
        assertNotNull(d);
        assertEquals(DecisionState.UNRESOLVED, d.state());
        assertEquals(BlockingReasons.REQUIRED_VALUE_UNRESOLVED, d.blockingReason());
    }

    @Test
    void 교통을_끄면_프로필은_해당_없음으로_확정된다() {
        sessions.start("k");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, "NONE");

        ParameterDecision d = sessions.activeSession("k").ledger().current(PROFILE);
        assertNotNull(d);
        assertEquals(DecisionState.DEFAULTED, d.state());
        assertTrue(d.state().executable(), "비활성 가지가 실행을 막으면 과차단이다");
    }

    @Test
    void 교통을_껐다_켜면_이전_프로필_답변이_낡는다() {
        sessions.start("k");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE);
        answer("trafficProfileId", "jangryang-weekday");
        assertTrue(sessions.activeSession("k").ledger().current(PROFILE).state().executable(),
                "전제 조건: 프로필을 답하면 확정된다");

        answer(JangnyangRules.TRAFFIC_MODE_FIELD, "NONE");
        answer(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE);

        ParameterDecision d = sessions.activeSession("k").ledger().current(PROFILE);
        assertFalse(d.state().executable(),
                "다른 구조에서 고른 프로필이 그대로 살아 있으면 안 된다");
    }
}
