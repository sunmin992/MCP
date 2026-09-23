package com.wastesim.template;

import com.wastesim.ledger.Activation;
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
            String origin = status == SubtaskStatus.FILLED ? "USER" : null;
            out.add(new SubtaskInstance(
                    t.templateId(), t.answerKey(), status, value, origin, t.question()));
        }
        return new SubtaskPlan(out);
    }
}
