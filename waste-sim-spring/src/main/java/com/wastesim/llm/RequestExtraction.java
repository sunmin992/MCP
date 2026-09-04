package com.wastesim.llm;

import java.util.List;

/**
 * 요청 하나에서 뽑아낸 전부.
 *
 * <p>설계도 값({@code values})과 <b>판정용 필드</b>가 갈라져 있다. 판정용 필드는 설계도에
 * 값으로 들어가지 않고 {@link FeasibilityGate}만 본다 — 거부 판정에 필요한 것이 33개 답변
 * 필드에 없기 때문이다.
 *
 * @param targetRegion        요청이 가리키는 지역. 비어 있으면 장량동으로 본다 —
 *                            "시뮬레이터 만들어 줘"처럼 지역을 생략한 요청이 정상이므로,
 *                            침묵을 거부 근거로 쓰지 않는다
 * @param targetDomain        요청의 도메인. 비어 있으면 생활쓰레기 수거로 본다
 * @param requestedConclusion 요청이 원하는 결론. 비어 있으면 판정하지 않고 통과시킨다
 */
public record RequestExtraction(List<ExtractedValue> values,
                                String targetRegion,
                                String targetDomain,
                                String requestedConclusion) {
    public RequestExtraction {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
