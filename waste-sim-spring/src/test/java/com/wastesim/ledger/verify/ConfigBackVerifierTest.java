package com.wastesim.ledger.verify;

import com.wastesim.ledger.DecisionState;
import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.ledger.ValueSource;
import com.wastesim.model.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 검증된 계획이 잘못된 실행 설정으로 바뀌는 오류는 계획 검증으로 잡히지 않는다 —
 * 계획 쪽은 전부 통과하기 때문이다. 역검증은 변환 자체를 의심하는 유일한 자리다.
 */
class ConfigBackVerifierTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");
    private static final Map<String, String> BINDING = Map.of("days", "sim::days");

    private static ParameterLedger ledgerWithDays(int value) {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::days#1", "sim::days", DecisionState.CONFIRMED,
                String.valueOf(value), null, value, "day",
                new ValueSource("user_explicit", "ST-02", null, T),
                null, List.of(), null, null, T));
        return ledger;
    }

    private static SimulationConfig configWithDays(int value) {
        SimulationConfig config = new SimulationConfig();
        config.setDays(value);
        return config;
    }

    @Test
    void 원장과_같은_값이면_통과한다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), ledgerWithDays(7), BINDING);
        assertTrue(r.passed(), r.blocks().toString());
    }

    @Test
    void 컴파일_결과가_한_필드라도_다르면_막는다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(14), ledgerWithDays(7), BINDING);

        assertFalse(r.passed());
        assertEquals(1, r.blocks().size());
        assertTrue(r.blocks().get(0).contains("sim::days"), r.blocks().get(0));
    }

    @Test
    void 원장에_결정이_없는_값은_막는다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), new ParameterLedger(), BINDING);

        assertFalse(r.passed());
        assertTrue(r.blocks().get(0).contains("결정이 없습니다"), r.blocks().get(0));
    }

    @Test
    void 실행할_수_없는_상태의_값은_막는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::days#1", "sim::days", DecisionState.UNRESOLVED,
                null, null, null, null, null, null, List.of(),
                "required_value_unresolved", null, T));

        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), ledger, BINDING);

        assertFalse(r.passed());
        assertTrue(r.blocks().get(0).contains("UNRESOLVED"), r.blocks().get(0));
    }

    @Test
    void 막힌_사유를_전부_모은다() {
        SimulationConfig config = new SimulationConfig();
        config.setDays(14);
        config.setNumTrucks(3);

        BackVerificationResult r = new ConfigBackVerifier().verify(config, ledgerWithDays(7),
                Map.of("days", "sim::days", "numTrucks", "sim::numTrucks"));

        assertEquals(2, r.blocks().size());
    }

    @Test
    void 원장은_확정값인데_설정에_값이_없으면_막는다() {
        // collectionDaysOfWeek는 기본값이 null인 List 필드다 — 설정하지 않으면 게터가
        // 그대로 null을 돌려주므로, 컴파일러가 값을 누락시킨 상황을 그대로 재현한다.
        SimulationConfig config = new SimulationConfig();

        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::daysOfWeek#1", "sim::daysOfWeek",
                DecisionState.CONFIRMED, "[1,3,5]", null, List.of(1, 3, 5), null,
                new ValueSource("user_explicit", "ST-02", null, T),
                null, List.of(), null, null, T));

        BackVerificationResult r = new ConfigBackVerifier().verify(config, ledger,
                Map.of("collectionDaysOfWeek", "sim::daysOfWeek"));

        assertFalse(r.passed());
        assertTrue(r.blocks().get(0).contains("sim::daysOfWeek"), r.blocks().get(0));
    }

    @Test
    void 원장에_결정이_없고_설정도_값이_없으면_막지_않는다() {
        SimulationConfig config = new SimulationConfig(); // collectionDaysOfWeek는 null 그대로

        BackVerificationResult r = new ConfigBackVerifier().verify(config, new ParameterLedger(),
                Map.of("collectionDaysOfWeek", "sim::daysOfWeek"));

        assertTrue(r.passed(), r.blocks().toString());
    }

    @Test
    void 필드에_해당하는_게터가_없으면_막는다() {
        BackVerificationResult r = new ConfigBackVerifier()
                .verify(configWithDays(7), ledgerWithDays(7),
                        Map.of("noSuchField", "sim::noSuchField"));

        assertFalse(r.passed());
        assertTrue(r.blocks().get(0).contains("noSuchField"), r.blocks().get(0));
    }

    @Test
    void is_접두사_게터를_통해_불리언_필드를_읽는다() {
        SimulationConfig config = new SimulationConfig();
        config.setTrafficEnabled(true);

        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::traffic#1", "sim::traffic",
                DecisionState.CONFIRMED, "true", null, true, null,
                new ValueSource("user_explicit", "ST-02", null, T),
                null, List.of(), null, null, T));

        BackVerificationResult r = new ConfigBackVerifier()
                .verify(config, ledger, Map.of("trafficEnabled", "sim::traffic"));

        assertTrue(r.passed(), r.blocks().toString());
    }
}
