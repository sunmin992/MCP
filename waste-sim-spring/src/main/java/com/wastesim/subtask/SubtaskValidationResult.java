package com.wastesim.subtask;

import java.util.List;
import java.util.Map;

/**
 * 답변 묶음 하나에 대한 검증 결과(FR-126·128, MCP {@code validate_jangnyang_subtask_answers}의 출력).
 *
 * @param valid    이번에 제출된 답변들이 <b>전부</b> 규칙을 통과했는가.
 *                 아직 안 낸 항목이 있어도 낸 것이 다 맞으면 true다
 * @param missing  아직 유효한 답이 없는 <b>필수</b> 서브태스크 ID 목록
 * @param errors   항목별 거부 사유. 첫 오류에서 멈추지 않고 전부 모은다
 * @param complete 세트 수준 필수 항목이 전부 유효한가(FR-126·130).
 *                 선택 항목이 비어도 true다 — 시나리오별 추가 요구는
 *                 {@link JangnyangCompletenessChecker}가 따로 본다
 * @param accepted 통과한 답변들(서브태스크 ID → 답변). 세션에 누적할 값이다
 */
public record SubtaskValidationResult(
        boolean valid,
        List<String> missing,
        List<SubtaskError> errors,
        boolean complete,
        Map<String, JangnyangSubtaskAnswer> accepted) {

    public SubtaskValidationResult {
        missing = List.copyOf(missing);
        errors = List.copyOf(errors);
        accepted = Ordered.copyOf(accepted);
    }
}
