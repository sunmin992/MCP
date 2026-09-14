package com.wastesim.ledger;

import java.time.Duration;
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
            // 규칙이 만든 "해당 없음" 자리표시자는 사용자가 실제로 입력해 낡을 수 있는
            // 값이 아니다 — 따라서 낡혔다는 표시 자체가 성립하지 않으므로 건드리지 않는다.
            if (current.state() == DecisionState.DEFAULTED
                    && ValueSource.NOT_APPLICABLE_BY_RULE.equals(current.source().type())) {
                continue;
            }
            appended.add(ledger.append(new ParameterDecision(
                    ledger.nextDecisionId(dependent), dependent, DecisionState.STALE,
                    current.rawValue(), current.rawUnit(),
                    current.normalizedValue(), current.normalizedUnit(),
                    current.source(), current.transformation(), current.evidenceRefs(),
                    BlockingReasons.UPSTREAM_VALUE_CHANGED, changedParameterId, now)));
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
                    && !ValueSource.NOT_APPLICABLE_BY_RULE.equals(current.source().type()))
                    ? null
                    : blocked(ledger, parameterId,
                            BlockingReasons.REQUIRED_VALUE_UNRESOLVED, current, now);

            // 비활성 가지는 묻지 않고 해당 없음으로 확정한다. 세트에서 지우지 않는 이유는
            // 50항목을 생략 없이 유지한다는 규약이 세트 해시의 전제이기 때문이다.
            case INACTIVE -> (current != null && current.state() == DecisionState.DEFAULTED
                    && ValueSource.NOT_APPLICABLE_BY_RULE.equals(current.source().type()))
                    ? null
                    : new ParameterDecision(
                            ledger.nextDecisionId(parameterId), parameterId,
                            DecisionState.DEFAULTED, null, null, null, null,
                            new ValueSource(ValueSource.NOT_APPLICABLE_BY_RULE, ruleId, null, now),
                            null, List.of(), null, null, now);

            // 활성 여부를 아직 모른다고 해서 이미 받은 답을 지우지 않는다. "미확정을
            // 비활성으로 접지 않는다"는 규약은 질문을 건너뛰지 말라는 뜻이었지, 손에 든
            // 값을 버리라는 뜻이 아니었다 — 필요한지와 값이 있는지는 다른 사실이다. 뒤에
            // 조건이 INACTIVE로 밝혀지면 그 분기가 해당 없음으로 정리하고, ACTIVE로
            // 밝혀지면 이미 확정된 값이 그대로 선다.
            case UNKNOWN -> (current != null && current.state().executable())
                    ? null
                    : blocked(ledger, parameterId, BlockingReasons.ACTIVATION_UNKNOWN, current, now);
        };
    }

    /**
     * 출처 만료. 허용 나이를 넘긴 확정값을 낡은 것으로 표시한다.
     *
     * <p><b>왜 값을 다시 조달하지 않는가</b>: 여기서 하는 일은 "더는 믿을 수 없다"를
     * 드러내는 것뿐이다. 다시 얻는 것은 조달 계층의 일이고, 그 경계를 섞으면 만료 판정이
     * 곧 도구 재호출이 되어 중복 실행 방지가 보증되지 않는 자리에서 같은 작업을 두 번
     * 시키게 된다.
     *
     * @param maxAge 이보다 오래된 출처는 더 쓰지 않는다
     * @return 이번에 새로 쌓인 결정들
     */
    public List<ParameterDecision> onSourceExpired(ParameterLedger ledger,
                                                   Duration maxAge, Instant now) {
        if (maxAge == null) {
            throw new IllegalArgumentException("최대 허용 나이가 없습니다.");
        }
        List<ParameterDecision> appended = new ArrayList<>();
        for (String parameterId : ledger.parameterIds()) {
            ParameterDecision current = ledger.current(parameterId);
            if (current == null || !current.state().executable()) continue;
            Instant acquiredAt = current.source().acquiredAt();
            // 시각이 없는 출처는 건너뛴다. 시각의 부재는 오래됐다는 증거가 아니므로,
            // 그것을 만료로 읽으면 기록을 덜 남긴 값이 낡았다는 판정을 대신 받는다 —
            // 규칙이 만든 "해당 없음" 자리표시자가 그런 값이다.
            if (acquiredAt == null) continue;
            if (Duration.between(acquiredAt, now).compareTo(maxAge) <= 0) continue;
            appended.add(ledger.append(staleCopy(ledger, current,
                    BlockingReasons.SOURCE_EXPIRED,
                    BlockingReasons.SOURCE_EXPIRED + ":" + maxAge, now)));
        }
        return List.copyOf(appended);
    }

    /**
     * 세트 버전 변경. <b>전체 재계산</b>이므로 지금 실행에 쓸 수 있는 결정을 모두 낡게 한다.
     *
     * <p>버전이 바뀌면 문항·허용값·활성 규칙이 통째로 달라졌을 수 있고, 무엇이 달라졌는지를
     * 원장이 알 방법은 없다. 알 수 없는 것을 골라내려 하는 대신 전부 다시 묻는 쪽을 택한다 —
     * 골라내기가 틀리면 낡은 값이 조용히 새 세트의 실행에 섞여 든다.
     *
     * @param newSetVersion 무엇 때문에 낡았는지 가리킬 새 세트 버전
     * @return 이번에 새로 쌓인 결정들
     */
    public List<ParameterDecision> onSetVersionChanged(ParameterLedger ledger,
                                                       String newSetVersion, Instant now) {
        if (newSetVersion == null || newSetVersion.isBlank()) {
            throw new IllegalArgumentException("새 세트 버전이 없습니다.");
        }
        List<ParameterDecision> appended = new ArrayList<>();
        for (String parameterId : ledger.parameterIds()) {
            ParameterDecision current = ledger.current(parameterId);
            if (current == null || !current.state().executable()) continue;
            appended.add(ledger.append(staleCopy(ledger, current,
                    BlockingReasons.SET_VERSION_CHANGED, newSetVersion, now)));
        }
        return List.copyOf(appended);
    }

    /** 값은 그대로 두고 상태만 낡음으로 옮긴다 — 무엇이 있었는지 알아야 무엇이 바뀌었는지 말할 수 있다. */
    private ParameterDecision staleCopy(ParameterLedger ledger, ParameterDecision current,
                                        String reason, String supersededBy, Instant now) {
        return new ParameterDecision(
                ledger.nextDecisionId(current.parameterId()), current.parameterId(),
                DecisionState.STALE,
                current.rawValue(), current.rawUnit(),
                current.normalizedValue(), current.normalizedUnit(),
                current.source(), current.transformation(), current.evidenceRefs(),
                reason, supersededBy, now);
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
