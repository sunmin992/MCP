package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변 원장에 출처가 남는가.
 *
 * <p>{@code LLM_NORMALIZED}는 enum에 값만 있고 넣는 곳이 없었다. 검증기가 12곳에서
 * {@code USER_DIRECT}를 하드코딩했기 때문이다. 출처를 구별하지 못하면 나중에 "이 값을
 * 누가 넣었나"를 되짚을 수 없고, LLM이 채운 값과 사람이 답한 값이 섞인다.
 */
class AnswerSourceTest {

    private static SubtaskSessionService service() {
        return TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
    }

    /** LLM이 넣은 값은 원장에 LLM_NORMALIZED로 남아야 한다. */
    @Test
    void llmAnswerIsRecordedAsLlmNormalized() {
        SubtaskSessionService svc = service();
        svc.start("s1");
        JangnyangSubtaskDefinition def = svc.definitionOf(svc.activeSession("s1"));
        String firstId = def.subtasks().get(0).id();

        svc.submit("s1", firstId, "민원 발생량 확인", null, SubtaskAnswerSource.LLM_NORMALIZED);

        JangnyangSubtaskAnswer a = svc.activeSession("s1").answers().get(firstId);
        assertNotNull(a, "답변이 원장에 없다");
        assertEquals(SubtaskAnswerSource.LLM_NORMALIZED, a.source(),
                "LLM이 넣은 값을 사용자 답변과 구별할 수 없으면 출처를 되짚을 수 없다");
    }

    /** 출처를 주지 않은 기존 호출은 USER_DIRECT로 남아야 한다 — 기존 동작 불변. */
    @Test
    void omittingSourceStaysUserDirect() {
        SubtaskSessionService svc = service();
        svc.start("s2");
        JangnyangSubtaskDefinition def = svc.definitionOf(svc.activeSession("s2"));
        String firstId = def.subtasks().get(0).id();

        svc.submit("s2", firstId, "민원 발생량 확인", null);

        assertEquals(SubtaskAnswerSource.USER_DIRECT,
                svc.activeSession("s2").answers().get(firstId).source(),
                "기존 경로의 출처가 바뀌면 이미 쌓인 원장의 의미가 달라진다");
    }
}
