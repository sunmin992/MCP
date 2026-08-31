package com.wastesim.subtask;

import java.util.List;
import java.util.Map;

/**
 * 클라이언트로 나가는 진행 상태 뷰(FR-128, SDD 2.18.3).
 *
 * <p>세션 객체를 그대로 내보내지 않고 뷰를 따로 두는 이유는 두 가지다. 세션은 가변이고
 * (동시 요청이 들어오면 직렬화 중에 바뀔 수 있다), 세션이 들고 있는 것 중에는 클라이언트가
 * 알 필요 없는 것(조립된 SimulationConfig 전체 등)이 섞여 있다.
 *
 * @param subtaskSetId     진행 중인 세트 ID
 * @param version          진행 중인 세트 버전 — 이 값이 답변 제출 시 대조된다(FR-138)
 * @param hash             세트 무결성 해시(NFR-20)
 * @param state            현재 상태
 * @param currentSubtaskId 지금 답해야 할 서브태스크. 다 채웠으면 {@code null}
 * @param order            현재 서브태스크의 순서(1부터). 다 채웠으면 {@code total}
 * @param total            <b>이번 실험에 실제로 물을</b> 서브태스크 개수 —
 *                         세트 전체 개수가 아니다(FR-130: 필요하지 않은 것은 묻지 않는다)
 * @param progress         0.0~1.0
 * @param answers          누적된 답변(서브태스크 ID → 구조화 값)
 * @param errors           직전 검증에서 남은 오류 항목
 */
public record SubtaskProgress(
        String subtaskSetId,
        int version,
        String hash,
        SubtaskState state,
        String currentSubtaskId,
        int order,
        int total,
        double progress,
        /** 현재 단계 번호(1~8) — 사용자에게 "3/8"로 보이는 앞 숫자. */
        int groupOrder,
        /** 전체 단계 수 — "3/8"의 뒷 숫자. */
        int groupTotal,
        /** 현재 단계 이름. 사용자에게는 이것이 보이고 ST 번호는 보이지 않는다. */
        String groupName,
        /** 현재 단계가 무엇을 입력하는 단계인지 한 줄 설명. */
        String groupDescription,
        /** 이 단계 안에서 몇 번째 질문인가(1부터) — "질문 2"의 숫자. */
        int questionInGroup,
        /** 이 단계의 질문 수. */
        int questionsInGroup,
        Map<String, Object> answers,
        List<SubtaskError> errors) {

    public SubtaskProgress {
        answers = Ordered.copyOf(answers);
        errors = List.copyOf(errors);
    }
}
