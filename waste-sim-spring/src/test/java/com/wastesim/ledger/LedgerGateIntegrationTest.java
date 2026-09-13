package com.wastesim.ledger;

import com.wastesim.ledger.verify.BackVerificationResult;
import com.wastesim.ledger.verify.ConfigBackVerifier;
import com.wastesim.model.SimulationConfig;
import com.wastesim.subtask.BasisKind;
import com.wastesim.subtask.SubtaskAnswerSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 오류 주입과 과차단을 <b>짝으로</b> 시험한다.
 *
 * <p>차단만 늘리면 "필수값 누락 0"과 "실행 성공률"이 동시에 올라가는 착시가 생긴다.
 * 정상 구성이 막히지 않는다는 것을 같은 파일에서 지키지 않으면, 이 원장은 안전해 보이는
 * 방식으로 쓸모없어질 수 있다.
 */
class LedgerGateIntegrationTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final Map<String, String> BINDING = Map.of("days", "sim::days");

    /**
     * 조립은 운영 경로({@link AnswerDecisions})에 맡긴다 — 시험이 자기 사본으로 조립하면
     * 운영 경로가 바뀌어도 이 시험은 통과하고, 그러면 시험이 실제로 도는 것을 시험하지 않는다.
     */
    private static ParameterDecision answered(ParameterLedger ledger, String parameterId,
                                              Object value, SubtaskAnswerSource source,
                                              BasisKind basis) {
        return AnswerDecisions.fromAnswer(
                ledger.nextDecisionId(parameterId), parameterId, value, value, source, basis,
                new ValueSource("user_explicit", "ST-02", null, T),
                new Transformation("normalize-days", List.of()), T);
    }

    // ---- 과차단: 정상 구성은 막히지 않는다 ----

    @Test
    void 정상_골드_구성은_막히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.USER_DIRECT, BasisKind.NONE));

        SimulationConfig config = new SimulationConfig();
        config.setDays(7);

        BackVerificationResult r = new ConfigBackVerifier().verify(config, ledger, BINDING);
        assertTrue(r.passed(), r.blocks().toString());
        assertEquals(List.of(), ledger.blocking());
    }

    @Test
    void 모델_기본값으로_채운_값도_막히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.MODEL_DEFAULT));

        assertEquals(List.of(), ledger.blocking());
    }

    @Test
    void 비활성_가지는_묻지_않고_확정되어_실행을_막지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        new LedgerRecalculator(JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of())
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));

        assertEquals(List.of(), ledger.blocking());
    }

    // ---- 오류 주입: 알려진 오류는 전부 실행 전에 막힌다 ----

    @Test
    void 근거_없는_필수값은_실행을_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.SERVER_DEFAULT, BasisKind.NONE));

        assertEquals(1, ledger.blocking().size());
    }

    @Test
    void 컴파일_결과가_원장과_다르면_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(answered(ledger, "sim::days", 7,
                SubtaskAnswerSource.USER_DIRECT, BasisKind.NONE));

        SimulationConfig tampered = new SimulationConfig();
        tampered.setDays(14);

        assertFalse(new ConfigBackVerifier().verify(tampered, ledger, BINDING).passed());
    }

    @Test
    void 활성_조건이_켜지면_새_필수값이_드러나고_실행이_막힌다() {
        ParameterLedger ledger = new ParameterLedger();
        LedgerRecalculator r = new LedgerRecalculator(JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of());

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));
        assertEquals(List.of(), ledger.blocking());

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "APPLY"));
        assertEquals(1, ledger.blocking().size(),
                "교통을 켰으면 프로필을 다시 물어야 한다");
    }

    @Test
    void 조건이_모름이면_가지를_건너뛰지_않고_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        new LedgerRecalculator(JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of())
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of());

        assertEquals(1, ledger.blocking().size());
        assertEquals("activation_unknown", ledger.blocking().get(0).blockingReason());
    }
}
