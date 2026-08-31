package com.wastesim.subtask;

/**
 * 사용자가 주지 않아 서버가 채운 값 하나와 그 근거(FR-131, D-53).
 *
 * <p>값을 조용히 채우고 성공 응답만 내면 사용자는 자기 실험의 조건을 모른 채 결과를
 * 읽는다. 무엇을 어떤 근거로 채웠는지 남겨 미리보기와 최종 결과에 함께 싣는다 —
 * D-26("조용히 보정하지 않는다")을 수집 계층으로 확장한 것이다.
 *
 * @param field  채운 필드(서브태스크의 {@code answerField} 또는 설정 필드명)
 * @param value  채운 값
 * @param reason 왜 이 값인가 — "기본값"이라고만 적으면 근거가 아니라 사실의 반복이다
 */
public record AppliedDefault(String field, Object value, String reason) {
    @Override
    public String toString() {
        return field + "=" + value + " (" + reason + ")";
    }
}
