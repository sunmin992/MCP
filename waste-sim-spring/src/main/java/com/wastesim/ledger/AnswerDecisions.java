package com.wastesim.ledger;

import java.time.Instant;
import java.util.List;

/**
 * 답변 하나를 결정기록 레코드 하나로 만든다.
 *
 * <p><b>왜 이 자리가 필요한가</b>: {@link DecisionStateMapper}는 상태만 돌려주고,
 * {@link ParameterDecision}은 그 상태에 따라 열세 개 인자 중 무엇이 필수이고 무엇이
 * 비어 있어야 하는지를 생성자에서 강제한다. 그 사이를 잇는 조립이 없으면 호출부마다
 * 같은 조립을 다시 쓰게 되고 — 실제로 통합 시험이 자기 사본을 들고 있었다 — 사본은
 * 본체가 바뀌어도 컴파일이 통과하므로 시험이 실제 경로가 아닌 것을 시험하게 된다.
 */
public final class AnswerDecisions {

    private AnswerDecisions() { }

    /**
     * @param answerSource     이번 답변이 어디서 왔는가
     * @param basis            이 필드가 무엇에 근거할 수 있는가(선언)
     * @param source           실행 가능한 상태일 때 쓸 출처. 모델 기본값이면 종류가 바뀐다
     * @param transformation   {@code LLM_NORMALIZED}로 유도한 값이면 필수
     */
    public static ParameterDecision fromAnswer(String decisionId, String parameterId,
                                               Object rawValue, Object normalizedValue,
                                               AnswerSourceKind answerSource, BasisKind basis,
                                               ValueSource source, Transformation transformation,
                                               Instant recordedAt) {
        DecisionState state = DecisionStateMapper.map(answerSource, basis);
        boolean executable = state.executable();

        return new ParameterDecision(
                decisionId, parameterId, state,
                rawValue, null,
                // 실행할 수 없는 상태에 정규화 값을 남겨 두면 역검증이 그것을 "결정기록이 요구하는
                // 값"으로 읽는다. 값이 실행에 쓰일 수 없다는 사실과 값이 비어 있다는 사실을
                // 어긋나게 두지 않는다.
                executable ? normalizedValue : null, null,
                executable ? noticed(source, basis) : null,
                state == DecisionState.DERIVED ? transformation : null,
                List.of(),
                executable ? null : BlockingReasons.REQUIRED_VALUE_UNRESOLVED,
                null, recordedAt);
    }

    /**
     * 모델 기본값 표시를 출처 종류에 새긴다.
     *
     * <p>{@link BasisKind#needsModelDefaultNotice()}가 이미 "이 값은 밖에서 대조할 곳이
     * 없다"를 판정해 두었는데 결정기록이 그 판정을 옮겨 적지 않으면, 규정 근거로 채운 값과
     * 모델이 정한 값이 결정기록에서 구별되지 않는다. 둘 다 {@code DEFAULTED}이기 때문이다.
     */
    private static ValueSource noticed(ValueSource source, BasisKind basis) {
        if (source == null || basis == null || !basis.needsModelDefaultNotice()) return source;
        return new ValueSource(ValueSource.MODEL_DEFAULT, source.reference(),
                source.version(), source.acquiredAt());
    }
}
