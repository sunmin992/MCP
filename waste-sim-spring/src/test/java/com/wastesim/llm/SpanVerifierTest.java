package com.wastesim.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM이 지어낸 값을 버린다.
 *
 * <p>값만 내라고 하면 요청에 없는 것도 만들어 낸다. 그래서 값마다 근거가 된 원문 조각을
 * 함께 내게 하고, 그 조각이 실제 요청에 있는지 검사한다. 한 줄짜리 검사인데 이것이
 * "근거 없는 값이 사실처럼 흘러드는" 문제를 막는 자리다.
 */
class SpanVerifierTest {

    private static RequestExtraction ex(ExtractedValue... vs) {
        return new RequestExtraction(List.of(vs), null, null, null);
    }

    /** 요청에 있는 조각을 인용한 값은 채택한다. */
    @Test
    void acceptsValuesQuotingTheRequest() {
        SpanVerifier.Verified v = SpanVerifier.verify("장량동 26개 동으로 돌려줘",
                ex(new ExtractedValue("numBuildings", 26, "26개 동")));
        assertEquals(1, v.accepted().size());
        assertEquals(0, v.rejected().size());
    }

    /** 정규화는 정당하다 — "한 달"에서 30을 뽑는 것은 창작이 아니다. */
    @Test
    void allowsNormalizationWhenTheSpanIsPresent() {
        SpanVerifier.Verified v = SpanVerifier.verify("한 달 돌려줘",
                ex(new ExtractedValue("days", 30, "한 달")));
        assertEquals(1, v.accepted().size(), "정규화를 막으면 자연어 해석이 불가능해진다");
    }

    /** <b>이 테스트가 이 클래스의 요점이다.</b> 요청에 없는 조각을 댄 값은 버린다. */
    @Test
    void rejectsValuesWhoseSpanIsNotInTheRequest() {
        SpanVerifier.Verified v = SpanVerifier.verify("장량동 시뮬레이터 만들어 줘",
                ex(new ExtractedValue("seeds", 10, "10회 반복")));
        assertEquals(0, v.accepted().size(), "요청에 없는 근거를 댄 값을 받으면 안 된다");
        assertEquals(1, v.rejected().size());
        assertEquals("seeds", v.rejected().get(0).field());
    }

    /** 조각이 비어 있거나 없는 값도 버린다 — 근거를 대지 않은 것이다. */
    @Test
    void rejectsValuesWithNoSpanAtAll() {
        SpanVerifier.Verified v = SpanVerifier.verify("장량동 시뮬레이터",
                ex(new ExtractedValue("days", 30, null),
                   new ExtractedValue("seeds", 10, "   ")));
        assertEquals(0, v.accepted().size());
        assertEquals(2, v.rejected().size());
    }

    /** 공백 차이는 용인한다 — LLM이 조각을 옮길 때 공백이 흔히 달라진다. */
    @Test
    void toleratesWhitespaceDifferences() {
        SpanVerifier.Verified v = SpanVerifier.verify("26개  동으로",
                ex(new ExtractedValue("numBuildings", 26, "26개 동")));
        assertEquals(1, v.accepted().size(),
                "공백 하나로 정당한 인용을 버리면 쓸 수 없는 검사가 된다");
    }

    /** 대소문자 차이도 용인한다. */
    @Test
    void toleratesCaseDifferences() {
        SpanVerifier.Verified v = SpanVerifier.verify("ROUND_ROBIN으로 배정해",
                ex(new ExtractedValue("zoneAssignmentRule", "ROUND_ROBIN", "round_robin")));
        assertEquals(1, v.accepted().size());
    }

    /** 요청이 비어 있으면 아무 값도 채택할 수 없다. */
    @Test
    void emptyRequestAcceptsNothing() {
        SpanVerifier.Verified v = SpanVerifier.verify("",
                ex(new ExtractedValue("days", 30, "한 달")));
        assertEquals(0, v.accepted().size());
    }
}
