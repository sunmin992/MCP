package com.wastesim.ledger.mcp;

import com.wastesim.ledger.BlockingReasons;
import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ParameterId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 후보값을 원장에 올릴지 판정한다.
 *
 * <p>검사를 통과하지 못한 값도 원장에 <b>들어간다</b> — {@link DecisionState#INVALID}로.
 * 버리면 "물어봤는데 왜 값이 없는가"의 답이 사라지고, 다음 사람이 같은 도구를 다시 부른다.
 */
public final class CandidateAdmission {

    public ParameterDecision admit(ToolCandidate candidate, ParameterExpectation expectation,
                                   String decisionId, Instant now) {
        String rejection = reject(candidate, expectation, now);
        if (rejection != null) {
            return new ParameterDecision(decisionId, expectation.parameterId(),
                    DecisionState.INVALID, candidate.value(), candidate.unit(),
                    null, null, null, null, List.of(), rejection, null, now);
        }
        return new ParameterDecision(decisionId, expectation.parameterId(),
                DecisionState.CONFIRMED, candidate.value(), candidate.unit(),
                candidate.value(), expectation.unit(), candidate.source(),
                null, List.of(), null, null, now);
    }

    /**
     * 타임아웃. <b>재요청하지 않는다</b> — 중복 실행 방지가 보증되지 않는 이상 재시도는
     * 같은 작업을 두 번 시키는 것일 수 있다.
     */
    public ParameterDecision onTimeout(ParameterExpectation expectation,
                                       String decisionId, Instant now) {
        return new ParameterDecision(decisionId, expectation.parameterId(),
                DecisionState.UNRESOLVED, null, null, null, null, null, null,
                List.of(), BlockingReasons.TOOL_TIMEOUT, null, now);
    }

    /** 막을 이유. 없으면 {@code null}. */
    private String reject(ToolCandidate c, ParameterExpectation e, Instant now) {
        if (c.purposeField() == null || c.purposeField().isBlank()) {
            return "쓸 곳이 없습니다: purposeField가 비어 있습니다 → " + e.parameterId();
        }
        // 접미사 일치(endsWith)는 "::" 경계를 문자열 끝에서만 확인하므로, purposeField가
        // 조각을 하나 이상 담고 있으면(예: "depotA::x") 앞부분(자산)만 다른 parameterId도
        // "뒤가 같다"는 이유로 통과시킬 수 있다. 그래서 규약을 아는 자리(ParameterId)에
        // 물어 마지막 조각만 정확히 비교한다 — 다른 자산의 값이 조용히 잘못된 자리에
        // 들어가는 일을 막는다.
        if (!ParameterId.fieldOf(e.parameterId()).equals(c.purposeField())) {
            return "쓸 곳이 다릅니다: " + c.purposeField() + " → " + e.parameterId();
        }
        if (!e.semanticType().equals(c.semanticType())) {
            return "의미 타입이 다릅니다: " + c.semanticType() + " ≠ " + e.semanticType();
        }
        // 단위가 맞아도 타입이 다르면 역검증의 equals 대조에서 같은 값이 다른 값으로 읽힌다
        // (Double 7.0 ≠ Integer 7). 여기서 수를 조용히 바꿔 맞추지 않는 이유는, 그렇게 하면
        // 역검증이 드러내려던 변환 오류를 조달 단계가 먼저 덮어 버리기 때문이다.
        if (!e.valueType().isInstance(c.value())) {
            return "값의 실행 타입이 다릅니다: "
                    + (c.value() == null ? "null" : c.value().getClass().getSimpleName())
                    + " ≠ " + e.valueType().getSimpleName();
        }
        if (!e.unit().equals(c.unit())) {
            return "단위가 다릅니다: " + c.unit() + " ≠ " + e.unit();
        }
        if (!e.timeWindow().equals(c.timeWindow())) {
            return "시간창이 다릅니다: " + c.timeWindow() + " ≠ " + e.timeWindow();
        }
        if (c.observedAt() == null) {
            return "최신성을 만족하지 않습니다: " + c.observedAt();
        }
        if (c.observedAt().isAfter(now)) {
            return "최신성을 만족하지 않습니다: 관측 시각이 미래입니다 → " + c.observedAt();
        }
        if (Duration.between(c.observedAt(), now).compareTo(e.maxAge()) > 0) {
            return "최신성을 만족하지 않습니다: " + c.observedAt();
        }
        // 출처 없는 값은 실행 상태로 올릴 수 없다(ParameterDecision의 불변식). 그 불변식에
        // 그냥 맡기면 생성자가 호출자에게 예외를 던져, "검사에 걸린 값도 INVALID로 기록한다"는
        // 이 클래스의 규약이 깨진다. 그래서 같은 사실을 여기서 먼저 거절 사유로 만든다.
        if (c.source() == null || c.source().type() == null || c.source().type().isBlank()) {
            return "출처가 없습니다: " + e.parameterId();
        }
        return null;
    }
}
