package com.wastesim.registry;

import com.wastesim.ledger.RuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 등록 검증 다섯 규칙.
 *
 * <p>넷은 계약 안의 일관성을 보고, {@code checkInputBindingCoverage} 하나만 계약 밖을
 * 본다. <b>그 하나가 이 클래스의 이유다</b> — 계약이 입력을 누락하면 그 계약으로 만든
 * 검증기도 같이 누락하므로, 계약을 정본으로 삼지 않는 검사가 최소 하나는 있어야 한다.
 */
public final class RegistrationValidator {

    /** {@code <verified-version>}처럼 채우지 않고 남겨 둔 자리. */
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^<>]+>");

    public List<RegistrationIssue> validate(AssetContract contract,
                                            RuleRegistry rules,
                                            Set<String> adapterIds) {
        List<RegistrationIssue> issues = new ArrayList<>();

        rejectUnresolvedPlaceholders(contract, issues);
        rejectUnknownRuleRefs(contract, rules, issues);
        rejectUnknownAdapterRefs(contract, adapterIds, issues);
        requireEvidenceBeforeVerified(contract, issues);
        checkInputBindingCoverage(contract, issues);

        return List.copyOf(issues);
    }

    private void rejectUnresolvedPlaceholders(AssetContract c, List<RegistrationIssue> issues) {
        List<String> found = new ArrayList<>();
        for (String value : allStrings(c)) {
            if (value != null && PLACEHOLDER.matcher(value).find()) found.add(value);
        }
        if (!found.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "rejectUnresolvedPlaceholders",
                    "채우지 않은 자리가 남아 있습니다: " + found));
        }
    }

    private void rejectUnknownRuleRefs(AssetContract c, RuleRegistry rules,
                                       List<RegistrationIssue> issues) {
        List<String> unknown = c.ruleRefs().stream().filter(r -> !rules.knows(r)).sorted().toList();
        if (!unknown.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "rejectUnknownRuleRefs", "등록되지 않은 규칙입니다: " + unknown));
        }
    }

    private void rejectUnknownAdapterRefs(AssetContract c, Set<String> adapterIds,
                                          List<RegistrationIssue> issues) {
        List<String> unknown = c.adapterRefs().stream()
                .filter(a -> !adapterIds.contains(a)).sorted().toList();
        if (!unknown.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "rejectUnknownAdapterRefs", "등록되지 않은 어댑터입니다: " + unknown));
        }
    }

    private void requireEvidenceBeforeVerified(AssetContract c, List<RegistrationIssue> issues) {
        if (c.status() == AssetContract.Status.VERIFIED && c.evidenceRefs().isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REJECT,
                    "requireEvidenceBeforeVerified",
                    "증거 없이 확인됨으로 올릴 수 없습니다: " + c.assetId()));
        }
    }

    /**
     * 계약이 덮지 못한 실제 입력 필드를 보고한다. <b>거부가 아니다</b> — 계약을 늘릴지
     * 그 필드가 이번 실험의 대상이 아닌지는 사람이 판단한다.
     */
    private void checkInputBindingCoverage(AssetContract c, List<RegistrationIssue> issues) {
        Set<String> uncovered = new TreeSet<>(SimulationConfigFields.all());
        uncovered.removeAll(Set.copyOf(c.boundInputFields()));
        if (!uncovered.isEmpty()) {
            issues.add(new RegistrationIssue(RegistrationIssue.Severity.REPORT,
                    "checkInputBindingCoverage",
                    "계약이 덮지 않는 실행 입력 필드 " + uncovered.size() + "개: " + uncovered));
        }
    }

    private List<String> allStrings(AssetContract c) {
        List<String> values = new ArrayList<>(
                List.of(String.valueOf(c.assetId()), String.valueOf(c.version()),
                        String.valueOf(c.contentDigest())));
        values.addAll(c.ruleRefs());
        values.addAll(c.adapterRefs());
        values.addAll(c.evidenceRefs());
        values.addAll(c.boundInputFields());
        values.addAll(c.trialGuarantees());
        return values;
    }
}
