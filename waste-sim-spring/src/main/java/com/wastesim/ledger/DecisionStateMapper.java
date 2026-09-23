package com.wastesim.ledger;

/**
 * 답변 시점의 두 사실을 결정기록의 상태로 옮긴다.
 *
 * <p><b>왜 판정을 다시 만들지 않는가</b>: {@link BasisKind#canFillWithoutAsking()}이
 * "묻지 않고 채울 수 있는가"를 이미 판정하고, 그 판정이 세트 해시가 덮는 자산이다.
 * 여기서 다른 기준을 쓰면 같은 필드에 대해 두 개의 답이 생기고, 갈라졌을 때 어느 쪽이
 * 옳은지 말할 근거가 없다.
 *
 * <p>그래서 이 매퍼는 <b>옮기기만 한다.</b> 판단은 {@code BasisKind}에 있다.
 */
public final class DecisionStateMapper {

    private DecisionStateMapper() { }

    public static DecisionState map(AnswerSourceKind source, BasisKind basis) {
        if (source == null) return DecisionState.UNRESOLVED;

        return switch (source) {
            case USER_DIRECT, USER, EXTERNAL -> DecisionState.CONFIRMED;
            case LLM_NORMALIZED, DERIVED -> DecisionState.DERIVED;
            case MODEL_DEFAULT -> DecisionState.DEFAULTED;
            // 선언이 없으면 근거를 모른다는 뜻이므로 채우지 않는다 —
            // FieldBasis.unknown()이 누락을 NONE으로 보는 것과 같은 이유다.
            case SERVER_DEFAULT -> (basis != null && basis.canFillWithoutAsking())
                    ? DecisionState.DEFAULTED
                    : DecisionState.UNRESOLVED;
        };
    }
}
