package com.wastesim.ledger;

/**
 * {@code <asset-id>::<input-field>} 규약을 가진 자리.
 *
 * <p><b>왜 헬퍼로 빼는가</b>: 이 규약은 원장 레코드의 javadoc, 후보값 판정, 그리고 모든
 * 호출부에서 각자 다시 유도되고 있었다. 규약이 여러 곳에 복제되면 한 곳만 고쳐도
 * 컴파일이 통과하고, 통과한 뒤에 다른 자산의 값이 조용히 잘못된 자리에 들어간다.
 */
public final class ParameterId {

    /** 자산과 입력 필드를 가르는 경계. */
    public static final String SEPARATOR = "::";

    private ParameterId() { }

    /** 자산 ID와 입력 필드명으로 매개변수 ID를 만든다. */
    public static String of(String assetId, String fieldName) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("자산 ID가 없습니다.");
        }
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("입력 필드명이 없습니다.");
        }
        return assetId + SEPARATOR + fieldName;
    }

    /**
     * 입력 필드명. 경계가 없으면 전체가 필드명이다.
     *
     * <p>조각이 셋 이상인 값이 들어와도 <b>마지막</b> 경계 뒤만 본다 — 앞부분은 어디까지나
     * 자산 쪽이고, 필드는 언제나 맨 뒤 한 조각이기 때문이다.
     */
    public static String fieldOf(String parameterId) {
        requireId(parameterId);
        int last = parameterId.lastIndexOf(SEPARATOR);
        return last < 0 ? parameterId : parameterId.substring(last + SEPARATOR.length());
    }

    /** 자산 ID. 경계가 없으면 {@code null} — 자산을 지어내지 않는다. */
    public static String assetOf(String parameterId) {
        requireId(parameterId);
        int last = parameterId.lastIndexOf(SEPARATOR);
        return last < 0 ? null : parameterId.substring(0, last);
    }

    private static void requireId(String parameterId) {
        if (parameterId == null || parameterId.isBlank()) {
            throw new IllegalArgumentException("매개변수 ID가 없습니다.");
        }
    }
}
