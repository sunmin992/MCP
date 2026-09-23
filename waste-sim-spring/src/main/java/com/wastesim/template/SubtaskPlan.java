package com.wastesim.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 요청에 대한 서브태스크 집합.
 *
 * <p>{@link #counts()}가 RQ2의 원자료다. 다만 생성 수의 <b>절대값</b>은 요청마다 달라
 * 지표가 되지 못한다 — 같은 요청을 다른 방식과 쌍으로 비교할 때만 뜻이 있다.
 */
public record SubtaskPlan(List<SubtaskInstance> instances) {

    public SubtaskPlan {
        instances = List.copyOf(instances);
    }

    /** 사용자에게 물어야 할 것. 보류와 미생성은 묻지 않는다. */
    public List<SubtaskInstance> toAsk() {
        return instances.stream().filter(i -> i.status() == SubtaskStatus.UNFILLED).toList();
    }

    /** 값이 정해진 것만 답변 맵으로. 다음 회차의 생성 조건 평가 입력이 된다. */
    public Map<String, Object> answeredValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SubtaskInstance i : instances) {
            if (i.status() == SubtaskStatus.FILLED) out.put(i.answerKey(), i.value());
        }
        return out;
    }

    public Map<SubtaskStatus, Integer> counts() {
        Map<SubtaskStatus, Integer> out = new LinkedHashMap<>();
        for (SubtaskStatus s : SubtaskStatus.values()) out.put(s, 0);
        for (SubtaskInstance i : instances) out.merge(i.status(), 1, Integer::sum);
        return out;
    }

    /** 물어야 할 것도 보류도 없는가. 이때만 PES를 만들 수 있다. */
    public boolean complete() {
        return instances.stream().noneMatch(
                i -> i.status() == SubtaskStatus.UNFILLED || i.status() == SubtaskStatus.DEFERRED);
    }
}
