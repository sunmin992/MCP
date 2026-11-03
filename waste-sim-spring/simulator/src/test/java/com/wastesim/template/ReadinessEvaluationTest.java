package com.wastesim.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.readiness.PurposeCheck;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Small labelled regression comparison, not a claim of general research accuracy. */
class ReadinessEvaluationTest {
    record Case(String name, String actualKey, List<Integer> values, List<String> observations,
                Set<String> supported, boolean expected) {}
    @Test void completeInputAloneCannotDetectFourPurposeMismatches() throws Exception {
        var purpose = new ObjectMapper().readTree("""
                {"mode":"COMPARE","comparisonKey":"truckCount","comparisonValues":[1,3],"observations":["meanComplaints"]}
                """);
        var cases=List.of(
                new Case("일치", "truckCount", List.of(1,3), List.of("meanComplaints"), Set.of("meanComplaints"), true),
                new Case("변수 불일치", "days", List.of(1,3), List.of("meanComplaints"), Set.of("meanComplaints"), false),
                new Case("비교 값 불일치", "truckCount", List.of(1,2), List.of("meanComplaints"), Set.of("meanComplaints"), false),
                new Case("목적 지표 누락", "truckCount", List.of(1,3), List.of(), Set.of("meanComplaints"), false),
                new Case("측정 불가 지표", "truckCount", List.of(1,3), List.of("meanComplaints"), Set.of(), false));
        int presenceFalseCompletions=0, verifiedFalseCompletions=0;
        for (Case c:cases) {
            boolean presenceOnly=!c.actualKey().isBlank() && !c.values().isEmpty();
            boolean verified=PurposeCheck.evaluate(purpose,c.actualKey(),c.values(),c.observations(),c.supported()).acceptable();
            if (presenceOnly && !c.expected()) presenceFalseCompletions++;
            if (verified && !c.expected()) verifiedFalseCompletions++;
            assertEquals(c.expected(),verified,c.name());
        }
        assertEquals(4,presenceFalseCompletions);
        assertEquals(0,verifiedFalseCompletions);
    }
}
