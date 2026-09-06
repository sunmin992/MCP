package com.wastesim.subtask;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 답하지 않은 필드를 근거로 가른다.
 *
 * <p>이 클래스가 "근거 유무로 가른다"를 실제로 수행하는 곳이다. 근거 있는 값은 출처와 함께
 * 채우고, 근거 없는 값과 실험 목적은 되묻기 목록으로 내린다.
 */
class GapResolverTest {

    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    /** 아무것도 답하지 않은 상태에서 갈라 본다. */
    private static GapResolver.Resolution resolveNothing() {
        return GapResolver.resolve(v4(), Set.of());
    }

    /** 근거 없는 셋과 실험 목적은 반드시 되묻기 목록에 있어야 한다. */
    @Test
    void unbasedAndIntentFieldsGoToMustAsk() {
        GapResolver.Resolution r = resolveNothing();
        for (String f : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule", "simulationGoal", "scenarioType"}) {
            assertTrue(r.mustAsk().contains(f), f + "를 묻지 않으면 조용한 가정이 된다");
        }
    }

    /** 근거 없는 필드를 자동 채움에 넣으면 안 된다. */
    @Test
    void unbasedFieldsAreNeverAutoFilled() {
        GapResolver.Resolution r = resolveNothing();
        for (String f : new String[]{"serviceMinutesPerSite", "intraZoneTravelMinutes",
                "zoneAssignmentRule"}) {
            assertFalse(r.autoFilled().containsKey(f),
                    f + "는 근거가 없으므로 채울 값이 없다");
        }
    }

    /** 출처가 있는 값은 채우고 근거를 함께 기록해야 한다. */
    @Test
    void citedFieldsAreFilledWithTheirSource() {
        GapResolver.Resolution r = resolveNothing();
        assertTrue(r.autoFilled().containsKey("trafficProfileId"));
        assertTrue(r.defaults().stream()
                        .anyMatch(d -> "trafficProfileId".equals(d.field())
                                && d.reason() != null && d.reason().contains("TMAP")),
                "출처 없이 채우면 다음 사람이 값의 근거를 물을 곳이 없다: " + r.defaults());
    }

    /** 출처 미확인 필드는 채우되 목록에 남아야 한다 — 결과에 표시를 붙이기 위한 것이다. */
    @Test
    void modelDefaultFieldsAreFilledButListed() {
        GapResolver.Resolution r = resolveNothing();
        assertTrue(r.autoFilled().containsKey("days"), "채우지 않으면 매번 묻게 된다");
        assertTrue(r.modelDefaultFields().contains("days"),
                "출처 미확인인데 표시하지 않으면 확인된 값과 구별되지 않는다");
    }

    /** 이미 답한 필드는 어느 목록에도 들어가지 않는다. */
    @Test
    void answeredFieldsAreLeftAlone() {
        JangnyangSubtaskDefinition def = v4();
        String daysId = def.subtasks().stream()
                .filter(s -> "days".equals(s.answerField()))
                .findFirst().orElseThrow().id();

        GapResolver.Resolution r = GapResolver.resolve(def, Set.of(daysId));
        assertFalse(r.autoFilled().containsKey("days"), "답한 값을 덮으면 안 된다");
        assertFalse(r.mustAsk().contains("days"));
        assertFalse(r.modelDefaultFields().contains("days"));
    }

    /** 선언이 없는 필드(v3)는 전부 되묻기로 간다 — 모르는 것을 채우지 않는다. */
    @Test
    void undeclaredFieldsAllGoToMustAsk() {
        GapResolver.Resolution r = GapResolver.resolve(
                new JangnyangSubtaskCatalog().byVersion(3), Set.of());
        assertEquals(33, r.mustAsk().size(),
                "선언 없는 세트에서 무언가 자동으로 채워지면 근거 없이 채운 것이다");
        assertEquals(0, r.autoFilled().size());
    }

    /**
     * "해당없음"은 값이 없는 것이 아니라 <b>확정된 결과</b>다 — routeSequence는
     * MODEL_DEFAULT 근거에 value가 null로 선언되어 있다("자동 생성 순서를 쓰려면
     * 해당 없음"). 이를 되묻기로 보내면 답할 수 없는 질문을 반복하는 것이고,
     * 자동 채움에서 빼 버리면 "왜 비어 있는가"가 감사 기록({@link AppliedDefault})에
     * 남지 않는다. 그래서 null 값인 채로 autoFilled의 키로 남아야 한다.
     *
     * <p>{@code containsKey}로 확인하는 이유: {@code get(...)}이 null을 반환하는 것은
     * 키가 없어도 마찬가지이므로, {@code assertNull(r.autoFilled().get(...))}만으로는
     * 키가 아예 없는 경우도 통과해 버려 증명이 되지 않는다.
     */
    @Test
    void notApplicableFieldsAreFilledWithNull() {
        GapResolver.Resolution r = resolveNothing();
        assertTrue(r.autoFilled().containsKey("routeSequence"),
                "해당없음도 확정된 값이다 — 키 자체가 없으면 왜 비었는지 감사 기록에 남지 않는다");
        assertNull(r.autoFilled().get("routeSequence"));
        assertFalse(r.mustAsk().contains("routeSequence"),
                "해당없음으로 채워졌으므로 다시 물으면 안 된다");
    }

    /**
     * 부르는 쪽이 <b>반드시 물으라</b>고 지정한 필드는 근거가 있어도 채우지 않는다.
     *
     * <p>"장량동 26개 동으로 한 달 돌려줘"처럼 실행 동사만 있고 시각이 없는 요청에서
     * 필요하다. {@code collectionTime}은 근거가 {@code MODEL_DEFAULT}라 그냥 두면 12:00으로
     * 자동으로 채워지고 넘어간다 — 그러면 사용자가 실행을 요청했는데 정작 자기가 정하려던
     * 시각은 물어보지도 않은 채 기본값으로 돈다.
     */
    @Test
    void alwaysAskFieldIsAskedEvenThoughItHasADefault() {
        GapResolver.Resolution r = GapResolver.resolve(v4(), Set.of(), Set.of("collectionTime"));

        assertTrue(r.mustAsk().contains("collectionTime"),
                "지정한 필드를 묻지 않으면 부르는 쪽의 요구가 조용히 무시된다");
        assertFalse(r.autoFilled().containsKey("collectionTime"),
                "물을 필드를 동시에 채우면 사용자의 답이 기본값에 덮인다");
        assertFalse(r.modelDefaultFields().contains("collectionTime"),
                "채우지 않은 값을 기본값으로 세면 결과 표시가 사실과 달라진다");
    }

    /** 지정은 그 필드에만 닿는다 — 나머지 자동 채움은 그대로여야 한다. */
    @Test
    void alwaysAskDoesNotDisturbOtherFields() {
        GapResolver.Resolution before = resolveNothing();
        GapResolver.Resolution after = GapResolver.resolve(v4(), Set.of(), Set.of("collectionTime"));

        assertEquals(before.autoFilled().size() - 1, after.autoFilled().size(),
                "지정한 하나 말고 다른 필드까지 되묻기로 내려가면 안 된다");
        assertTrue(after.autoFilled().containsKey("numBuildings"));
    }

    /** 기존 2인자 호출은 아무것도 강제하지 않는 것과 같아야 한다. */
    @Test
    void twoArgResolveForcesNothing() {
        assertEquals(resolveNothing().mustAsk(),
                GapResolver.resolve(v4(), Set.of(), Set.of()).mustAsk());
    }
}
