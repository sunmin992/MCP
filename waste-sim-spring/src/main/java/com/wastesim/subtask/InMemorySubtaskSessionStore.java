package com.wastesim.subtask;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.13 초판의 세션 보관소 — 프로세스 메모리(SDD 2.18.11, D-51).
 *
 * <p>{@link #durable()}이 {@code false}인 것이 이 클래스의 요점이다. 서버를 재시작하면
 * 진행 중인 수집이 사라지므로 NFR-19는 <b>미충족</b>이며, 그 사실을 감추지 않는다.
 * 운영 단계에서 세션·답변·진행 상태·시나리오·결과 식별자를 외부 저장소(RDB 또는 Redis)로
 * 옮기면 이 클래스만 교체된다 — 호출부는 {@link SubtaskSessionStore}만 알고 있다.
 *
 * <p>{@code ConcurrentHashMap}을 쓰는 이유는 세션 <b>키가 여럿</b>이기 때문이다(D-49).
 * 단일 default 세션 시절의 전역 락 하나로는 서로 다른 사용자의 진행이 불필요하게
 * 직렬화된다.
 */
@Component
public class InMemorySubtaskSessionStore implements SubtaskSessionStore {

    private final Map<String, JangnyangSubtaskSession> sessions = new ConcurrentHashMap<>();

    @Override
    public JangnyangSubtaskSession find(String sessionKey) {
        return sessionKey == null ? null : sessions.get(sessionKey);
    }

    @Override
    public void save(JangnyangSubtaskSession session) {
        sessions.put(session.sessionKey(), session);
    }

    @Override
    public void remove(String sessionKey) {
        if (sessionKey != null) sessions.remove(sessionKey);
    }

    @Override
    public void clear() {
        sessions.clear();
    }

    @Override
    public boolean durable() {
        return false;   // NFR-19 미충족 — 의도적으로 미룬 범위(D-51)
    }
}
