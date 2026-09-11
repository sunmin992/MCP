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
        assertEquals(List.of("5톤 차량", "2.5톤 차량", "1톤 차량"),
                truckType.allowedRange().valuesOrEmpty(),
                "허용값은 선언이 아니라 트리의 자식 이름에서 채워져야 한다");
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
