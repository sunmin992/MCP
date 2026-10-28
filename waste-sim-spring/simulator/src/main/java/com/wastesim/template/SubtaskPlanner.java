package com.wastesim.template;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 템플릿의 생성 조건을 현재 답변에 비추어 평가해 서브태스크 인스턴스를 만든다.
 *
 * <p>서버가 소유하는 것은 규칙이고, <b>어떤 작업이 존재해야 하는지는 요청마다 정해진다.</b>
 * 그래서 이 클래스는 상태를 갖지 않는다 — 답변 맵 하나를 받아 그때의 집합을 낸다.
 * 답이 바뀌면 다시 부르면 되고, 그 결과가 이전과 달라지는 것이 정상이다.
 */
@Component
public class SubtaskPlanner {

    private final TemplateCatalog catalog;

    public SubtaskPlanner(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    public SubtaskPlan plan(Map<String, Object> answers) {
        return plan(answers, Map.of());
    }

    /**
     * 보류된 결정이 기다리는 답변키 — 생성 조건이 읽는 키 가운데 아직 답이 없는 것.
     * "이것을 먼저 정해야 이 결정이 필요한지 알 수 있다" 를 LLM 에 알리는 데 쓴다.
     */
    public List<String> waitingOn(String answerKey, Map<String, Object> answers) {
        return catalog.byAnswerKey(answerKey)
                .map(t -> t.generateWhen().inputs().stream().filter(k -> answers.get(k) == null).toList())
                .orElse(List.of());
    }

    /**
     * @param origins 답변키 → 출처. 받은 것만 그대로 싣는다. 적혀 오지 않은 값의 출처는 비워 둔다 —
     *                "사용자가 말했다" 고 넘겨짚으면 기본값으로 채운 값과 구별되지 않는다.
     */
    public SubtaskPlan plan(Map<String, Object> answers, Map<String, String> origins) {
        List<SubtaskInstance> out = new ArrayList<>();
        for (SubtaskTemplate t : catalog.all()) {
            Activation activation = t.generateWhen().evaluate(answers);
            SubtaskStatus status = switch (activation) {
                case INACTIVE -> SubtaskStatus.NOT_GENERATED;
                case UNKNOWN -> SubtaskStatus.DEFERRED;
                case ACTIVE -> answers.get(t.answerKey()) != null
                        ? SubtaskStatus.FILLED : SubtaskStatus.UNFILLED;
            };
            Object value = status == SubtaskStatus.FILLED ? answers.get(t.answerKey()) : null;
            String origin = status == SubtaskStatus.FILLED ? origins.get(t.answerKey()) : null;
            out.add(new SubtaskInstance(
                    t.templateId(), t.answerKey(), status, value, origin, t.question()));
        }
        return new SubtaskPlan(out);
    }

    /**
     * 이 답에서의 기본값. 템플릿 기본값과 같되, 블록 적재 몫은 차종에 따라 달라 고정값을 둘 수 없다 — 엔진이
     * 빈 값에 쓰는 차종 정격용량을 기본값으로 제안한다. 템플릿을 언제나 묻는 필수값으로 두었더니 사용자가
     * 배정 몫을 몰라 "기본값으로" 라고 하자 시나리오가 막혔다(2026-10-28 UT-B02 · J01 · B12).
     */
    public Object defaultFor(String answerKey, Map<String, Object> answers) {
        if (!"routeAvailableCapacityKg".equals(answerKey))
            return template(answerKey).map(SubtaskTemplate::defaultValue).orElse(null);
        Object type = answers.get("truckType");
        if (type == null) type = template("truckType").map(SubtaskTemplate::defaultValue).orElse(null);
        try {
            return com.wastesim.model.TruckType.fromName(String.valueOf(type)).capacityKg;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public java.util.Optional<SubtaskTemplate> template(String answerKey) {
        return catalog.byAnswerKey(answerKey);
    }

    /**
     * 실험 변수의 값마다 계획을 세워 합친 결과.
     *
     * @param plan      합친 계획. 결정마다 가장 급한 상태를 고른다 — 물어야 함 &gt; 보류 &gt; 채워짐 &gt; 생성 안 됨.
     *                  실험 변수 자체는 비교할 값 목록으로 채워진 것으로 본다
     * @param onlyFor   어떤 비교 값에서만 물어야 하는 결정 → 그 값들. 모든 값에서 필요하면 싣지 않는다
     * @param waitingOn 보류된 결정 → 먼저 정해야 할 답변키(값마다의 합집합)
     */
    public record Across(SubtaskPlan plan, Map<String, List<Object>> onlyFor, Map<String, List<String>> waitingOn) {}

    /**
     * 실험 변수의 비교 값마다 생성 조건을 평가해 합친다.
     *
     * <p>값 하나로만 계산하면, 다른 비교 값에서만 생기는 결정(차량 3대일 때의 배차 간격, 시간대 차등 요금일 때의
     * 할증률)이 시나리오를 만들 때에야 드러나 질문이 한 번 더 늘어난다. {@code build_scenario} 가 실행 설정마다
     * 세는 것과 같은 계산을 미리 한다.
     */
    public Across planAcross(Map<String, Object> answers, Map<String, String> origins,
                             String variableKey, List<Object> values) {
        if (variableKey == null || variableKey.isBlank() || values == null || values.isEmpty()) {
            SubtaskPlan p = plan(answers, origins);
            return new Across(p, Map.of(), waitingOf(p, answers));
        }
        List<SubtaskPlan> plans = new ArrayList<>();
        List<Map<String, Object>> runAnswers = new ArrayList<>();
        for (Object v : values) {
            Map<String, Object> a = new java.util.LinkedHashMap<>(answers);
            a.put(variableKey, v);
            runAnswers.add(a);
            plans.add(plan(a, origins));
        }
        List<SubtaskInstance> merged = new ArrayList<>();
        Map<String, List<Object>> onlyFor = new java.util.LinkedHashMap<>();
        Map<String, List<String>> waiting = new java.util.LinkedHashMap<>();
        int n = plans.get(0).instances().size();
        for (int idx = 0; idx < n; idx++) {
            SubtaskInstance best = plans.get(0).instances().get(idx);
            List<Object> unfilledFor = new ArrayList<>();
            java.util.Set<String> waits = new java.util.LinkedHashSet<>();
            for (int r = 0; r < plans.size(); r++) {
                SubtaskInstance i = plans.get(r).instances().get(idx);
                if (rank(i.status()) < rank(best.status())) best = i;
                if (i.status() == SubtaskStatus.UNFILLED) unfilledFor.add(values.get(r));
                if (i.status() == SubtaskStatus.DEFERRED) waits.addAll(waitingOn(i.answerKey(), runAnswers.get(r)));
            }
            if (best.answerKey().equals(variableKey)) {
                best = new SubtaskInstance(best.templateId(), best.answerKey(), SubtaskStatus.FILLED,
                        List.copyOf(values), origins.get(variableKey), best.question());
            }
            merged.add(best);
            if (!unfilledFor.isEmpty() && unfilledFor.size() < values.size()) onlyFor.put(best.answerKey(), unfilledFor);
            if (best.status() == SubtaskStatus.DEFERRED) waiting.put(best.answerKey(), List.copyOf(waits));
        }
        return new Across(new SubtaskPlan(merged), onlyFor, waiting);
    }

    private Map<String, List<String>> waitingOf(SubtaskPlan p, Map<String, Object> answers) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (SubtaskInstance i : p.instances()) {
            if (i.status() == SubtaskStatus.DEFERRED) out.put(i.answerKey(), waitingOn(i.answerKey(), answers));
        }
        return out;
    }

    private static int rank(SubtaskStatus s) {
        return switch (s) {
            case UNFILLED -> 0;
            case DEFERRED -> 1;
            case FILLED -> 2;
            case NOT_GENERATED -> 3;
        };
    }
}
