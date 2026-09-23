package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이미 있는 선언을 다시 만들지 않고 읽는가. {@link BasisKind}가 "묻지 않고 채울 수
 * 있는가"를 이미 판정하고 있으므로, 결정기록은 그 판정을 <b>뒤집지 않는다</b>.
 */
class DecisionStateMapperTest {

    @Test
    void 사용자가_직접_넣으면_확정이다() {
        assertEquals(DecisionState.CONFIRMED,
                DecisionStateMapper.map(AnswerSourceKind.USER_DIRECT, BasisKind.NONE));
    }

    @Test
    void LLM이_정규화했으면_유도다() {
        assertEquals(DecisionState.DERIVED,
                DecisionStateMapper.map(AnswerSourceKind.LLM_NORMALIZED, BasisKind.NONE));
    }

    @Test
    void 서버가_모델_기본값으로_채우면_기본값이다() {
        assertEquals(DecisionState.DEFAULTED,
                DecisionStateMapper.map(AnswerSourceKind.SERVER_DEFAULT,
                        BasisKind.MODEL_DEFAULT));
    }

    @Test
    void 규정과_측정도_채울_수_있으므로_기본값이다() {
        assertEquals(DecisionState.DEFAULTED,
                DecisionStateMapper.map(AnswerSourceKind.SERVER_DEFAULT,
                        BasisKind.REGULATION));
        assertEquals(DecisionState.DEFAULTED,
                DecisionStateMapper.map(AnswerSourceKind.SERVER_DEFAULT,
                        BasisKind.MEASURED));
    }

    @Test
    void 근거가_없으면_서버가_채워도_미해결이다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(AnswerSourceKind.SERVER_DEFAULT, BasisKind.NONE));
    }

    @Test
    void 실험_목적은_서버가_채울_수_있는_성질이_아니다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(AnswerSourceKind.SERVER_DEFAULT,
                        BasisKind.EXPERIMENT_INTENT));
    }

    @Test
    void 답이_없으면_미해결이다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(null, BasisKind.MODEL_DEFAULT));
    }

    @Test
    void 선언이_없으면_미해결이다() {
        assertEquals(DecisionState.UNRESOLVED,
                DecisionStateMapper.map(AnswerSourceKind.SERVER_DEFAULT, null));
    }
}
