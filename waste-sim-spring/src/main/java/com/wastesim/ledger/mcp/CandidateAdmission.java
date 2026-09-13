package com.wastesim.ledger.mcp;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;

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
                List.of(), "tool_timeout", null, now);
    }

    /** 막을 이유. 없으면 {@code null}. */
    private String reject(ToolCandidate c, ParameterExpectation e, Instant now) {
        if (!e.parameterId().endsWith("::" + c.purposeField())) {
            return "쓸 곳이 다릅니다: " + c.purposeField() + " → " + e.parameterId();
        }
        if (!e.semanticType().equals(c.semanticType())) {
            return "의미 타입이 다릅니다: " + c.semanticType() + " ≠ " + e.semanticType();
        }
        if (!e.unit().equals(c.unit())) {
            return "단위가 다릅니다: " + c.unit() + " ≠ " + e.unit();
        }
        if (!e.timeWindow().equals(c.timeWindow())) {
            return "시간창이 다릅니다: " + c.timeWindow() + " ≠ " + e.timeWindow();
        }
        if (c.observedAt() == null
                || Duration.between(c.observedAt(), now).compareTo(e.maxAge()) > 0) {
            return "최신성을 만족하지 않습니다: " + c.observedAt();
        }
        return null;
    }
}
