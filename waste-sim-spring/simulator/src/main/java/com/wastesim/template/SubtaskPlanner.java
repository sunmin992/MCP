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
                .map(t -> t.generateWhen().inputs().stream().filter(k -> !answers.containsKey(k)).toList())
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
                case ACTIVE -> answers.containsKey(t.answerKey())
                        ? SubtaskStatus.FILLED : SubtaskStatus.UNFILLED;
            };
            Object value = status == SubtaskStatus.FILLED ? answers.get(t.answerKey()) : null;
            String origin = status == SubtaskStatus.FILLED ? origins.get(t.answerKey()) : null;
            out.add(new SubtaskInstance(
                    t.templateId(), t.answerKey(), status, value, origin, t.question()));
        }
        return new SubtaskPlan(out);
    }
}
