package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답변 결정기록에 출처가 남는가.
 *
 * <p>{@code LLM_NORMALIZED}는 enum에 값만 있고 넣는 곳이 없었다. 검증기가 12곳에서
 * {@code USER_DIRECT}를 하드코딩했기 때문이다. 출처를 구별하지 못하면 나중에 "이 값을
 * 누가 넣었나"를 되짚을 수 없고, LLM이 채운 값과 사람이 답한 값이 섞인다.
 */
class AnswerSourceTest {

    private static SubtaskSessionService service() {
        return TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
    }

    /**
     * "첫 번째 서브태스크"가 아니라 {@code simulationGoal}을 고른다 — 이 테스트가 보는
     * 것은 출처 기록이지 어떤 필드가 순서상 첫 번째냐가 아니다. v5에서는 순서상 첫
     * 서브태스크가 ENUM(scenarioType)이라 자유 문장을 답하면 검증에서 거부돼 결정기록에
     * 아예 안 남는다 — 자유 문장을 허용하는 STRING 필드로 고정해야 이 테스트가 세트
     * 버전이 바뀔 때마다 함께 깨지지 않는다.
     */
    private static String freeTextField(JangnyangSubtaskDefinition def) {
        JangnyangSubtask st = def.byAnswerField("simulationGoal");
        return (st != null ? st : def.subtasks().get(0)).id();
    }

    /** LLM이 넣은 값은 결정기록에 LLM_NORMALIZED로 남아야 한다. */
    @Test
    void llmAnswerIsRecordedAsLlmNormalized() {
        SubtaskSessionService svc = service();
        svc.start("s1");
        JangnyangSubtaskDefinition def = svc.definitionOf(svc.activeSession("s1"));
        String firstId = freeTextField(def);

        svc.submit("s1", firstId, "민원 발생량 확인", null, SubtaskAnswerSource.LLM_NORMALIZED);

        JangnyangSubtaskAnswer a = svc.activeSession("s1").answers().get(firstId);
        assertNotNull(a, "답변이 결정기록에 없다");
        assertEquals(SubtaskAnswerSource.LLM_NORMALIZED, a.source(),
                "LLM이 넣은 값을 사용자 답변과 구별할 수 없으면 출처를 되짚을 수 없다");
    }

    /** 출처를 주지 않은 기존 호출은 USER_DIRECT로 남아야 한다 — 기존 동작 불변. */
    @Test
    void omittingSourceStaysUserDirect() {
        SubtaskSessionService svc = service();
        svc.start("s2");
        JangnyangSubtaskDefinition def = svc.definitionOf(svc.activeSession("s2"));
        String firstId = freeTextField(def);

        svc.submit("s2", firstId, "민원 발생량 확인", null);

        assertEquals(SubtaskAnswerSource.USER_DIRECT,
                svc.activeSession("s2").answers().get(firstId).source(),
                "기존 경로의 출처가 바뀌면 이미 쌓인 결정기록의 의미가 달라진다");
    }
}
