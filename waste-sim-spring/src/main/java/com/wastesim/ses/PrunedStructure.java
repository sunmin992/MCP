package com.wastesim.ses;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 가지치기된 트리 — <b>이 사용자의 요청이 SES의 어느 가지를 골랐는가</b>.
 *
 * <p>{@code SimulationConfig}는 숫자 뭉치라 "원한 대로 구성됐는가"를 보기 어렵다. 이쪽은
 * 고른 가지가 그대로 보인다. 미리보기 화면과 가설 판정이 둘 다 이것을 읽는다.
 *
 * <p><b>Ruling 2 — attributeValues의 키는 pointId가 아니라 answerField다.</b>
 * {@code collectionTime}(단일 시각)과 {@code collectionTimes}(시각 목록)은 서로 다른
 * 답변 필드이면서 같은 SES 지점({@code attr:수거차량:수거시각})을 가리킨다. pointId를
 * 키로 쓰면 나중에 처리된 필드가 먼저 것을 덮어써 사용자가 답한 값 하나가 조용히
 * 사라진다 — 기존 {@code toConfig()}가 두 필드를 각각 다른 세터로 보내는 것과도
 * 어긋난다. 그래서 여기서는 answerField를 키로 쓰고, 그 필드가 SES의 어느 자리였는지는
 * {@link #pointOfField}로 따로 들고 다닌다 — 값을 잃지 않으면서 SES와의 연결도
 * 잃지 않는다.
 */
public record PrunedStructure(Map<String, String> chosenSpecs,
                              Map<String, Integer> counts,
                              Map<String, Object> attributeValues,
                              Map<String, String> pointOfField,
                              Set<String> deadCouplings) {

    public PrunedStructure {
        chosenSpecs = new LinkedHashMap<>(chosenSpecs);
        counts = new LinkedHashMap<>(counts);
        attributeValues = new LinkedHashMap<>(attributeValues);
        pointOfField = new LinkedHashMap<>(pointOfField);
        deadCouplings = new LinkedHashSet<>(deadCouplings);
    }

    public String chosen(String pointId) {
        return chosenSpecs.get(pointId);
    }

    public int count(String pointId) {
        Integer n = counts.get(pointId);
        if (n == null) throw new IllegalStateException("복제 수가 정해지지 않았다: " + pointId);
        return n;
    }

    /** answerField로 찾는다 — pointId로는 collectionTime/collectionTimes를 가려낼 수 없다. */
    public Object value(String answerField) {
        return attributeValues.get(answerField);
    }

    /** 이 답변 필드가 SES의 어느 지점이었는지 — 미리보기가 "이 값이 SES의 어느 자리인가"를 되찾는 통로다. */
    public String pointOf(String answerField) {
        return pointOfField.get(answerField);
    }

    /** 조건이 없는 결합은 언제나 살아 있다 — 죽은 목록에 없으면 산 것이다. */
    public boolean isLive(String couplingId) {
        return !deadCouplings.contains(couplingId);
    }
}
