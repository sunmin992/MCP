package com.wastesim.ses;

import com.wastesim.subtask.AnswerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 34문항이 29 + 1 + 4로 닫히는가. 닫히지 않으면 SES에 자리가 없는 것을 묻고 있거나,
 * 자리가 있는데 묻지 않고 있다는 뜻이다 — 둘 다 보고 대상이다.
 */
class SesFieldMappingTest {

    @Test
    void everyBindingPointsAtARealDecisionPoint() {
        Set<String> pointIds = DecisionPointExtractor.extract(JangnyangEntityStructure.get())
                .stream().map(DecisionPoint::id).collect(Collectors.toSet());
        List<String> dangling = SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::pointId)
                .filter(id -> !pointIds.contains(id))
                .sorted().toList();
        assertEquals(List.of(), dangling, "SES에 없는 지점을 가리키는 대응이 있다: " + dangling);
    }

    @Test
    void thirtyFourFieldsCloseAsTwentyNinePlusOnePlusFour() {
        long seated = SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::answerField).distinct().count();
        long outsideSes = SesFieldMapping.nonSesFields().stream()
                .filter(f -> f.reason() == SesFieldMapping.NonSesField.Reason.OUTSIDE_SES).count();
        long procedure = SesFieldMapping.nonSesFields().stream()
                .filter(f -> f.reason() == SesFieldMapping.NonSesField.Reason.PROCEDURE_CONTROL).count();

        assertEquals(29, seated, "SES에 자리를 가진 필드");
        assertEquals(1, outsideSes, "SES 밖 결정 — engine");
        assertEquals(4, procedure, "절차 제어");
        assertEquals(34, seated + outsideSes + procedure);
    }

    @Test
    void specChoiceOptionsAreNotDeclaredHere() {
        SesFieldMapping.FieldBinding truckType = SesFieldMapping.bindings().stream()
                .filter(b -> b.answerField().equals("truckType")).findFirst().orElseThrow();
        assertEquals(AnswerType.ENUM, truckType.answerType());
        assertNull(truckType.range().values(),
                "spec 축의 허용값은 선언하지 않는다 — 트리의 자식 이름이 곧 허용값이다");
    }

    @Test
    void noFieldIsDeclaredTwice() {
        List<String> fields = SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::answerField).toList();
        Set<String> nonSes = new TreeSet<>(SesFieldMapping.nonSesFields().stream()
                .map(SesFieldMapping.NonSesField::answerField).toList());
        assertEquals(fields.size(), Set.copyOf(fields).size(), "같은 필드를 두 번 묶었다");
        assertTrue(fields.stream().noneMatch(nonSes::contains), "SES 안팎에 동시에 적힌 필드가 있다");
    }
}
