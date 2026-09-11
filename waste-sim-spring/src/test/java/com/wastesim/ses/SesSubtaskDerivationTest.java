package com.wastesim.ses;

import com.wastesim.subtask.AnswerType;
import com.wastesim.subtask.JangnyangCompletenessChecker;
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
        // 허용값의 "구성원과 순서"는 트리의 자식(5톤·2.5톤·1톤 차량, 이 순서)이 정하지만,
        // 최종 표기는 v4가 이미 쓰는 코드값이다 — Task 7 측정 1에서 v4가 한글 자식 이름이
        // 아니라 영문 코드로 답을 받는다는 것이 드러나, SesFieldMapping의 optionCodes가
        // 트리의 자식 이름을 코드값으로만 옮긴다(유도본-v4-대조.md truckType 항목,
        // Ruling 3). truckType은 트리 순서와 v4 순서가 우연히 같아서 이 단언만으로는
        // "표기가 트리 순서를 따른다"와 "표기가 v4 순서를 따른다"를 구분하지 못한다 —
        // 그 구분은 아래 specChoiceOptionOrderFollowsTreeNotV4가 travelTimeMode로 한다.
        assertEquals(List.of("LARGE_5TON", "MEDIUM_2P5T", "SMALL_1TON"),
                truckType.allowedRange().valuesOrEmpty(),
                "허용값의 구성원·순서는 트리가 정하고, 표기는 optionCodes가 v4의 코드값으로 옮긴다");
    }

    /**
     * Ruling 7 회귀 테스트 — {@code optionCodes}는 이름만 옮기는 사전이지 순서를 정하지
     * 않는다는 것을 travelTimeMode로 확인한다. 트리의 자식 순서는 [구간 상수, 교통구역
     * 근사, 실제 도로 기반]인데 v4의 값 순서는 뒤 두 개가 바뀐 [LEGACY_CONSTANT,
     * OSRM_HYBRID, ZONE_PROXY_HYBRID]다(유도본-v4-대조.md travelTimeMode 항목). 만약
     * {@code optionValues}가 v4 순서나 {@code optionCodes}의 선언 순서를 따랐다면 이
     * 단언은 v4와 같은 순서를 기대했을 것이다 — 실제로는 <b>트리 순서를 그대로 코드로
     * 옮긴</b> [LEGACY_CONSTANT, ZONE_PROXY_HYBRID, OSRM_HYBRID]가 나와야 트리가 순서를
     * 정한다는 주장이 참임을 코드로 확인한 것이다.
     */
    @Test
    void specChoiceOptionOrderFollowsTreeNotV4() {
        SubtaskSkeleton travelTimeMode = SesSubtaskDerivation.deriveSkeletons().stream()
                .filter(s -> s.answerField().equals("travelTimeMode")).findFirst().orElseThrow();
        assertEquals(List.of("LEGACY_CONSTANT", "ZONE_PROXY_HYBRID", "OSRM_HYBRID"),
                travelTimeMode.allowedRange().valuesOrEmpty(),
                "순서는 optionCodes의 선언 순서나 v4의 순서가 아니라 트리의 자식 순회 순서를 따라야 한다");
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

    /**
     * I2 — required가 실제 조립 필요와 어긋나면 외부 MCP 클라이언트가 거짓 "complete"를
     * 받는다. {@code JangnyangCompletenessChecker.check}는 required 플래그를 보지 않고
     * {@code def.collectSubtasks()} <b>전부</b>에 답을 요구하므로, 이 검사기가 실제로
     * 요구하는 항목(=조립에 필요한 항목)은 required=true여야 {@code
     * JangnyangSubtaskValidator}가 내는 "complete"와 뜻이 같아진다.
     */
    @Test
    void everyCollectFieldTheCheckerNeedsIsMarkedRequired() {
        JangnyangSubtaskDefinition def = SesSubtaskDerivation.derive(ProseCatalog.load());
        JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
        List<JangnyangSubtask> neededForAssembly = checker.relevantSubtasks(def, null);

        for (JangnyangSubtask s : neededForAssembly) {
            assertTrue(s.required(),
                    s.answerField() + "는 체크커가 실제로 답을 요구하는데 required=false다 — "
                            + "외부 클라이언트가 9개만 답해도 complete=true로 오판한다(I2)");
        }
        // v5는 전부 required=true다(v4도 34개 전부 그렇다) — 결과적으로 이 세트에는
        // "필요 없는데 required로 표시된" 항목도, "필요한데 required가 아닌" 항목도 없다.
        assertTrue(def.subtasks().stream().allMatch(JangnyangSubtask::required));
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
