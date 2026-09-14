package com.wastesim.ledger;

import com.wastesim.subtask.BasisKind;
import com.wastesim.subtask.SubtaskAnswerSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변 하나를 원장 레코드로 만드는 운영 경로가 불변식을 스스로 채우는가.
 *
 * <p>이 조립이 없으면 호출부마다 열세 개 인자를 다시 맞추게 되고, 사본은 본체가 바뀌어도
 * 컴파일이 통과하므로 갈라진 것을 아무도 모른다.
 */
class AnswerDecisionsTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final ValueSource USER = new ValueSource("user_explicit", "ST-01", null, T);
    private static final Transformation RULE =
            new Transformation("normalize-days", List.of("sim::days#1"));

    @Test
    void 사용자가_직접_넣은_답은_확정으로_조립된다() {
        ParameterDecision d = AnswerDecisions.fromAnswer("sim::days#1", "sim::days",
                "7일", 7, SubtaskAnswerSource.USER_DIRECT, BasisKind.NONE, USER, RULE, T);

        assertEquals(DecisionState.CONFIRMED, d.state());
        assertEquals(7, d.normalizedValue());
        assertEquals(USER, d.source());
        assertNull(d.blockingReason());
        // 확정값에 변환 규칙을 붙이면 "이 값은 유도된 것"이라는 거짓 이력이 남는다.
        assertNull(d.transformation());
    }

    @Test
    void 유도한_답은_변환_규칙을_달고_조립된다() {
        ParameterDecision d = AnswerDecisions.fromAnswer("sim::days#1", "sim::days",
                "일주일", 7, SubtaskAnswerSource.LLM_NORMALIZED, BasisKind.REGULATION,
                USER, RULE, T);

        assertEquals(DecisionState.DERIVED, d.state());
        assertEquals(RULE, d.transformation());
    }

    @Test
    void 근거_없는_기본값은_차단_사유를_달고_미해결이_된다() {
        ParameterDecision d = AnswerDecisions.fromAnswer("sim::days#1", "sim::days",
                null, 7, SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.NONE, USER, RULE, T);

        assertEquals(DecisionState.UNRESOLVED, d.state());
        assertEquals("required_value_unresolved", d.blockingReason());
        // 실행할 수 없는 값이 정규화 칸에 남아 있으면 역검증이 그것을 요구값으로 읽는다.
        assertNull(d.normalizedValue());
        assertNull(d.source());
    }

    @Test
    void 모델_기본값은_원장에서_규정_기본값과_구별된다() {
        ParameterDecision model = AnswerDecisions.fromAnswer("sim::days#1", "sim::days",
                null, 7, SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.MODEL_DEFAULT,
                USER, RULE, T);
        ParameterDecision regulation = AnswerDecisions.fromAnswer("sim::seeds#1", "sim::seeds",
                null, 3, SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.REGULATION,
                USER, RULE, T);

        assertEquals(DecisionState.DEFAULTED, model.state());
        assertEquals(DecisionState.DEFAULTED, regulation.state());
        // 상태가 같으므로 표시를 담을 자리는 출처 종류뿐이다.
        assertEquals("model_default", model.source().type());
        assertEquals("user_explicit", regulation.source().type());
        assertEquals("ST-01", model.source().reference());
    }
}
