package com.wastesim.ses;

import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 측정 1 — 문항이 SES에서 나오는가.
 *
 * <p>완전히 같을 것으로 보지 않는다. 어긋나는 자리가 나오면 그것이 발견이고, 이 테스트가
 * 하는 일은 그것을 <b>눈에 보이게 만드는 것</b>이다. 그래서 실패 메시지에 목록을 다 찍는다.
 */
class DerivedSetVsV4ReportTest {

    /** v4를 가리키는 실제 조회 메서드 이름은 카탈로그를 열어 맞췄다 — byVersion(int)가 실재한다. */
    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    @Test
    void derivedFieldsMatchV4Fields() {
        Set<String> derived = new TreeSet<>(SesSubtaskDerivation.derive(ProseCatalog.load())
                .subtasks().stream().map(JangnyangSubtask::answerField).toList());
        Set<String> existing = new TreeSet<>(v4().subtasks().stream()
                .map(JangnyangSubtask::answerField).toList());

        Set<String> onlyDerived = new TreeSet<>(derived);
        onlyDerived.removeAll(existing);
        Set<String> onlyV4 = new TreeSet<>(existing);
        onlyV4.removeAll(derived);

        assertEquals(Set.of(), onlyDerived, "SES에 자리가 있는데 v4가 묻지 않던 것: " + onlyDerived);
        assertEquals(Set.of(), onlyV4, "v4가 묻는데 SES에 자리가 없는 것: " + onlyV4);
    }

    @Test
    void derivedAnswerTypesMatchV4() {
        Map<String, JangnyangSubtask> existing = v4().subtasks().stream()
                .collect(Collectors.toMap(JangnyangSubtask::answerField, Function.identity()));

        List<String> mismatches = SesSubtaskDerivation.derive(ProseCatalog.load()).subtasks().stream()
                .filter(s -> existing.containsKey(s.answerField()))
                .filter(s -> s.answerType() != existing.get(s.answerField()).answerType())
                .map(s -> s.answerField() + ": 유도=" + s.answerType()
                        + " v4=" + existing.get(s.answerField()).answerType())
                .sorted().toList();

        assertEquals(List.of(), mismatches, "자료형이 어긋난 문항: " + mismatches);
    }

    @Test
    void specChoiceOptionsMatchV4Values() {
        Map<String, JangnyangSubtask> existing = v4().subtasks().stream()
                .collect(Collectors.toMap(JangnyangSubtask::answerField, Function.identity()));

        List<String> mismatches = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.pointId().startsWith("spec:"))
                .filter(s -> existing.containsKey(s.answerField()))
                .filter(s -> !s.allowedRange().valuesOrEmpty()
                        .equals(existing.get(s.answerField()).allowedRange().valuesOrEmpty()))
                .map(s -> s.answerField() + ": 트리=" + s.allowedRange().valuesOrEmpty()
                        + " v4=" + existing.get(s.answerField()).allowedRange().valuesOrEmpty())
                .sorted().toList();

        assertEquals(List.of(), mismatches,
                "트리의 자식 이름과 v4의 허용값이 다르다 — 가설이 가장 강하게 걸린 자리다: " + mismatches);
    }
}
