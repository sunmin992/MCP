package com.wastesim.ledger.mcp;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 결과가 후보값으로만 들어오는가.
 *
 * <p>도구의 출력 스키마는 선택 사항이고 멱등성 표시는 보증이 아니라 힌트다. 그러므로
 * 의미·단위·시간창·최신성 검사는 프로토콜이 주는 것이 아니라 <b>이 자리가 해야 하는 일</b>이다.
 */
class CandidateAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private static final ParameterExpectation EXPECT = new ParameterExpectation(
            "sim::routeTravelMinutes", "travel_time", "minute", "daily_average",
            Duration.ofDays(30));

    private static ToolCandidate candidate(String unit, String semanticType,
                                           String timeWindow, Instant observedAt) {
        return new ToolCandidate("routeTravelMinutes", 12.5, unit, semanticType, timeWindow,
                observedAt, new ValueSource("mcp_result", "tmap#call-1", "v1", observedAt));
    }

    @Test
    void 전부_맞으면_확정된다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "travel_time", "daily_average", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.CONFIRMED, d.state());
        assertEquals(12.5, d.normalizedValue());
    }

    @Test
    void 단위가_다르면_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("second", "travel_time", "daily_average", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("단위"), d.blockingReason());
    }

    @Test
    void 단위가_같아도_시간창이_다르면_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "travel_time", "peak_hour", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("시간창"), d.blockingReason());
    }

    @Test
    void 의미_타입이_다르면_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "distance", "daily_average", NOW),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("의미"), d.blockingReason());
    }

    @Test
    void 너무_오래된_값은_확정하지_않는다() {
        ParameterDecision d = new CandidateAdmission().admit(
                candidate("minute", "travel_time", "daily_average", NOW.minus(Duration.ofDays(60))),
                EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("최신성"), d.blockingReason());
    }

    @Test
    void 쓸_곳이_다른_값은_받지_않는다() {
        ToolCandidate wrongPurpose = new ToolCandidate("intraZoneTravelMinutes", 12.5,
                "minute", "travel_time", "daily_average", NOW,
                new ValueSource("mcp_result", "tmap#call-1", "v1", NOW));

        ParameterDecision d = new CandidateAdmission()
                .admit(wrongPurpose, EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.INVALID, d.state());
        assertTrue(d.blockingReason().contains("쓸 곳"), d.blockingReason());
    }

    @Test
    void 타임아웃은_재요청하지_않고_미해결로_돌린다() {
        ParameterDecision d = new CandidateAdmission()
                .onTimeout(EXPECT, "sim::routeTravelMinutes#1", NOW);

        assertEquals(DecisionState.UNRESOLVED, d.state());
        assertEquals("tool_timeout", d.blockingReason());
    }
}
