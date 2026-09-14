package com.wastesim.ledger.wiring;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.registry.SimulationConfigFields;
import com.wastesim.ses.SesFieldMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배선이 대응표에서 유도되는가, 그리고 <b>유도되지 않는 자리가 선언돼 있는가</b>.
 *
 * <p>역검증이 보지 않는 필드를 "빠뜨림"이 아니라 "선언된 제외"로 두는 것이 이 클래스의
 * 요점이다. 둘이 코드에서 같아 보이면 다음 사람은 역검증이 전부를 본다고 믿는다.
 */
class JangnyangLedgerWiringTest {

    private static Set<String> allAnswerFields() {
        return SesFieldMapping.bindings().stream()
                .map(SesFieldMapping.FieldBinding::answerField)
                .collect(Collectors.toSet());
    }

    @Test
    void 매개변수_ID는_자산_ID와_답변_필드로_만든다() {
        assertEquals("jangnyang-simulator::days",
                JangnyangLedgerWiring.parameterIdOf("days"));
    }

    @Test
    void 대응표의_모든_바인딩이_유도되거나_제외_선언돼_있다() {
        Set<String> verified = JangnyangLedgerWiring.fieldToParameterId().keySet();
        Set<String> excluded = JangnyangLedgerWiring.TRANSFORMED_FIELDS.keySet();

        List<String> unclassified = allAnswerFields().stream()
                .filter(f -> !verified.contains(f) && !excluded.contains(f))
                .sorted().toList();

        assertEquals(List.of(), unclassified,
                "대응표에 늘었는데 배선에서 분류되지 않은 필드가 있다: " + unclassified);
    }

    @Test
    void 제외_선언에는_반드시_이유가_적혀_있다() {
        for (Map.Entry<String, String> e : JangnyangLedgerWiring.TRANSFORMED_FIELDS.entrySet()) {
            assertNotNull(e.getValue(), e.getKey());
            assertFalse(e.getValue().isBlank(),
                    e.getKey() + ": 왜 대조할 수 없는지 적지 않으면 빠뜨린 것과 같아 보인다");
        }
    }

    @Test
    void 유도된_필드는_전부_SimulationConfig에_실재한다() {
        Set<String> real = SimulationConfigFields.all();
        List<String> missing = JangnyangLedgerWiring.fieldToParameterId().keySet().stream()
                .filter(f -> !real.contains(f)).sorted().toList();

        assertEquals(List.of(), missing,
                "역검증이 읽을 수 없는 필드를 매핑했다: " + missing);
    }

    @Test
    void 변환되는_필드는_역검증_맵에_들어가지_않는다() {
        for (String transformed : JangnyangLedgerWiring.TRANSFORMED_FIELDS.keySet()) {
            assertFalse(JangnyangLedgerWiring.fieldToParameterId().containsKey(transformed),
                    transformed + "은 값을 대조할 수 없는데 역검증 맵에 들어 있다");
        }
    }

    @Test
    void 교통_프로필은_교통_활성_규칙에_묶인다() {
        assertEquals(JangnyangRules.TRAFFIC_APPLY,
                JangnyangLedgerWiring.activeWhenByParameter()
                        .get(JangnyangLedgerWiring.parameterIdOf("trafficProfileId")));
    }

    @Test
    void 교통_모드가_바뀌면_프로필이_종속으로_따라온다() {
        assertEquals(List.of(JangnyangLedgerWiring.parameterIdOf("trafficProfileId")),
                JangnyangLedgerWiring.dependents()
                        .get(JangnyangLedgerWiring.parameterIdOf(JangnyangRules.TRAFFIC_MODE_FIELD)));
    }
}
