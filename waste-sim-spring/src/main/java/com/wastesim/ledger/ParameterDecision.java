package com.wastesim.ledger;

import java.time.Instant;
import java.util.List;

/**
 * 매개변수 하나에 대한 결정 한 건. 원장에 쌓이는 단위다.
 *
 * <p><b>왜 불변식을 생성자에서 막는가</b>: 원장은 append-only라 한 번 들어간 레코드를
 * 고칠 수 없다. 들어간 뒤에 검사하면 고칠 방법이 없는 것을 발견하게 되므로, 들어가기
 * 전에 막는 자리가 여기뿐이다.
 *
 * @param decisionId       원장 안에서 유일한 ID. {@code supersededBy}가 이것을 가리킨다
 * @param parameterId      {@code <asset-id>::<input-field>}
 * @param rawValue         정규화 전 값. 사용자가 "7일"이라 답했으면 그 문자열
 * @param normalizedValue  정규화 후 값. 실행 설정에 들어갈 값
 * @param source           실행 허용 상태({@link DecisionState#executable()})면 필수
 * @param transformation   {@link DecisionState#DERIVED}면 필수
 * @param blockingReason   실행 불가 상태면 필수 — 왜 막혔는지 다음 사람이 알아야 한다
 * @param supersededBy     {@link DecisionState#STALE}이면 필수. 이 결정을 낡게 만든
 *                         트리거나 대체 결정의 참조
 */
public record ParameterDecision(
        String decisionId,
        String parameterId,
        DecisionState state,
        Object rawValue,
        String rawUnit,
        Object normalizedValue,
        String normalizedUnit,
        ValueSource source,
        Transformation transformation,
        List<String> evidenceRefs,
        String blockingReason,
        String supersededBy,
        Instant recordedAt) {

    public ParameterDecision {
        if (parameterId == null || parameterId.isBlank()) {
            throw new IllegalArgumentException("매개변수 ID가 없습니다.");
        }
        if (state == null) {
            throw new IllegalArgumentException("결정 상태가 없습니다.");
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);

        if (state == DecisionState.DERIVED && transformation == null) {
            throw new IllegalArgumentException(
                    parameterId + ": 유도한 값인데 변환 규칙이 없습니다.");
        }
        if (state.executable() && source == null) {
            throw new IllegalArgumentException(
                    parameterId + ": 실행에 쓸 값인데 출처가 없습니다.");
        }
        if (!state.executable() && (blockingReason == null || blockingReason.isBlank())) {
            throw new IllegalArgumentException(
                    parameterId + ": 실행할 수 없는 상태인데 차단 사유가 없습니다.");
        }
        if (state == DecisionState.STALE && (supersededBy == null || supersededBy.isBlank())) {
            throw new IllegalArgumentException(
                    parameterId + ": 낡았다고 표시했는데 무엇 때문인지 없습니다.");
        }
    }
}
