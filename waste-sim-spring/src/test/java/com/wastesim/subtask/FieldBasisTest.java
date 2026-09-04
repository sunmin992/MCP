package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 필드별 근거 선언.
 *
 * <p>"근거 유무로 가른다"를 판단이 아니라 데이터로 만든다. 지금 근거는 코드 주석에 흩어져
 * 있어서 기계가 읽을 수 없다 — {@code wasteMeanKg=0.9}가 논문에서 왔다는 사실이 공통 주석에만
 * 있고 {@code days}·{@code seeds}는 아무 표시도 없다.
 *
 * <p>이 테스트가 지키는 것은 <b>선언이 실제 상태를 말하는가</b>다. 확인하지 않은 출처를
 * {@code PAPER}로 적으면 이 설계가 막으려는 실수를 설계 자체가 저지르는 것이 된다.
 */
class FieldBasisTest {

    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    /** 자동 채움 가능 여부는 두 종류만 거짓이다. */
    @Test
    void onlyTwoKindsBlockAutomaticFilling() {
        assertTrue(BasisKind.PAPER.canFillWithoutAsking());
        assertTrue(BasisKind.REGULATION.canFillWithoutAsking());
        assertTrue(BasisKind.MEASURED.canFillWithoutAsking());
        assertTrue(BasisKind.UNVERIFIED.canFillWithoutAsking());
        assertFalse(BasisKind.NONE.canFillWithoutAsking(),
                "근거 없는 값을 기본값으로 채우면 조용한 가정이 된다");
        assertFalse(BasisKind.EXPERIMENT_INTENT.canFillWithoutAsking(),
                "실험 목적은 사용자가 정해야 한다");
    }

    /** 경고가 필요한 것은 UNVERIFIED뿐이다 — 경고를 남발하면 읽히지 않는다. */
    @Test
    void onlyUnverifiedNeedsAWarning() {
        assertTrue(BasisKind.UNVERIFIED.needsUnverifiedWarning());
        for (BasisKind k : BasisKind.values()) {
            if (k != BasisKind.UNVERIFIED) {
                assertFalse(k.needsUnverifiedWarning(), k + "에 출처 미확인 경고를 붙이면 안 된다");
            }
        }
    }

    /**
     * v4의 34문항 전부에 선언이 있어야 한다 — 빠진 필드는 조용히 처리된다.
     *
     * <p>34인 이유: v3의 33문항에 {@code zoneAssignmentRule} 질문 하나를 더했다. 그
     * 필드는 v3부터 {@code SimulationConfig}에 있었지만 물어볼 질문이 없어서, 5동 이상에
     * {@code ZONE_PROXY_HYBRID}를 쓰려는 사용자가 답할 방법이 없었다.
     */
    @Test
    void everyV4SubtaskDeclaresItsBasis() {
        List<String> missing = v4().subtasks().stream()
                .filter(s -> s.basis() == null)
                .map(JangnyangSubtask::answerField)
                .collect(Collectors.toList());
        assertEquals(List.of(), missing, "근거 선언이 없는 필드");
        assertEquals(34, v4().subtasks().size());
    }

    /**
     * 근거 없는 필드는 정확히 셋이다. 2026-09-02에 하루 종일 경고 표시를 붙인 값들이며,
     * 하나가 늘거나 줄면 이 서술을 다시 세워야 한다.
     */
    @Test
    void exactlyThreeFieldsHaveNoBasis() {
        List<String> none = v4().subtasks().stream()
                .filter(s -> s.basis().kind() == BasisKind.NONE)
                .map(JangnyangSubtask::answerField)
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of("intraZoneTravelMinutes", "serviceMinutesPerSite",
                        "zoneAssignmentRule"),
                none);
    }

    /**
     * 채울 수 있다고 선언한 필드는 기본값을 함께 내야 한다 — 없으면 채울 것이 없다.
     *
     * <p>단, "해당없음"을 정식 답으로 받는 필드({@code allowsNotApplicable})는 예외다.
     * 그런 필드에서 {@code value: null}은 값이 빠진 게 아니라 "해당없음"이라는 답 자체다 —
     * {@code routeSequence}가 비어 있으면 자동 생성 순서를 쓰고, {@code collectionTimes}가
     * 없으면 기준 시각 1회로 돈다. 여기에 억지로 빈 목록·0 같은 대체값을 채우면 없는 값이
     * 있는 것처럼 보이게 되는데, 그게 이 설계가 막으려는 실패 형태다.
     */
    @Test
    void fillableFieldsCarryAValue() {
        List<String> broken = v4().subtasks().stream()
                .filter(s -> s.basis().kind().canFillWithoutAsking())
                .filter(s -> !s.allowsNotApplicable())
                .filter(s -> s.basis().value() == null)
                .map(JangnyangSubtask::answerField)
                .collect(Collectors.toList());
        assertEquals(List.of(), broken, "자동 채움이라면서 기본값이 없는 필드");
    }

    /** 출처를 주장하는 필드는 출처 문자열을 함께 내야 한다. */
    @Test
    void citedFieldsCarryTheirSource() {
        List<String> broken = v4().subtasks().stream()
                .filter(s -> s.basis().kind() == BasisKind.PAPER
                        || s.basis().kind() == BasisKind.REGULATION
                        || s.basis().kind() == BasisKind.MEASURED)
                .filter(s -> s.basis().source() == null || s.basis().source().isBlank())
                .map(JangnyangSubtask::answerField)
                .collect(Collectors.toList());
        assertEquals(List.of(), broken, "출처를 주장하면서 출처 문자열이 없는 필드");
    }

    /** 근거 없다고 선언한 필드는 그 이유를 적어야 한다. */
    @Test
    void unbasedFieldsExplainWhy() {
        v4().subtasks().stream()
                .filter(s -> s.basis().kind() == BasisKind.NONE)
                .forEach(s -> assertFalse(s.basis().why() == null || s.basis().why().isBlank(),
                        s.answerField() + "에 근거 없는 이유가 적혀 있지 않다"));
    }

    /** v3는 손대지 않는다 — immutable 세트의 내용이 바뀌면 세트 해시가 무의미해진다. */
    @Test
    void v3IsUntouched() {
        JangnyangSubtaskDefinition v3 = new JangnyangSubtaskCatalog().byVersion(3);
        assertEquals(33, v3.subtasks().size());
        assertTrue(v3.subtasks().stream().allMatch(s -> s.basis() == null),
                "v3에 basis가 생기면 immutable 세트를 수정한 것이다");
    }
}
