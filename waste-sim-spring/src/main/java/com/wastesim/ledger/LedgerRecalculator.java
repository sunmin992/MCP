package com.wastesim.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 구조를 바꾸는 답변이 들어왔을 때 원장을 다시 계산한다.
 *
 * <p><b>왜 이 자리가 필요한가</b>: 지금 수집 경로는 답변을 받아 검증하고 조립하지만,
 * 뒤에 온 답변이 앞서 받은 답변의 전제를 무너뜨렸는지 따지지 않는다. 그래서 교통을 켠
 * 뒤 끄면 교통 프로필 값이 남아 있고, 끈 뒤 켜면 프로필을 묻지 않은 채로 조립이 된다.
 *
 * <p>상태 기계는 건드리지 않는다 — {@code SubtaskState}는 {@code READY → COLLECTING}과
 * {@code BUILT → COLLECTING}을 이미 허용한다("답을 고치면 다시 수집으로"). 여기서 하는
 * 일은 그 전이를 <b>일으켜야 할 때를 알아내는 것</b>이다.
 */
public final class LedgerRecalculator {

    private final RuleRegistry rules;
    private final Map<String, String> activeWhenByParameter;
    private final Map<String, List<String>> dependents;

    /**
     * @param activeWhenByParameter 매개변수 → 그 가지를 살리는 규칙 ID
     * @param dependents            매개변수 → 이 값이 바뀌면 낡는 매개변수들
     */
    public LedgerRecalculator(RuleRegistry rules,
                              Map<String, String> activeWhenByParameter,
                              Map<String, List<String>> dependents) {
        this.rules = rules;
        this.activeWhenByParameter = Map.copyOf(activeWhenByParameter);
        this.dependents = Map.copyOf(dependents);
        for (String ruleId : activeWhenByParameter.values()) {
            if (!rules.knows(ruleId)) {
                throw new IllegalArgumentException("등록되지 않은 규칙 ID입니다: " + ruleId);
            }
        }
    }

    /**
     * 답변 하나가 바뀌었다. 낡은 것을 표시하고 활성 구조를 다시 계산한다.
     *
     * @return 이번에 새로 쌓인 결정들
     */
    public List<ParameterDecision> onAnswerChanged(ParameterLedger ledger,
                                                   String changedParameterId,
                                                   Map<String, Object> answers) {
        Instant now = Instant.now();
        List<ParameterDecision> appended = new ArrayList<>();

        // 1) 종속 결정을 낡은 것으로 표시한다. 값은 지우지 않는다 —
        //    무엇이 있었는지 알아야 무엇이 바뀌었는지 말할 수 있다.
        Set<String> staledInStep1 = new HashSet<>();
        for (String dependent : dependents.getOrDefault(changedParameterId, List.of())) {
            ParameterDecision current = ledger.current(dependent);
            if (current == null || !current.state().executable()) continue;
            // 규칙이 만든 "해당 없음" 자리표시자는 사용자가 실제로 입력한 값이 아니다 —
            // 매번 낡혔다가 2단계에서 다시 같은 자리표시자로 되돌아올 뿐이므로, 같은 답을
            // 거듭 반영해도 이력이 STALE↔DEFAULTED로 요동치지 않도록 여기서 걸러낸다.
            if (current.state() == DecisionState.DEFAULTED
                    && "not_applicable_by_rule".equals(current.source().type())) {
                continue;
            }
            appended.add(ledger.append(new ParameterDecision(
                    ledger.nextDecisionId(dependent), dependent, DecisionState.STALE,
                    current.rawValue(), current.rawUnit(),
                    current.normalizedValue(), current.normalizedUnit(),
                    current.source(), current.transformation(), current.evidenceRefs(),
                    "upstream_value_changed", changedParameterId, now)));
            staledInStep1.add(dependent);
        }

        // 2) 활성 구조를 다시 계산한다. 단, 1단계에서 이미 낡은 것으로 표시한 매개변수는
        //    건드리지 않는다 — STALE은 이미 실행을 막고(executable() == false) 옛 값도
        //    보존하고 있다. 여기서 또 UNRESOLVED를 쌓으면 방금 일부러 지키기로 한 값을
        //    덮어써 버리게 된다.
        for (Map.Entry<String, String> e : activeWhenByParameter.entrySet()) {
            String parameterId = e.getKey();
            if (staledInStep1.contains(parameterId)) continue;
            Activation activation = rules.evaluate(e.getValue(), answers);
            ParameterDecision next = decisionFor(ledger, parameterId, e.getValue(), activation, now);
            if (next != null) appended.add(ledger.append(next));
        }

        return List.copyOf(appended);
    }

    /** 이미 같은 결론이면 {@code null} — 같은 레코드를 거듭 쌓으면 이력이 잡음이 된다. */
    private ParameterDecision decisionFor(ParameterLedger ledger, String parameterId,
                                          String ruleId, Activation activation, Instant now) {
        ParameterDecision current = ledger.current(parameterId);

        return switch (activation) {
            case ACTIVE -> (current != null && current.state().executable()
                    && !"not_applicable_by_rule".equals(current.source().type()))
                    ? null
                    : blocked(ledger, parameterId, "required_value_unresolved", current, now);

            // 비활성 가지는 묻지 않고 해당 없음으로 확정한다. 세트에서 지우지 않는 이유는
            // 50항목을 생략 없이 유지한다는 규약이 세트 해시의 전제이기 때문이다.
            case INACTIVE -> (current != null && current.state() == DecisionState.DEFAULTED
                    && "not_applicable_by_rule".equals(current.source().type()))
                    ? null
                    : new ParameterDecision(
                            ledger.nextDecisionId(parameterId), parameterId,
                            DecisionState.DEFAULTED, null, null, null, null,
                            new ValueSource("not_applicable_by_rule", ruleId, null, now),
                            null, List.of(), null, null, now);

            case UNKNOWN -> blocked(ledger, parameterId, "activation_unknown", current, now);
        };
    }

    private ParameterDecision blocked(ParameterLedger ledger, String parameterId,
                                      String reason, ParameterDecision current, Instant now) {
        if (current != null && current.state() == DecisionState.UNRESOLVED
                && reason.equals(current.blockingReason())) {
            return null;
        }
        return new ParameterDecision(
                ledger.nextDecisionId(parameterId), parameterId, DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(), reason, null, now);
    }
}
