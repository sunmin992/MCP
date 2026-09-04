package com.wastesim.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 뽑은 값이 <b>요청에 실제로 근거하는가</b>를 검사한다.
 *
 * <p>LLM에게 값만 내라고 하면 요청에 없는 것도 만들어 낸다. 그래서 값마다 근거가 된 원문
 * 조각을 함께 내게 하고, 그 조각이 요청 문자열에 있는지 본다. 한 줄짜리 검사이지만
 * "근거 없는 값이 사실처럼 흘러드는" 문제를 막는 자리다.
 *
 * <p><b>정규화는 막지 않는다.</b> "한 달"에서 30을 뽑는 것은 창작이 아니라 해석이며, 조각
 * ("한 달")이 요청에 있으므로 통과한다. 걸리는 것은 조각 자체가 없는 경우다.
 *
 * <p>공백과 대소문자 차이는 용인한다 — LLM이 조각을 옮길 때 흔히 달라지고, 그것으로 정당한
 * 인용을 버리면 쓸 수 없는 검사가 된다.
 */
public final class SpanVerifier {

    private SpanVerifier() {}

    /**
     * @param accepted 인용이 확인된 값. 이것만 세션에 제출한다
     * @param rejected 인용을 확인하지 못한 값. 되묻기 대상으로 내린다
     */
    public record Verified(List<ExtractedValue> accepted, List<ExtractedValue> rejected) {
        public Verified {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }
    }

    public static Verified verify(String request, RequestExtraction extraction) {
        List<ExtractedValue> accepted = new ArrayList<>();
        List<ExtractedValue> rejected = new ArrayList<>();
        String haystack = normalize(request);

        for (ExtractedValue v : extraction.values()) {
            String needle = normalize(v.span());
            if (needle.isEmpty() || haystack.isEmpty() || !haystack.contains(needle)) {
                rejected.add(v);
            } else {
                accepted.add(v);
            }
        }
        return new Verified(accepted, rejected);
    }

    /** 공백을 없애고 소문자로 맞춘다. {@code null}은 빈 문자열로 본다. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
