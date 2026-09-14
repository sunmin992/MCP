package com.wastesim.subtask;

/**
 * 세션 테스트가 매번 조립하던 것을 한자리로 모은다. 서비스 조립과 "필드명으로 서브태스크
 * ID 찾기"는 세트 버전이 바뀌면 같이 바뀌는데, 테스트마다 복사해 두면 한 곳만 고치고
 * 나머지가 조용히 낡는다.
 *
 * <p>서비스 조립 자체는 {@link TestSubtaskFixtures}에 이미 있는 실제 조립을 그대로 물린다 —
 * 여기서 새로 만들면 "검증기·조립기는 언제나 진짜를 쓴다"는 의도가 두 곳으로 갈라진다.
 */
final class SubtaskTestSupport {

    private SubtaskTestSupport() { }

    static SubtaskSessionService service() {
        return TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
    }

    /** 답변 필드명으로 이 세션의 서브태스크 ID를 찾는다. 없으면 {@code null}. */
    static String idOfField(SubtaskSessionService sessions, String sessionKey, String field) {
        JangnyangSubtaskSession session = sessions.activeSession(sessionKey);
        JangnyangSubtaskDefinition def = sessions.definitionOf(session);
        for (JangnyangSubtask s : def.ordered()) {
            if (field.equals(s.answerField())) return s.id();
        }
        return null;
    }
}
