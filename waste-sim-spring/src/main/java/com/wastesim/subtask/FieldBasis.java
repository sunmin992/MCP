package com.wastesim.subtask;

/**
 * 필드 하나의 근거 선언. 서브태스크 세트 JSON에 담기고 세트 해시가 덮는다.
 *
 * @param kind   근거의 종류
 * @param value  묻지 않고 채울 값. {@code NONE}·{@code EXPERIMENT_INTENT}면 없다
 * @param source 출처 문자열. {@code REGULATION}·{@code MEASURED}에 필수 —
 *               밖에서 찾아가 대조할 수 있는 곳을 가리킨다
 * @param why    근거가 없는 이유. {@code NONE}에 필수 — 다음 사람이 왜 막혔는지 알아야 한다
 */
public record FieldBasis(BasisKind kind, Object value, String source, String why) {

    /**
     * 선언이 없는 필드. <b>{@code MODEL_DEFAULT}가 아니라 {@code NONE}</b>이다.
     *
     * <p>선언을 빠뜨린 것을 "모델 기본값"으로 보면, 잊어버린 필드가 조용히 채워진다.
     * 선언이 없다는 것은 근거를 모른다는 뜻이므로 묻는 쪽이 맞다.
     */
    public static FieldBasis unknown() {
        return new FieldBasis(BasisKind.NONE, null, null, "근거 선언이 없다");
    }
}
