package com.wastesim.subtask;

/**
 * 수집 세션 보관소(SDD 2.18.3·2.18.11).
 *
 * <p><b>왜 인터페이스인가</b>: v1.13 초판의 구현은 in-memory이고, 그것으로는
 * NFR-19(진행 상태의 지속성)를 만족할 수 없다 — 서버를 재시작하면 진행 중인 수집이
 * 사라진다. 이 사실을 문서가 먼저 인정했고(D-51), 코드도 같은 태도를 취한다: 저장소를
 * 인터페이스로 갈라 두면 "지금은 미충족"이 구현 세부가 아니라 <b>교체 가능한 한 지점</b>이
 * 되고, {@link #durable()}이 그 상태를 호출부와 테스트에 드러낸다.
 *
 * <p>{@code durable()}을 두는 이유는 "언젠가 고치자"는 주석이 아니라 <b>지금 확인 가능한
 * 사실</b>로 남기기 위해서다. 운영 저장소가 붙으면 이 값이 true가 되고, 그때 UT-319의
 * 복구 검증이 스킵에서 실검증으로 자동 전환된다.
 */
public interface SubtaskSessionStore {

    /** 세션 키로 조회. 없으면 {@code null}. */
    JangnyangSubtaskSession find(String sessionKey);

    /** 저장(덮어쓰기). */
    void save(JangnyangSubtaskSession session);

    /** 제거 — 취소·초기화 시 호출. */
    void remove(String sessionKey);

    /** 전부 제거 — 채팅 초기화({@code /app/chat.clear})에 대응. */
    void clear();

    /**
     * 프로세스 재시작을 넘어 세션이 살아남는가.
     *
     * <p>false면 NFR-19 <b>미충족</b>이며, 그것이 결함이 아니라 의도적으로 미룬 범위라는
     * 사실을 호출부·테스트가 이 값으로 확인한다(D-51).
     */
    boolean durable();
}
