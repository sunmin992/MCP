package com.wastesim.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 구조를 바꾸는 답변이 들어왔을 때 이미 받은 값들의 유효성을 다시 따지는가.
 * 이것이 이 계획의 본체다 — 지금 코드에는 이 자리가 없다.
 */
class LedgerRecalculatorTest {

    private static final Instant T = Instant.parse("2026-09-13T00:00:00Z");

    private static ParameterDecision confirmed(String id, String param, Object value) {
        return new ParameterDecision(id, param, DecisionState.CONFIRMED,
                value, null, value, null,
                new ValueSource("user_explicit", "ST-01", null, T),
                null, List.of(), null, null, T);
    }

    /**
     * changedParameterId 자신에게 "이전 값 → 새 값"이 실제로 이미 쌓여 있는 상태를
     * 만든다. 운영 경로에서는 {@code recordDecision}이 {@code onAnswerChanged}보다
     * 먼저 새 결정을 원장에 쌓아 두므로, "값이 바뀌었다"를 확인하려면 이 헬퍼처럼
     * 이전 값과 새 값 둘 다 이미 원장에 있어야 한다 — 그래야 도착과 변경을 가르는
     * 새 판정을 이 테스트에서도 그대로 재현할 수 있다.
     */
    private static void seedGenuineChange(ParameterLedger ledger, String parameterId,
                                          Object oldValue, Object newValue) {
        ledger.append(confirmed(parameterId + "#1", parameterId, oldValue));
        ledger.append(confirmed(parameterId + "#2", parameterId, newValue));
    }

    private LedgerRecalculator recalculator() {
        return new LedgerRecalculator(
                JangnyangRules.registry(),
                Map.of("sim::trafficProfileId", JangnyangRules.TRAFFIC_APPLY),
                Map.of("sim::trafficMode", List.of("sim::trafficProfileId")));
    }

    @Test
    void 상위_값이_바뀌면_종속_결정이_낡는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));
        // trafficMode가 처음 답해진 것이 아니라 이전 값(NONE)에서 실제로 바뀌었다 —
        // "도착"이 아니라 "변경"이어야 종속 결정이 낡는다는 새 규칙이 겨냥하는 경우다.
        seedGenuineChange(ledger, "sim::trafficMode", "NONE", "APPLY");

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.STALE, now.state());
        assertEquals("sim::trafficMode", now.supersededBy());
        assertEquals("upstream_value_changed", now.blockingReason());
    }

    @Test
    void 낡은_값은_지워지지_않고_이력에_남는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));
        seedGenuineChange(ledger, "sim::trafficMode", "NONE", "APPLY");

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        assertEquals("P1", ledger.history("sim::trafficProfileId").get(0).normalizedValue());
        assertEquals("P1", ledger.current("sim::trafficProfileId").normalizedValue());
    }

    @Test
    void 가지가_살아나면_새_필수값이_미해결로_드러난다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.UNRESOLVED, now.state());
        assertEquals("required_value_unresolved", now.blockingReason());
    }

    @Test
    void 가지가_죽으면_해당없음으로_확정되고_근거가_남는다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "IGNORE"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.DEFAULTED, now.state());
        assertEquals("not_applicable_by_rule", now.source().type());
        assertEquals(JangnyangRules.TRAFFIC_APPLY, now.source().reference());
        assertTrue(now.state().executable());
    }

    @Test
    void 조건이_모름이면_필수값은_미해결로_남는다() {
        ParameterLedger ledger = new ParameterLedger();

        recalculator().onAnswerChanged(ledger, "sim::trafficMode", Map.of());

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.UNRESOLVED, now.state());
        assertEquals("activation_unknown", now.blockingReason());
    }

    @Test
    void 조건이_모름이어도_이미_실행_가능한_값은_그대로_선다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));

        // trafficMode를 아예 답하지 않은 채로 넘긴다 — RuleRegistry.fieldEquals가
        // UNKNOWN을 돌려주는 바로 그 조건이다. 사용자는 trafficProfileId에 이미 정직하게
        // 답했으므로, 그 값을 지우면 안 된다(활성 여부를 아직 모르는 것과 값이 없는
        // 것은 다른 사실이다).
        recalculator().onAnswerChanged(ledger, "sim::trafficProfileId", Map.of());

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.CONFIRMED, now.state(), "이미 받은 답이 UNKNOWN 통과에 지워졌다");
        assertEquals("P1", now.normalizedValue());
        assertEquals(1, ledger.history("sim::trafficProfileId").size(),
                "값이 그대로라면 새 레코드가 쌓이지 않아야 한다");
    }

    @Test
    void 상위_값이_처음_도착한_것은_변경이_아니라_종속_결정을_낡히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));
        // trafficMode 자신은 이번이 첫 결정이다 — 이전 값이 아예 없다. "도착"을
        // "변경"으로 잘못 읽으면 사용자가 이미 정직하게 낸 trafficProfileId 답이
        // 활성 여부를 알기도 전에 STALE로 지워진다.
        ledger.append(confirmed("sim::trafficMode#1", "sim::trafficMode", "APPLY"));

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.CONFIRMED, now.state(),
                "첫 답이 변경으로 오인돼 이미 받은 답이 낡았다");
        assertEquals(1, ledger.history("sim::trafficProfileId").size());
    }

    @Test
    void 상위_값이_같은_값으로_다시_답해도_종속_결정을_낡히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));
        // 이전 값과 새 값이 둘 다 APPLY다 — 같은 값으로 다시 답한 것은 아무것도
        // 바꾸지 않았다.
        seedGenuineChange(ledger, "sim::trafficMode", "APPLY", "APPLY");

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        ParameterDecision now = ledger.current("sim::trafficProfileId");
        assertEquals(DecisionState.CONFIRMED, now.state(),
                "같은 값으로 재확인한 것이 변경으로 오인돼 이미 받은 답이 낡았다");
        assertEquals(1, ledger.history("sim::trafficProfileId").size());
    }

    @Test
    void 이미_같은_상태면_같은_레코드를_거듭_쌓지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        LedgerRecalculator r = recalculator();

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));
        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));

        assertEquals(1, ledger.history("sim::trafficProfileId").size());
    }

    @Test
    void 바뀐_결과들을_돌려준다() {
        ParameterLedger ledger = new ParameterLedger();
        List<ParameterDecision> changed = recalculator()
                .onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "APPLY"));

        assertEquals(1, changed.size());
        assertEquals("sim::trafficProfileId", changed.get(0).parameterId());
    }

    @Test
    void 낡힌_매개변수는_2단계에서_다시_건드리지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::trafficProfileId#1", "sim::trafficProfileId", "P1"));
        seedGenuineChange(ledger, "sim::trafficMode", "NONE", "APPLY");

        recalculator().onAnswerChanged(ledger, "sim::trafficMode",
                Map.of("trafficMode", "APPLY"));

        // 이 호출에서 이 매개변수에 대해 정확히 레코드 하나만 쌓여야 한다(1 -> 2) —
        // 2단계가 같은 매개변수를 또 건드려 UNRESOLVED를 덧쌓으면(1 -> 3) 이 검사가 깨진다.
        assertEquals(2, ledger.history("sim::trafficProfileId").size());
        assertEquals(DecisionState.STALE, ledger.current("sim::trafficProfileId").state());
    }

    @Test
    void 규칙이_만든_자리표시자는_같은_답을_반복해도_다시_낡지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        LedgerRecalculator r = recalculator();

        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));
        r.onAnswerChanged(ledger, "sim::trafficMode", Map.of("trafficMode", "IGNORE"));

        // 1단계의 자리표시자 가드가 사라지면 두 번째 호출이 이 자리표시자를 STALE로
        // 덧쌓아 이력이 늘고 current()도 STALE로 바뀐다.
        assertEquals(1, ledger.history("sim::trafficProfileId").size());
        assertEquals(DecisionState.DEFAULTED, ledger.current("sim::trafficProfileId").state());
    }

    // ---- 출처 만료 ----

    @Test
    void 허용_나이를_넘긴_출처는_낡는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::routeTravelMinutes#1", "sim::routeTravelMinutes", 12));

        recalculator().onSourceExpired(ledger, java.time.Duration.ofDays(1),
                T.plus(java.time.Duration.ofDays(30)));

        ParameterDecision now = ledger.current("sim::routeTravelMinutes");
        assertEquals(DecisionState.STALE, now.state());
        assertEquals("source_expired", now.blockingReason());
        assertTrue(now.supersededBy().startsWith("source_expired"), now.supersededBy());
        assertEquals(12, ledger.history("sim::routeTravelMinutes").get(0).normalizedValue());
    }

    @Test
    void 아직_젊은_출처는_낡지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::routeTravelMinutes#1", "sim::routeTravelMinutes", 12));

        assertEquals(List.of(), recalculator().onSourceExpired(ledger,
                java.time.Duration.ofDays(30), T.plus(java.time.Duration.ofDays(1))));
        assertEquals(DecisionState.CONFIRMED,
                ledger.current("sim::routeTravelMinutes").state());
    }

    @Test
    void 취득_시각이_없는_출처는_만료로_읽지_않는다() {
        // 시각의 부재는 오래됐다는 증거가 아니다. 그렇게 읽으면 규칙이 만든 "해당 없음"
        // 자리표시자처럼 시각을 남기지 않는 값이 대신 낡았다는 판정을 받는다.
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::days#1", "sim::days",
                DecisionState.CONFIRMED, 7, null, 7, null,
                new ValueSource("user_explicit", "ST-01", null, null),
                null, List.of(), null, null, T));

        assertEquals(List.of(), recalculator().onSourceExpired(ledger,
                java.time.Duration.ofSeconds(1), T.plus(java.time.Duration.ofDays(365))));
        assertEquals(DecisionState.CONFIRMED, ledger.current("sim::days").state());
    }

    @Test
    void 실행할_수_없는_결정은_만료로_다시_낡히지_않는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(new ParameterDecision("sim::days#1", "sim::days",
                DecisionState.UNRESOLVED, null, null, null, null, null, null,
                List.of(), "required_value_unresolved", null, T));

        assertEquals(List.of(), recalculator().onSourceExpired(ledger,
                java.time.Duration.ofSeconds(1), T.plus(java.time.Duration.ofDays(1))));
        assertEquals(1, ledger.history("sim::days").size());
    }

    // ---- 세트 버전 변경 ----

    @Test
    void 세트_버전이_바뀌면_실행_가능한_결정이_전부_낡는다() {
        ParameterLedger ledger = new ParameterLedger();
        ledger.append(confirmed("sim::days#1", "sim::days", 7));
        ledger.append(confirmed("sim::seeds#1", "sim::seeds", 3));

        assertEquals(2, recalculator().onSetVersionChanged(ledger, "v6", T).size());
        for (String id : List.of("sim::days", "sim::seeds")) {
            assertEquals(DecisionState.STALE, ledger.current(id).state());
            assertEquals("set_version_changed", ledger.current(id).blockingReason());
            assertEquals("v6", ledger.current(id).supersededBy());
        }
    }

    @Test
    void 세트_버전이_없으면_재계산을_시작하지_않는다() {
        assertThrows(IllegalArgumentException.class,
                () -> recalculator().onSetVersionChanged(new ParameterLedger(), " ", T));
    }
}
