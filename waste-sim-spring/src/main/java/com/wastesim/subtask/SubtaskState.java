package com.wastesim.subtask;

import java.util.Set;

/**
 * 수집 세션의 상태(FR-129, SDD 2.18.3).
 *
 * <pre>
 * NOT_STARTED
 *    │ 생성 요청(FR-119)
 *    ▼
 * COLLECTING ──답변 제출──▶ VALIDATING ──오류──▶ COLLECTING (재질문, FR-127)
 *    │                          │
 *    │                          └──전 항목 통과──▶ READY
 *    │                                                │ build_jangnyang_scenario
 *    │                                                ▼
 *    │                                              BUILT ──실행 승인(FR-133)──▶ RUNNING ──▶ COMPLETED
 *    └──취소/초기화──▶ CANCELLED
 * </pre>
 *
 * <p><b>왜 상태를 열거하고 전이를 강제하는가</b>: 이 프로젝트가 파라미터 검증에서 지켜온
 * 태도(잘못된 값은 연산에 도달하지 못한다)를 시간 축에 적용한 것이다(D-52). 값이 다 맞아도
 * 아직 조립되지 않은 세션이 엔진을 부를 수 있으면, "준비됐는가"의 판단이 호출 순서라는
 * 암묵적 규약에 얹히게 된다 — 호출부가 늘어나면 반드시 어긋난다.
 */
public enum SubtaskState {

    NOT_STARTED,
    COLLECTING,
    VALIDATING,
    READY,
    BUILT,
    RUNNING,
    COMPLETED,
    CANCELLED;

    /**
     * 이 상태에서 {@code next}로 갈 수 있는가. 갈 수 없는 전이 요청은 조용히 무시하지 않고
     * 거부한다 — 무시하면 세션이 옛 상태에 머문 채 호출부만 진행됐다고 믿는다.
     */
    public boolean canTransitionTo(SubtaskState next) {
        if (next == CANCELLED) return this != COMPLETED;   // 언제든 취소할 수 있다(끝난 것 빼고)
        return switch (this) {
            case NOT_STARTED -> next == COLLECTING;
            case COLLECTING  -> next == VALIDATING || next == COLLECTING;
            case VALIDATING  -> next == COLLECTING || next == READY;
            case READY       -> next == BUILT || next == COLLECTING;   // 답을 고치면 다시 수집으로
            case BUILT       -> next == RUNNING || next == COLLECTING;
            case RUNNING     -> next == COMPLETED || next == BUILT;    // 실행 실패면 BUILT로 되돌린다
            case COMPLETED, CANCELLED -> false;
        };
    }

    /** 시나리오 조립(build)을 허용하는 상태인가 — READY 이전의 조립 요청을 막는다(UT-324). */
    public boolean canBuild() {
        return this == READY || this == BUILT;
    }

    /**
     * 엔진 실행을 허용하는 상태인가 — BUILT 없는 RUNNING 요청을 막는다(UT-317).
     * READY만으로는 부족하다: 조립을 거치지 않으면 실행할 설정 자체가 없다.
     */
    public boolean canRun() {
        return this == BUILT;
    }

    /** 아직 수집이 진행 중인 상태들 — 채팅이 "이번 메시지는 답변"으로 볼 상태다. */
    private static final Set<SubtaskState> ACTIVE =
            Set.of(COLLECTING, VALIDATING, READY, BUILT);

    /** 이 세션이 아직 살아 있는가(채팅 게이트가 답변 처리로 가로챌지 판단하는 기준). */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
