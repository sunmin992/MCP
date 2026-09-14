package com.wastesim.registry;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 계약이 자기 자신을 증명하지 못한다는 사실을 검사로 만든 자리.
 *
 * <p>다른 규칙들은 계약 안의 일관성을 보지만 {@code checkInputBindingCoverage}만
 * 계약 <b>밖</b>을 본다 — 실제 입력 필드를 열거해 계약이 덮지 않는 것을 보고한다.
 */
class RegistrationValidatorTest {

    private static final Set<String> ADAPTERS = Set.of("jangnyang-adapter");

    private static AssetContract.Builder ok() {
        return AssetContract.builder()
                .assetId("jangnyang-simulator")
                .version("v4")
                .contentDigest("sha256:0000")
                .status(AssetContract.Status.PROPOSED)
                .adapterRefs(List.of("jangnyang-adapter"))
                .ruleRefs(List.of(JangnyangRules.TRAFFIC_APPLY))
                .boundInputFields(SimulationConfigFields.all());
    }

    private static List<RegistrationIssue> validate(AssetContract contract) {
        return new RegistrationValidator()
                .validate(contract, JangnyangRules.registry(), ADAPTERS);
    }

    private static List<String> rulesOf(List<RegistrationIssue> issues,
                                        RegistrationIssue.Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity)
                .map(RegistrationIssue::rule).sorted().toList();
    }

    @Test
    void 온전한_계약은_아무것도_걸리지_않는다() {
        assertEquals(List.of(), validate(ok().build()));
    }

    @Test
    void 미치환_placeholder는_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(ok().version("<verified-version>").build());
        assertEquals(List.of("rejectUnresolvedPlaceholders"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 미등록_규칙_ID는_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(ok().ruleRefs(List.of("없는규칙")).build());
        assertEquals(List.of("rejectUnknownRuleRefs"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 미등록_어댑터는_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(ok().adapterRefs(List.of("없는어댑터")).build());
        assertEquals(List.of("rejectUnknownAdapterRefs"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 증거_없는_자산은_verified가_될_수_없다() {
        List<RegistrationIssue> issues = validate(
                ok().status(AssetContract.Status.VERIFIED).evidenceRefs(List.of()).build());
        assertEquals(List.of("requireEvidenceBeforeVerified"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 증거가_있으면_verified가_된다() {
        assertEquals(List.of(), validate(ok()
                .status(AssetContract.Status.VERIFIED)
                .evidenceRefs(List.of("docs/research/s1-ses-extraction/reference-ses.json"))
                .build()));
    }

    @Test
    void 덮지_못한_입력_필드는_거부가_아니라_보고다() {
        List<RegistrationIssue> issues = validate(ok()
                .boundInputFields(List.of("days")).build());

        assertEquals(List.of(), rulesOf(issues, RegistrationIssue.Severity.REJECT));
        assertEquals(List.of("checkInputBindingCoverage"),
                rulesOf(issues, RegistrationIssue.Severity.REPORT));
        assertTrue(issues.get(0).detail().contains("seeds"), issues.get(0).detail());
    }

    @Test
    void boundInputFields에_숨은_placeholder도_등록을_거부한다() {
        List<RegistrationIssue> issues = validate(
                ok().boundInputFields(List.of("<actual-input-json-pointer>")).build());
        assertEquals(List.of("rejectUnresolvedPlaceholders"),
                rulesOf(issues, RegistrationIssue.Severity.REJECT));
    }

    @Test
    void 실제_입력_필드를_계약이_아니라_코드에서_읽는다() {
        Set<String> fields = SimulationConfigFields.all();
        assertTrue(fields.contains("days"));
        assertTrue(fields.contains("trafficEnabled"));
        assertTrue(fields.size() >= 40,
                "SimulationConfig의 세터가 40개 미만일 리 없다: " + fields.size());
    }
}
