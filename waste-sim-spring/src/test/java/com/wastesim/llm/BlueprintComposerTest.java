package com.wastesim.llm;

import com.wastesim.subtask.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 요청 하나가 설계도로 가는 전체 흐름.
 *
 * <p>LLM을 실제로 부르지 않는다. {@link RequestInterpreter}를 스텁으로 두고 흐름만
 * 검증한다 — TMAP·OSRM에 쓴 방식과 같다. 실제 호출은 별도 통합 확인으로 분리한다.
 */
class BlueprintComposerTest {

    private static SubtaskSessionService sessions() {
        return TestSubtaskFixtures.service(new JangnyangSubtaskCatalog());
    }

    /** 고정 응답을 내는 스텁. */
    private static RequestInterpreter stub(RequestExtraction fixed) {
        return (request, fields) -> fixed;
    }

    /** 항상 실패하는 스텁 — 서비스 장애를 흉내낸다. */
    private static RequestInterpreter failing() {
        return (request, fields) -> { throw new InterpreterException("서비스 없음"); };
    }

    /** 거부 사유가 있으면 세션을 만들지 않고 끝낸다. */
    @Test
    void refusedRequestDoesNotStartASession() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc,
                stub(new RequestExtraction(List.of(), "부산", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s1", "부산 시뮬레이터 만들어 줘");

        assertFalse(o.verdict().feasible());
        assertEquals(FeasibilityVerdict.Reason.OUT_OF_REGION, o.verdict().reason());
        assertNull(svc.activeSession("s1"), "거부한 요청으로 세션을 만들면 안 된다");
    }

    /** 인용이 확인된 값만 세션에 들어가고, 출처가 LLM_NORMALIZED로 남는다. */
    @Test
    void onlyVerifiedValuesEnterTheSessionAndAreMarkedAsLlm() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(
                        new ExtractedValue("numBuildings", 26, "26개 동"),
                        new ExtractedValue("seeds", 10, "10회 반복")),   // 요청에 없다
                        "장량동", null, null)));

        composer.compose("s2", "장량동 26개 동으로 돌려줘");

        JangnyangSubtaskSession session = svc.activeSession("s2");
        assertNotNull(session);
        boolean anyLlm = session.answers().values().stream()
                .anyMatch(a -> a.source() == SubtaskAnswerSource.LLM_NORMALIZED);
        assertTrue(anyLlm, "LLM이 채운 값이 원장에 그 출처로 남아야 한다");
    }

    /** 인용을 확인하지 못한 필드는 되묻기 목록에 있어야 한다. */
    @Test
    void unverifiedValuesGoToMustAsk() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(
                        new ExtractedValue("seeds", 10, "10회 반복")),   // 요청에 없다
                        "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s3", "장량동 시뮬레이터");
        assertTrue(o.mustAsk().contains("seeds"),
                "지어낸 값을 버렸으면 그 필드를 물어야 한다: " + o.mustAsk());
    }

    /** 근거 없는 셋은 언제나 되묻기 목록에 있다. */
    @Test
    void unbasedFieldsAreAlwaysAsked() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(), "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s4", "장량동 시뮬레이터 만들어 줘");
        for (String f : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule"}) {
            assertTrue(o.mustAsk().contains(f), f + "를 묻지 않으면 조용한 가정이 된다");
        }
    }

    /**
     * <b>LLM이 죽으면 기본값으로 채우지 않는다.</b> 문항 흐름으로 넘기고 사용자에게 알린다.
     */
    @Test
    void llmFailureFallsBackLoudlyNotSilently() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, failing());

        BlueprintComposer.Outcome o = composer.compose("s5", "장량동 시뮬레이터 만들어 줘");

        assertTrue(o.verdict().feasible(), "LLM 장애는 요청이 불가능하다는 뜻이 아니다");
        assertTrue(o.usedFallback());
        assertNotNull(o.fallbackNotice(), "조용히 문항으로 넘기면 사용자가 이유를 모른다");
        assertNotNull(svc.activeSession("s5"), "폴백은 문항 흐름으로 진행하는 것이다");
        assertTrue(svc.activeSession("s5").answers().isEmpty(),
                "LLM이 죽었는데 값이 채워져 있으면 어디서 온 값인지 알 수 없다");
    }

    /** 스키마가 깨진 추출은 전체를 버린다 — 부분 파싱 금지. */
    @Test
    void malformedExtractionIsDiscardedWholesale() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc, stub(
                new RequestExtraction(List.of(
                        new ExtractedValue(null, 26, "26개 동")),   // 필드 이름이 없다
                        "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s6", "장량동 26개 동");
        assertTrue(o.usedFallback(), "반쯤 읽은 결과를 쓰면 무엇이 빠졌는지 알 수 없다");
        assertTrue(svc.activeSession("s6").answers().isEmpty());
    }

    /**
     * {@link GapResolver}가 낸 출처 미확인 필드 목록이 {@link BlueprintComposer.Outcome}까지
     * 살아서 도착해야 한다 — 그러지 않으면 조립 지점에서 그 정보가 사라진다.
     *
     * <p>폴백에서는 반드시 비어 있어야 한다: 폴백은 아무것도 자동으로 채우지 않으므로
     * "채웠지만 출처를 확인 안 한 것"이 있을 수 없다.
     */
    @Test
    void outcomeReportsUnverifiedBasisFields() {
        SubtaskSessionService svc = sessions();
        BlueprintComposer composer = new BlueprintComposer(svc,
                stub(new RequestExtraction(List.of(), "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s7", "장량동 시뮬레이터 만들어 줘");
        assertFalse(o.unverifiedFields().isEmpty(), "days 같은 UNVERIFIED 근거 필드가 있어야 한다");
        assertTrue(o.unverifiedFields().contains("days"), "" + o.unverifiedFields());

        SubtaskSessionService svcFallback = sessions();
        BlueprintComposer fallbackComposer = new BlueprintComposer(svcFallback, failing());
        BlueprintComposer.Outcome fallback = fallbackComposer.compose("s8", "장량동 시뮬레이터 만들어 줘");
        assertTrue(fallback.unverifiedFields().isEmpty(),
                "폴백은 아무것도 채우지 않으므로 출처 미확인 필드도 없어야 한다");
    }
}
