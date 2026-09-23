package com.wastesim.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매개변수별 결정 이력. <b>덮어쓰지 않는다.</b>
 *
 * <p><b>왜 현재 값을 따로 저장하지 않는가</b>: 저장하면 이력과 현재가 갈라질 수 있고,
 * 갈라지면 어느 쪽이 옳은지 판단할 근거가 없다. 현재는 언제나 <b>이력의 마지막</b>이므로
 * 계산해서 답한다 — 두 사실을 하나로 줄이면 어긋날 자리가 없어진다.
 *
 * <p>{@code SubtaskState}가 시간 축에 대해 한 일과 같은 태도다.
 */
public final class ParameterLedger {

    private final Map<String, List<ParameterDecision>> byParameter = new LinkedHashMap<>();
    private final Set<String> usedIds = new LinkedHashSet<>();

    /** 결정을 쌓는다. 같은 ID를 두 번 쌓으면 이력이 가리키는 곳이 모호해지므로 거부한다. */
    public ParameterDecision append(ParameterDecision decision) {
        if (!usedIds.add(decision.decisionId())) {
            throw new IllegalArgumentException(
                    "이미 쓴 결정 ID입니다: " + decision.decisionId());
        }
        byParameter.computeIfAbsent(decision.parameterId(), k -> new ArrayList<>())
                .add(decision);
        return decision;
    }

    /** 이 매개변수의 현재 결정. 없으면 {@code null}. */
    public ParameterDecision current(String parameterId) {
        List<ParameterDecision> history = byParameter.get(parameterId);
        if (history == null || history.isEmpty()) return null;
        return history.get(history.size() - 1);
    }

    /** 쌓인 순서 그대로의 이력. */
    public List<ParameterDecision> history(String parameterId) {
        return List.copyOf(byParameter.getOrDefault(parameterId, List.of()));
    }

    public List<String> parameterIds() {
        return List.copyOf(byParameter.keySet());
    }

    /**
     * 지금 실행을 막는 결정들. 이력이 아니라 <b>현재</b>만 본다 — 과거에 막혔다가 풀린
     * 것까지 세면 영원히 실행할 수 없다.
     */
    public List<ParameterDecision> blocking() {
        List<ParameterDecision> blocking = new ArrayList<>();
        for (String id : byParameter.keySet()) {
            ParameterDecision now = current(id);
            if (now != null && !now.state().executable()) blocking.add(now);
        }
        return List.copyOf(blocking);
    }

    /** 다음 결정 ID. 매개변수마다 1부터 센다 — 사람이 이력을 읽을 때 순서가 보인다. */
    public String nextDecisionId(String parameterId) {
        return parameterId + "#" + (byParameter.getOrDefault(parameterId, List.of()).size() + 1);
    }
}
