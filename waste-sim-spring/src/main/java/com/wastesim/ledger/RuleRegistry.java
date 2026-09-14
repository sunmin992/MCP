package com.wastesim.ledger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 등록된 결정론적 활성 규칙.
 *
 * <p><b>왜 자연어 조건을 실행하지 않는가</b>: {@code Coupling.activeWhen}이 지금 들고 있는
 * 문자열은 사람이 읽는 설명이다. 그것을 해석해 실행하면 해석기가 곧 또 하나의 추론 경로가
 * 되고, 추론 경로는 이 프로젝트가 값 결정에서 막아 온 바로 그것이다. 그래서 조건은 등록된
 * ID로만 참조하고, <b>모르는 ID는 평가하지 않고 던진다</b> — 조용히 비활성으로 처리하면
 * 오타 하나가 가지 하나를 통째로 없앤다.
 */
public final class RuleRegistry {

    /** 답변들을 보고 이 가지가 살아 있는지 판정한다. */
    @FunctionalInterface
    public interface Rule {
        Activation evaluate(Map<String, Object> answers);
    }

    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public RuleRegistry register(String ruleId, Rule rule) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("규칙 ID가 없습니다.");
        }
        if (rules.putIfAbsent(ruleId, rule) != null) {
            throw new IllegalArgumentException("이미 등록된 규칙 ID입니다: " + ruleId);
        }
        return this;
    }

    public boolean knows(String ruleId) {
        return rules.containsKey(ruleId);
    }

    public Set<String> ruleIds() {
        return Set.copyOf(rules.keySet());
    }

    public Activation evaluate(String ruleId, Map<String, Object> answers) {
        Rule rule = rules.get(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("등록되지 않은 규칙 ID입니다: " + ruleId);
        }
        return rule.evaluate(answers == null ? Map.of() : answers);
    }

    /**
     * 필드 하나가 기대값과 같은가.
     *
     * <p>값이 없거나 {@code null}이면 {@link Activation#UNKNOWN}이다 — 없는 것을
     * "다르다"로 읽으면 미답이 곧 비활성이 된다.
     */
    public static Rule fieldEquals(String field, Object expected) {
        return answers -> {
            if (!answers.containsKey(field) || answers.get(field) == null) {
                return Activation.UNKNOWN;
            }
            return expected.equals(answers.get(field)) ? Activation.ACTIVE : Activation.INACTIVE;
        };
    }
}
