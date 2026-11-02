package com.wastesim.template;

import java.util.HashMap;
import java.util.Map;

/** 생성 조건 의존 깊이로 센 최소 필요 단계. 순환은 카탈로그가 막으므로 메모 재귀로 충분하다. */
public final class MinRounds {

    private MinRounds() {}

    public static int of(SubtaskPlan plan, TemplateCatalog catalog) {
        return 1 + levels(plan, catalog).values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** ACTIVE(FILLED/UNFILLED) 템플릿 결정마다의 깊이. */
    static Map<String, Integer> levels(SubtaskPlan plan, TemplateCatalog catalog) {
        Map<String, SubtaskInstance> active = new HashMap<>();
        for (SubtaskInstance i : plan.instances())
            if (i.status() == SubtaskStatus.FILLED || i.status() == SubtaskStatus.UNFILLED) active.put(i.answerKey(), i);
        Map<String, Integer> memo = new HashMap<>();
        for (SubtaskInstance i : active.values()) level(i.answerKey(), active, catalog, memo);
        Map<String, Integer> byTemplate = new HashMap<>();
        active.forEach((k, i) -> byTemplate.put(i.templateId(), memo.get(k)));
        return byTemplate;
    }

    private static int level(String key, Map<String, SubtaskInstance> active, TemplateCatalog catalog, Map<String, Integer> memo) {
        Integer m = memo.get(key);
        if (m != null) return m;
        int best = -1;
        for (String in : catalog.byAnswerKey(key).orElseThrow().generateWhen().inputs())
            if (!in.equals(key) && active.containsKey(in)) best = Math.max(best, level(in, active, catalog, memo));
        memo.put(key, best + 1);
        return best + 1;
    }
}
