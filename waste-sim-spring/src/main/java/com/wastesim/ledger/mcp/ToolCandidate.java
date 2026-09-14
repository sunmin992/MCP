package com.wastesim.ledger.mcp;

import com.wastesim.ledger.ValueSource;

import java.time.Instant;

/**
 * MCP가 돌려준 값. <b>아직 확정값이 아니다.</b>
 *
 * @param purposeField 이 값을 어디에 쓰기로 하고 불렀는가. 호출 시점에 고정된다
 */
public record ToolCandidate(String purposeField, Object value, String unit, String semanticType,
                            String timeWindow, Instant observedAt, ValueSource source) {

    /**
     * <b>왜 형제 레코드들과 달리 던지지 않는가</b>: 이 레코드의 필드는 하나도 빠짐없이
     * {@link CandidateAdmission}의 판정 대상이고, 그 판정의 규약은 통과하지 못한 값도
     * {@code INVALID}로 <b>결정기록에 기록한다</b>는 것이다. 여기서 먼저 던지면 기록되어야 할
     * 실패가 호출자에게 예외로 터져, 후보값 계층이 존재하는 이유 자체가 무너진다.
     *
     * <p>그래서 이 생성자가 하는 일은 빈 문자열을 {@code null}로 접는 것뿐이다 —
     * "없다"와 "비어 있다"를 두 가지 사실로 두면 판정이 두 갈래로 갈라진다.
     */
    public ToolCandidate {
        purposeField = blankToNull(purposeField);
        unit = blankToNull(unit);
        semanticType = blankToNull(semanticType);
        timeWindow = blankToNull(timeWindow);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
