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

    /**
     * 조립기(BlueprintComposer)와 세션 서비스가 <b>같은 카탈로그 인스턴스</b>를 보게
     * 한다 — 둘이 각자 새 카탈로그를 만들면 세트 내용은 같아도 서로 다른 객체가 되어
     * "세션과 조립기가 같은 세트를 본다"는 전제가 테스트에서 깨질 수 있다.
     */
    private static SubtaskSessionService sessions(JangnyangSubtaskCatalog catalog) {
        return TestSubtaskFixtures.service(catalog);
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
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog,
                stub(new RequestExtraction(List.of(), "부산", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s1", "부산 시뮬레이터 만들어 줘");

        assertFalse(o.verdict().feasible());
        assertEquals(FeasibilityVerdict.Reason.OUT_OF_REGION, o.verdict().reason());
        assertNull(svc.activeSession("s1"), "거부한 요청으로 세션을 만들면 안 된다");
    }

    /**
     * 인용이 확인된 값만 세션에 들어가고, 출처가 LLM_NORMALIZED로 남는다.
     *
     * <p>검증된 값이 도착했는지만 보면 충분하지 않다 — 검증되지 않은 값이 함께 들어와도
     * 그 확인은 통과해 버리기 때문이다. 그래서 이 테스트는 양방향을 모두 본다: 검증된
     * 값은 들어오고, 검증되지 않은 값은 들어오지 않는다.
     */
    @Test
    void onlyVerifiedValuesEnterTheSessionAndAreMarkedAsLlm() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog, stub(
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

        // 원장은 필드명이 아니라 서브태스크 id로 키가 잡혀 있으므로, "seeds"가 가리키는
        // id를 먼저 찾아야 한다 — 정의는 세션이 아니라 정의(definition)가 갖고 있다.
        JangnyangSubtaskDefinition def = svc.definitionOf(session);
        JangnyangSubtask seedsSubtask = def.byAnswerField("seeds");
        assertNotNull(seedsSubtask, "테스트 픽스처에 seeds 서브태스크가 없다");
        assertFalse(session.answers().containsKey(seedsSubtask.id()),
                "인용을 확인하지 못한 값이 원장에 들어가면, 지어낸 값이 시뮬레이션 입력이 될 수 있다: "
                        + session.answers().keySet());
    }

    /** 인용을 확인하지 못한 필드는 되묻기 목록에 있어야 한다. */
    @Test
    void unverifiedValuesGoToMustAsk() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog, stub(
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
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog, stub(
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
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog, failing());

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
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog, stub(
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
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog,
                stub(new RequestExtraction(List.of(), "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s7", "장량동 시뮬레이터 만들어 줘");
        assertFalse(o.unverifiedFields().isEmpty(), "days 같은 UNVERIFIED 근거 필드가 있어야 한다");
        assertTrue(o.unverifiedFields().contains("days"), "" + o.unverifiedFields());

        JangnyangSubtaskCatalog catalogFallback = new JangnyangSubtaskCatalog();
        SubtaskSessionService svcFallback = sessions(catalogFallback);
        BlueprintComposer fallbackComposer = new BlueprintComposer(svcFallback, catalogFallback, failing());
        BlueprintComposer.Outcome fallback = fallbackComposer.compose("s8", "장량동 시뮬레이터 만들어 줘");
        assertTrue(fallback.unverifiedFields().isEmpty(),
                "폴백은 아무것도 채우지 않으므로 출처 미확인 필드도 없어야 한다");
    }

    /**
     * {@link GapResolver}가 낸 자동 채움 값이 세션 원장에 <b>실제로</b> 들어간다.
     *
     * <p>결과(Outcome)만 보면 충분하지 않다 — 결과에는 값이 실려도 세션에 제출되지
     * 않으면, 다음 답변 제출에서 세션은 여전히 그 필드를 묻는다(이 클래스 상단 javadoc이
     * 말하는 "같은 판단이 두 곳에 사는 문제"). 근거 값이 null인 필드("해당없음"으로
     * 확정)도 건너뛰지 않고 들어가야 한다 — 건너뛰면 그 필드는 영원히 채워지지도
     * 물어지지도 않는다.
     */
    @Test
    void autoFilledValuesEnterTheSession() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog,
                stub(new RequestExtraction(List.of(), "장량동", null, null)));

        composer.compose("s10", "장량동 시뮬레이터 만들어 줘");

        JangnyangSubtaskSession session = svc.activeSession("s10");
        assertNotNull(session);
        assertFalse(session.answers().isEmpty(), "자동 채움 값이 하나도 세션에 들어가지 않았다");

        JangnyangSubtaskDefinition def = svc.definitionOf(session);
        JangnyangSubtask trafficProfile = def.byAnswerField("trafficProfileId");
        assertNotNull(trafficProfile, "테스트 픽스처에 trafficProfileId 서브태스크가 없다");
        JangnyangSubtaskAnswer answer = session.answers().get(trafficProfile.id());
        assertNotNull(answer, "근거가 있는 필드(trafficProfileId)가 자동으로 채워지지 않았다");
        assertEquals(SubtaskAnswerSource.SERVER_DEFAULT, answer.source(),
                "서버가 채운 값의 출처는 SERVER_DEFAULT여야 한다: " + answer);

        // 근거 값이 null인 필드도 세션에 들어가야 한다 — "해당없음"으로 확정된 것이지
        // 채우지 못한 것이 아니다.
        JangnyangSubtask routeSequence = def.byAnswerField("routeSequence");
        assertNotNull(routeSequence, "테스트 픽스처에 routeSequence 서브태스크가 없다");
        JangnyangSubtaskAnswer naAnswer = session.answers().get(routeSequence.id());
        assertNotNull(naAnswer,
                "근거 값이 null인 필드가 자동 채움에서 빠졌다 — 해당없음 확정을 건너뛰면 안 된다");
        assertEquals(JangnyangSubtaskValidator.NOT_APPLICABLE, naAnswer.value());
    }

    /**
     * {@code appliedDefaults}의 각 항목은 근거 문장을 지녀야 한다.
     *
     * <p>근거 없이 "채운 사실"만 남으면 사용자는 자기가 답하지 않은 값이 어디서 왔는지
     * 되짚을 수 없다.
     */
    @Test
    void appliedDefaultsCarryTheirReason() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog,
                stub(new RequestExtraction(List.of(), "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s11", "장량동 시뮬레이터 만들어 줘");

        assertFalse(o.appliedDefaults().isEmpty(), "채운 값이 있는데 appliedDefaults가 비어 있다");
        assertTrue(o.appliedDefaults().stream()
                        .anyMatch(d -> d.reason() != null && !d.reason().isBlank()),
                "근거 문장이 없으면 사용자가 답하지 않은 값의 출처를 되짚을 수 없다: "
                        + o.appliedDefaults());
    }

    /**
     * 근거가 없는(NONE) 세 필드는 자동 채움 대상이 아니다.
     *
     * <p>이 셋은 되묻기 목록에 있어야 하고, 동시에 세션 원장에는 없어야 한다 — 채우면
     * 근거 없는 값이 조용한 가정으로 흘러든다.
     */
    @Test
    void unbasedFieldsAreNeverAutoFilled() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer composer = new BlueprintComposer(svc, catalog,
                stub(new RequestExtraction(List.of(), "장량동", null, null)));

        BlueprintComposer.Outcome o = composer.compose("s12", "장량동 시뮬레이터 만들어 줘");

        JangnyangSubtaskSession session = svc.activeSession("s12");
        assertNotNull(session);
        JangnyangSubtaskDefinition def = svc.definitionOf(session);
        for (String field : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule"}) {
            assertTrue(o.mustAsk().contains(field),
                    field + "는 근거가 없으므로 되물어야 한다: " + o.mustAsk());
            JangnyangSubtask st = def.byAnswerField(field);
            assertNotNull(st, "테스트 픽스처에 " + field + " 서브태스크가 없다");
            assertFalse(session.answers().containsKey(st.id()),
                    field + "는 근거가 없는데 세션에 값이 들어갔다 — 조용한 가정이 된다: "
                            + session.answers().keySet());
        }
    }

    /**
     * 거부된 요청 하나가 이미 진행 중인 세션을 지우면 안 된다.
     *
     * <p>세션을 먼저 시작해서 답을 하나 넣어 두고 나서 범위 밖 문장을 보낸다. 거부된
     * 문장이 세션을 건드리면, 사용자가 20문항을 답해 온 뒤 범위 밖 문장 하나를 보내는
     * 순간 그 답을 모두 잃는다.
     */
    @Test
    void refusedRequestLeavesAnExistingSessionIntact() {
        JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
        SubtaskSessionService svc = sessions(catalog);
        BlueprintComposer refusing = new BlueprintComposer(svc, catalog,
                stub(new RequestExtraction(List.of(), "부산", null, null)));

        svc.start("s9");
        JangnyangSubtaskSession before = svc.activeSession("s9");
        JangnyangSubtaskDefinition def = svc.definitionOf(before);
        JangnyangSubtask first = before.nextSubtask(def, svc.checker());
        assertNotNull(first, "테스트 픽스처의 첫 질문을 찾지 못했다");
        svc.submit("s9", first.id(), "민원이 가장 적은 수거 시각 찾기", null,
                SubtaskAnswerSource.USER_DIRECT);

        JangnyangSubtaskAnswer answered = svc.activeSession("s9").answers().get(first.id());
        assertNotNull(answered, "테스트 준비 단계에서 답이 들어가지 않았다");

        BlueprintComposer.Outcome o = refusing.compose("s9", "부산 시뮬레이터 만들어 줘");
        assertFalse(o.verdict().feasible());

        JangnyangSubtaskSession after = svc.activeSession("s9");
        assertNotNull(after, "거부된 요청이 진행 중인 세션 자체를 지웠다");
        assertEquals(answered, after.answers().get(first.id()),
                "거부된 문장 하나가 이미 답한 값을 지우면 안 된다 — 진행 중인 작업을 잃는다");
    }
}
