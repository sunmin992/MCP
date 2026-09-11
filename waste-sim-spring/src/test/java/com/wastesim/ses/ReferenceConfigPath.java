package com.wastesim.ses;

import com.wastesim.model.SimulationConfig;
import com.wastesim.subtask.JangnyangCompletenessChecker;
import com.wastesim.subtask.JangnyangScenarioBuilder;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskAnswer;
import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.SubtaskAnswerSource;
import com.wastesim.subtask.TestSubtaskFixtures;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기존 경로({@code JangnyangScenarioBuilder})로 설정을 만든다 — 차등 테스트의 기준.
 *
 * <p>제품 코드의 가시성을 넓히지 않으려고 테스트 쪽에 둔다. {@code toConfig()}는 private이고
 * {@code JangnyangSubtaskAnswer} 맵을 받는다 — 여기서 답변 필드명 맵을 그 맵으로 바꿔
 * {@link JangnyangScenarioBuilder#build}에 넘기고, 돌아온 {@code BuildOutcome}에서
 * {@link SimulationConfig}를 꺼낸다.
 *
 * <p><b>검증기를 거치지 않는다.</b> {@code JangnyangSubtaskValidator}는 자연어 원문을
 * 구조화 값으로 정규화하는 계층이라, 답변이 이미 구조화 값(코드값·분 단위 정수 등)으로
 * 온다면 그 단계를 다시 거칠 이유가 없다 — {@code SesPruner}·{@code PesFlattener}도 같은
 * 전제로 동작한다(Ruling 3). 그래서 여기서는 {@link JangnyangSubtaskAnswer#accepted}로
 * 답을 직접 감싼다.
 */
final class ReferenceConfigPath {

    private ReferenceConfigPath() { }

    static SimulationConfig build(Map<String, Object> answersByField) {
        JangnyangSubtaskDefinition def = new JangnyangSubtaskCatalog().byVersion(4);
        JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
        JangnyangScenarioBuilder builder = TestSubtaskFixtures.builder(checker);

        Map<String, JangnyangSubtaskAnswer> answers = new LinkedHashMap<>();
        for (JangnyangSubtask st : def.collectSubtasks()) {
            if (!answersByField.containsKey(st.answerField())) {
                throw new IllegalStateException("차등 테스트 답변 조합이 v4의 필수 항목을 빠뜨렸다: "
                        + st.answerField() + " (" + st.id() + ")");
            }
            Object value = answersByField.get(st.answerField());
            answers.put(st.id(), JangnyangSubtaskAnswer.accepted(
                    st.id(), String.valueOf(value), value, SubtaskAnswerSource.USER_DIRECT));
        }

        JangnyangScenarioBuilder.BuildOutcome outcome = builder.build(def, answers);
        if (!outcome.ok()) {
            throw new IllegalStateException("기존 경로가 이 답변 조합으로 조립되지 않았다 — missing="
                    + outcome.missing() + ", configErrors=" + outcome.configErrors());
        }
        return outcome.spec().toSimulationConfig();
    }
}
