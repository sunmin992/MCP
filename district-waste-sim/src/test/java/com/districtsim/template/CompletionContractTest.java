package com.districtsim.template;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CompletionContractTest {
    final TemplateCatalog catalog = new TemplateCatalog();
    final SubtaskPlanner planner = new SubtaskPlanner(catalog);
    SubtaskTemplate integer() {
        return catalog.all().stream().filter(t -> t.valueType().equals("INTEGER")
                && t.generateWhen() == GenerateCondition.ALWAYS && t.min() != null).findFirst().orElseThrow();
    }
    @Test void invalidValueCannotFillAnInputOrASweep() {
        var t = integer();
        int invalid = t.min().intValue() - 1;
        var p = planner.plan(Map.of(t.answerKey(), invalid));
        assertEquals(SubtaskStatus.UNFILLED, p.instances().stream()
                .filter(i -> i.answerKey().equals(t.answerKey())).findFirst().orElseThrow().status());
        var sweep = planner.planAcross(Map.of(), Map.of(), t.answerKey(), List.of(t.min().intValue(), invalid));
        assertEquals(SubtaskStatus.UNFILLED, sweep.plan().instances().stream()
                .filter(i -> i.answerKey().equals(t.answerKey())).findFirst().orElseThrow().status());
        assertFalse(sweep.plan().complete());
    }
    @Test void absentAndNullValuesCannotFillInputs() {
        var t = integer();
        Map<String, Object> a = new java.util.HashMap<>();
        a.put(t.answerKey(), null);
        assertEquals(SubtaskStatus.UNFILLED, planner.plan(a).instances().stream()
                .filter(i -> i.answerKey().equals(t.answerKey())).findFirst().orElseThrow().status());
    }
}
