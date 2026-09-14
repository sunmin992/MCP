package com.wastesim.ledger;

import java.time.Instant;
import java.util.List;

/**
 * 매개변수 하나에 대한 결정 한 건. 결정기록에 쌓이는 단위다.
 *
 * <p><b>왜 불변식을 생성자에서 막는가</b>: 결정기록은 append-only라 한 번 들어간 레코드를
 * 고칠 수 없다. 들어간 뒤에 검사하면 고칠 방법이 없는 것을 발견하게 되므로, 들어가기
 * 전에 막는 자리가 여기뿐이다.
 *
 * @param decisionId       결정기록 안에서 유일한 ID. {@code supersededBy}가 이것을 가리킨다
 * @param parameterId      {@code <asset-id>::<input-field>} — 규약은 {@link ParameterId}가 갖는다
 * @param rawValue         정규화 전 값. 사용자가 "7일"이라 답했으면 그 문자열
 * @param normalizedValue  정규화 후 값. 실행 설정에 들어갈 값.
 *                         <b>여기 들어오는 값은 이미 실행 설정 필드가 기대하는 런타임 타입이어야
 *                         한다</b> — 7일이 {@code int} 필드로 간다면 {@code Integer 7}이지
 *                         {@code Double 7.0}이 아니다. 역검증은 {@code equals}로 대조하므로
 *                         타입이 갈라지면 같은 값이 다른 값으로 읽힌다. 대조 지점에서 조용히
 *                         숫자를 맞춰 주지 않는 이유는, 그 어긋남이야말로 역검증이 드러내려고
 *                         존재하는 변환 오류이기 때문이다. 타입 계약은
 *                         {@code ParameterExpectation.valueType}이 조달 시점에 지킨다
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
        // 결정기록의 "현재"는 쌓인 순서의 마지막이고, 순서를 사람이 읽을 수 있게 만드는 것은
        // 이 시각뿐이다. 비어 있으면 이력이 남아도 언제 무엇이 앞섰는지 말할 수 없다.
        if (recordedAt == null) {
            throw new IllegalArgumentException(
                    parameterId + ": 결정을 언제 쌓았는지가 없습니다.");
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
