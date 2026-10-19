package com.wastesim.template;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 생성 조건이 선언한 입력(inputs)만 읽는가.
 *
 * <p>inputs 는 보류된 결정이 "무엇을 먼저 정해야 하는지" 를 알리는 데 쓰인다. 평가 코드가 선언하지 않은 키를 읽으면
 * 그 안내가 틀린다. 템플릿의 허용값 · 범위로 무작위 답변을 만들어, 전체 답변과 선언한 입력만 남긴 답변의 판정이
 * 같은지 본다.
 */
class GenerateConditionInputsTest {

    private final TemplateCatalog catalog = new TemplateCatalog();

    private Object sample(SubtaskTemplate t, Random rnd) {
        if (rnd.nextInt(4) == 0) return null;   // 답이 없는 경우도 섞는다
        List<String> allowed = t.allowed();
        if ("ENUM_LIST".equals(t.valueType())) {
            return List.of(allowed.get(rnd.nextInt(allowed.size())), allowed.get(rnd.nextInt(allowed.size())));
        }
        if (!allowed.isEmpty()) return allowed.get(rnd.nextInt(allowed.size()));
        if ("INTEGER_LIST".equals(t.valueType())) return rnd.nextBoolean() ? List.of() : List.of(360, 1080);
        double lo = t.min() == null ? 0 : t.min();
        double hi = t.max() == null ? lo + 30 : t.max();
        int v = (int) Math.round(lo + rnd.nextDouble() * (hi - lo));
        return "NUMBER".equals(t.valueType()) ? (Object) (double) v : (Object) v;
    }

    @Test
    void 조건은_선언한_입력만_읽는다() {
        Random rnd = new Random(7);
        for (int n = 0; n < 2000; n++) {
            Map<String, Object> answers = new HashMap<>();
            for (SubtaskTemplate t : catalog.all()) {
                Object v = sample(t, rnd);
                if (v != null) answers.put(t.answerKey(), v);
            }
            for (GenerateCondition c : GenerateCondition.values()) {
                Map<String, Object> only = new HashMap<>();
                for (String k : c.inputs()) if (answers.containsKey(k)) only.put(k, answers.get(k));
                assertEquals(c.evaluate(answers), c.evaluate(only), c + " 이 선언하지 않은 키를 읽는다: " + answers);
            }
        }
    }

    @Test
    void 보류된_결정은_기다리는_키를_알려준다() {
        SubtaskPlanner planner = new SubtaskPlanner(catalog);
        for (SubtaskInstance i : planner.plan(Map.of()).instances()) {
            if (i.status() == SubtaskStatus.DEFERRED) {
                assertEquals(false, planner.waitingOn(i.answerKey(), Map.of()).isEmpty(),
                        i.answerKey() + " 는 보류인데 무엇을 기다리는지 모른다");
            }
        }
    }
}
