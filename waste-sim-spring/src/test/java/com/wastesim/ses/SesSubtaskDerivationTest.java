package com.wastesim.ses;

import com.wastesim.subtask.AnswerType;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SesSubtaskDerivationTest {

    @Test
    void specChoiceOptionsAreFilledFromTheTree() {
        SubtaskSkeleton truckType = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.answerField().equals("truckType")).findFirst().orElseThrow();
        assertEquals(AnswerType.ENUM, truckType.answerType());
        // 허용값의 "개수와 순서"는 트리의 자식(5톤·2.5톤·1톤 차량)이 정하지만, 최종 표기는
        // v4가 이미 쓰는 코드값이다 — Task 7 측정 1에서 v4가 한글 자식 이름이 아니라 영문
        // 코드로 답을 받는다는 것이 드러나, SesFieldMapping의 optionCodes가 트리의 자식
        // 이름을 코드값으로 옮긴다(유도본-v4-대조.md truckType 항목, Ruling 3).
        assertEquals(List.of("LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"),
                truckType.allowedRange().valuesOrEmpty(),
                "허용값의 개수·순서는 트리가 정하고, 표기는 optionCodes가 v4의 코드값으로 옮긴다");
    }

    @Test
    void specAndMultiAreRequired() {
        List<SubtaskSkeleton> skeletons = SesSubtaskDerivation.deriveSkeletons();
        assertTrue(skeletons.stream().filter(s -> s.pointId().startsWith("spec:"))
                .allMatch(SubtaskSkeleton::required), "spec 축이 안 정해지면 트리가 닫히지 않는다");
        assertTrue(skeletons.stream().filter(s -> s.pointId().startsWith("multi:"))
                .allMatch(SubtaskSkeleton::required), "복제 수가 안 정해지면 모델을 만들 수 없다");
    }

    @Test
    void derivedSetIsFullySpecifiedAndComplete() {
        JangnyangSubtaskDefinition def = SesSubtaskDerivation.derive(ProseCatalog.load());
        assertEquals("jangnyang-simulator-v5", def.subtaskSetId());
        assertEquals(5, def.version());
        assertEquals(34, def.subtasks().size(), "SES 29 + 밖 1 + 절차 4");
        for (JangnyangSubtask st : def.subtasks()) {
            assertTrue(st.isFullySpecified(), st.answerField() + " 문항에 빈 항목이 있다");
        }
    }

    @Test
    void ordersAreUniqueAndContiguous() {
        List<Integer> orders = SesSubtaskDerivation.derive(ProseCatalog.load()).subtasks().stream()
                .map(JangnyangSubtask::order).sorted().toList();
        for (int i = 0; i < orders.size(); i++) {
            assertEquals(i + 1, orders.get(i), "order가 1부터 연속이어야 한다");
        }
    }
}
