package com.wastesim.subtask;

/**
 * 서브태스크 하나에 대한 답변(SDD 2.18.2).
 *
 * <p><b>원문을 계속 들고 다니는 이유</b>: 구조화 값만 남기면 "사용자가 08:30이라고
 * 답했는가, 아니면 '아침 여덟시 반쯤'을 LLM이 08:30으로 바꾼 것인가"를 사후에 구분할 수
 * 없다. NFR-20(구성 과정의 감사성)은 결과에서 조건을 되짚을 수 있어야 한다고 요구하는데,
 * 정규화가 잘못됐을 때 원문이 없으면 어디서 틀렸는지 알 수 없다.
 *
 * @param subtaskId 이 답변이 속한 서브태스크 ID
 * @param raw       사용자가 실제로 친 문장
 * @param value     검증을 통과한 구조화 값(TIME은 분 단위 Integer, 나머지는 자료형 그대로).
 *                  검증 실패면 {@code null}이다 — 실패한 값을 저장하면 다음 조립에서
 *                  그 값이 쓰인다
 * @param source    이 값이 어디서 왔는가
 * @param error     검증 실패 사유. 통과했으면 {@code null}
 */
public record JangnyangSubtaskAnswer(
        String subtaskId,
        String raw,
        Object value,
        SubtaskAnswerSource source,
        SubtaskError error) {

    public static JangnyangSubtaskAnswer accepted(String subtaskId, String raw, Object value,
                                                  SubtaskAnswerSource source) {
        return new JangnyangSubtaskAnswer(subtaskId, raw, value, source, null);
    }

    public static JangnyangSubtaskAnswer rejected(String subtaskId, String raw,
                                                  SubtaskAnswerSource source, SubtaskError error) {
        return new JangnyangSubtaskAnswer(subtaskId, raw, null, source, error);
    }

    public boolean valid() {
        return error == null;
    }
}
