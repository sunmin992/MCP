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

    /**
     * spec 축의 <b>구성원</b>이 v4와 같은가 — 표기(이름 vs 코드)는 {@code optionCodes}가
     * 옮기므로 여기서는 집합으로 비교한다. <b>순서</b>는 이 테스트가 보지 않는다 — 순서는
     * 별도로 {@link #specChoiceOptionOrderMismatchesAgainstV4AreOnlyTheKnownOnes}가 다룬다
     * (Ruling 7). 구성원과 순서를 한 단언에 묶으면 순서만 다른 경우와 값 자체가 다른
     * 경우를 구분할 수 없다 — 실패 메시지는 여전히 양쪽 목록을 다 찍어서 어느 경우인지
     * 사람이 바로 판단할 수 있게 한다.
     */
    @Test
    void specChoiceOptionsMatchV4Values() {
        Map<String, JangnyangSubtask> existing = v4().subtasks().stream()
                .collect(Collectors.toMap(JangnyangSubtask::answerField, Function.identity()));

        List<String> mismatches = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.pointId().startsWith("spec:"))
                .filter(s -> existing.containsKey(s.answerField()))
                .filter(s -> !Set.copyOf(s.allowedRange().valuesOrEmpty())
                        .equals(Set.copyOf(existing.get(s.answerField()).allowedRange().valuesOrEmpty())))
                .map(s -> s.answerField() + ": 트리=" + s.allowedRange().valuesOrEmpty()
                        + " v4=" + existing.get(s.answerField()).allowedRange().valuesOrEmpty())
                .sorted().toList();

        assertEquals(List.of(), mismatches,
                "트리의 자식 이름과 v4의 허용값이 다르다 — 가설이 가장 강하게 걸린 자리다: " + mismatches);
    }

    /**
     * spec 축의 <b>순서</b>가 v4와 같은가 — 트리의 자식 순회 순서를 그대로 코드로 옮긴
     * 목록({@link SesSubtaskDerivation#deriveSkeletons})과 v4의 순서를 비교한다.
     *
     * <p>순서 차이 자체로 빌드를 막지는 않는다 — 트리도 v4도 이 태스크에서 고칠 수 없는
     * 기준선이고(전역 제약), travelTimeMode는 실제로 트리와 v4가 순서에 대해 불일치한다는
     * 것이 측정 결과이기 때문이다(유도본-v4-대조.md travelTimeMode 항목). 대신 "지금
     * 알려진 불일치가 정확히 이것뿐이다"를 고정해서, 새 불일치가 조용히 늘거나 알던
     * 불일치가 조용히 사라지면(트리 순서가 바뀌었거나 v4가 갱신됐거나 대응이 잘못 고쳐졌거나)
     * 이 테스트가 잡는다.
     */
    @Test
    void specChoiceOptionOrderMismatchesAgainstV4AreOnlyTheKnownOnes() {
        Map<String, JangnyangSubtask> existing = v4().subtasks().stream()
                .collect(Collectors.toMap(JangnyangSubtask::answerField, Function.identity()));

        List<String> orderMismatches = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.pointId().startsWith("spec:"))
                .filter(s -> existing.containsKey(s.answerField()))
                .filter(s -> !s.allowedRange().valuesOrEmpty()
                        .equals(existing.get(s.answerField()).allowedRange().valuesOrEmpty()))
                .map(s -> s.answerField())
                .sorted().toList();

        assertEquals(List.of("travelTimeMode"), orderMismatches,
                "트리 순서와 v4 순서가 다른 spec 필드 목록이 바뀌었다 — 새 필드가 늘었으면 "
                        + "원인을 확인하고 유도본-v4-대조.md에 반영해야 하고, 사라졌으면 트리나 "
                        + "v4 중 하나가 바뀐 것이다: " + orderMismatches);
    }
}
