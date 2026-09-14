package com.wastesim.ses;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 답변으로 트리를 접는다. spec 축은 하나로, multi는 개수로, 속성은 값으로.
 *
 * <p><b>조용히 넘어가지 않는다.</b> spec 축이나 복제 수가 안 정해지면 예외를 던진다 —
 * 그 자리가 비면 모델을 만들 수 없는데, 기본값으로 메우면 사용자가 고르지 않은 가지가
 * 결과에 들어가 있고 아무 데도 그 사실이 남지 않는다.
 *
 * <p><b>Ruling 3 — spec 답변은 코드값으로 들어온다.</b> 트리의 자식 이름은 한글
 * ("5톤 차량")이지만 실제 답변 필드에는 {@code SesFieldMapping.FieldBinding.optionCodes}가
 * 정한 코드값("LARGE_5TON")이 들어온다. 그래서 여기서는 {@code spec.options()}에 답을
 * 직접 대조하지 않고, optionCodes를 코드값 → 트리 자식 이름으로 뒤집어 먼저 트리 이름을
 * 찾은 뒤 그 이름이 실제로 이 축의 자식인지 확인한다.
 *
 * <p>{@code chosenSpecs}에는 트리 자식 이름이 아니라 <b>코드값 그대로</b>를 담는다.
 * {@code SimulationConfig.setTruckType}·{@code setTravelTimeMode}·
 * {@code setDischargeTimeMode}가 받는 값이 이미 코드값이기 때문이다(SimulationConfig
 * 참조) — Task 9가 이 값을 그대로 세터에 넘길 수 있어야 하므로, 트리 이름으로
 * 저장하면 Task 9가 다시 코드값으로 되돌리는 중복 번역을 해야 한다.
 */
public final class SesPruner {

    private SesPruner() { }

    public static PrunedStructure prune(Map<String, Object> answersByField) {
        Map<String, DecisionPoint> byId = new LinkedHashMap<>();
        for (DecisionPoint p : DecisionPointExtractor.extract(JangnyangEntityStructure.get())) {
            byId.put(p.id(), p);
        }

        Map<String, String> chosen = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> pointOfField = new LinkedHashMap<>();
        Set<String> dead = new LinkedHashSet<>();

        for (SesFieldMapping.FieldBinding b : SesFieldMapping.bindings()) {
            DecisionPoint point = byId.get(b.pointId());
            Object answer = answersByField.get(b.answerField());

            if (point instanceof DecisionPoint.SpecChoice spec) {
                if (answer == null) {
                    throw new IllegalStateException("spec 축이 정해지지 않았다: " + spec.axis()
                            + " (필드 " + b.answerField() + ")");
                }
                String code = String.valueOf(answer);
                String treeName = childNameForCode(b.optionCodes(), code);
                if (treeName == null || !spec.options().contains(treeName)) {
                    throw new IllegalStateException("축에 없는 가지를 골랐다: " + spec.axis()
                            + " ← " + code + " (가능: " + spec.options() + ")");
                }
                // 코드값 그대로 담는다 — SimulationConfig의 세터가 코드값을 받는다(Ruling 3).
                chosen.put(spec.id(), code);

            } else if (point instanceof DecisionPoint.MultiCount multi) {
                if (answer == null) {
                    throw new IllegalStateException("복제 수가 정해지지 않았다: " + multi.entity()
                            + " (필드 " + b.answerField() + ")");
                }
                // M4 — 숫자가 아닌 값이 들어오면 ClassCastException이 아니라 다른 갈래와
                // 같은 문체(사유가 담긴 IllegalStateException)로 실패해야 한다. 검증을
                // 통과한 값만 여기 도달한다는 전제가 깨졌다는 뜻이라 조용히 넘기지 않는다.
                if (!(answer instanceof Number n)) {
                    throw new IllegalStateException("복제 수가 숫자가 아니다: " + multi.entity()
                            + " (필드 " + b.answerField() + ", 받은 값: " + answer + ")");
                }
                counts.put(multi.id(), n.intValue());

            } else if (point instanceof DecisionPoint.CouplingActivation) {
                // 답이 없으면 켠 것으로 보지 않는다 — 끄는 쪽이 안전한 기본이다.
                if (!"APPLY".equals(String.valueOf(answer))) killAllCouplingPoints(dead, byId);

            } else if (answer != null) {
                // 답하지 않은 속성은 PES에 넣지 않는다 — 넣지 않은 것이 곧
                // "이 실험에서 정하지 않았다"는 기록이다.
                // 키는 pointId가 아니라 answerField다(Ruling 2) — collectionTime과
                // collectionTimes처럼 같은 지점을 가리키는 서로 다른 필드가 있어서,
                // pointId를 키로 쓰면 나중 필드가 먼저 필드의 값을 덮어쓴다.
                values.put(b.answerField(), answer);
                pointOfField.put(b.answerField(), point.id());
            }
        }

        return new PrunedStructure(chosen, counts, values, pointOfField, dead);
    }

    /** optionCodes(트리 이름 → 코드값)를 뒤집어 코드값으로 트리 이름을 찾는다. */
    private static String childNameForCode(Map<String, String> optionCodes, String code) {
        if (optionCodes == null) return null;
        for (Map.Entry<String, String> e : optionCodes.entrySet()) {
            if (e.getValue().equals(code)) return e.getKey();
        }
        return null;
    }

    /**
     * 교통을 끄면 교통 구역에서 나가는 결합이 <b>전부</b> 죽는다. 지금 조건부 결합은 둘
     * 다 같은 조건({@code trafficMode=APPLY})을 갖는다 — 조건이 갈라지면 이 자리를
     * 조건별로 나눈다.
     */
    private static void killAllCouplingPoints(Set<String> dead, Map<String, DecisionPoint> byId) {
        List<String> activations = byId.keySet().stream()
                .filter(id -> id.startsWith("coupling:")).toList();
        dead.addAll(activations);
    }
}
