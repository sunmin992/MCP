package com.wastesim.subtask;

import com.wastesim.model.DataQualityFlag;
import com.wastesim.model.SimulationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 출처를 확인하지 않은 기본값이 <b>결과까지</b> 따라가는가.
 *
 * <p>{@code DEFAULT_BASIS_UNVERIFIED} 플래그와 {@code GapResolver}의 목록은 만들어 뒀지만
 * 둘을 잇는 것이 없어서, 지금까지 그 플래그는 <b>아무도 발신할 수 없었다.</b> 화면 첫 줄이
 * 개수를 말할 뿐 결과 카드에는 아무 표시도 붙지 않았다 — 실행 결과만 따로 인용되면 그 숫자가
 * 무엇에 기대고 있었는지가 사라진다.
 *
 * <p>목록은 <b>조립기가 원장에서 유도한다</b>. 컴포저가 계산한 값을 컨트롤러까지 끌고 다니면
 * 같은 사실이 두 곳에 살게 되고, 이 프로젝트는 그 모양으로 이미 두 번 물렸다.
 */
class UnverifiedDefaultsInSpecTest {

    private static JangnyangSubtaskDefinition v4() {
        return new JangnyangSubtaskCatalog().byVersion(4);
    }

    private static String idOf(String field) {
        return v4().subtasks().stream()
                .filter(s -> field.equals(s.answerField()))
                .findFirst().orElseThrow().id();
    }

    /** 서버가 채운 값 중 근거가 {@code UNVERIFIED}인 것만 골라낸다. */
    @Test
    void collectsOnlyServerFilledFieldsWhoseBasisIsUnverified() {
        List<String> unverified = JangnyangScenarioBuilder.unverifiedDefaultsOf(v4(),
                java.util.Map.of(
                        idOf("days"), JangnyangSubtaskAnswer.accepted(
                                idOf("days"), "30", 30, SubtaskAnswerSource.SERVER_DEFAULT),
                        idOf("trafficProfileId"), JangnyangSubtaskAnswer.accepted(
                                idOf("trafficProfileId"), "jangryang-weekday", "jangryang-weekday",
                                SubtaskAnswerSource.SERVER_DEFAULT)));

        assertTrue(unverified.contains("days"), "관행값은 출처 미확인이다: " + unverified);
        assertFalse(unverified.contains("trafficProfileId"),
                "TMAP 측정 출처가 있는 값을 미확인으로 세면 표시가 무의미해진다: " + unverified);
    }

    /** 사용자가 직접 답한 값은 세지 않는다 — 서버가 채운 것이 아니다. */
    @Test
    void userAnsweredFieldsAreNotCountedAsUnverifiedDefaults() {
        List<String> unverified = JangnyangScenarioBuilder.unverifiedDefaultsOf(v4(),
                java.util.Map.of(idOf("days"), JangnyangSubtaskAnswer.accepted(
                        idOf("days"), "30", 30, SubtaskAnswerSource.USER_DIRECT)));

        assertEquals(List.of(), unverified,
                "사용자가 답한 값에 '출처 미확인'을 붙이면 사용자를 탓하는 표시가 된다");
    }

    /** 근거 선언이 없는 세트(v3)에서는 아무것도 세지 않는다. */
    @Test
    void setsWithoutBasisDeclarationsReportNothing() {
        JangnyangSubtaskDefinition v3 = new JangnyangSubtaskCatalog().byVersion(3);
        String daysId = v3.subtasks().stream()
                .filter(s -> "days".equals(s.answerField()))
                .findFirst().orElseThrow().id();

        assertEquals(List.of(), JangnyangScenarioBuilder.unverifiedDefaultsOf(v3,
                        java.util.Map.of(daysId, JangnyangSubtaskAnswer.accepted(
                                daysId, "30", 30, SubtaskAnswerSource.SERVER_DEFAULT))),
                "선언이 없으면 확인했는지 여부를 말할 수 없다 — 모른다고 단정하지 않는다");
    }

    // ── 결과에 붙는다 ──────────────────────────────────────────────────────

    /** <b>이 작업의 요점.</b> 목록이 있으면 결과가 운영 예측이 아니게 된다. */
    @Test
    void unverifiedDefaultsMakeTheResultAnExperiment() {
        SimulationResult r = new SimulationResult("08:30", 0,
                java.util.Map.of(), java.util.Map.of(), 0.0, 1);
        r.setCoordinateQuality(com.wastesim.model.CoordinateQuality.MEASURED_SITE);
        assertFalse(r.isNotForOperationalUse(), "전제 확인: 표시 전에는 운영 후보다");

        r.addDataQualityFlag(DataQualityFlag.DEFAULT_BASIS_UNVERIFIED, "days, seeds");

        assertTrue(r.isNotForOperationalUse());
        assertTrue(r.getDataQualityWarnings().stream().anyMatch(w -> w.contains("days, seeds")),
                "어느 필드인지 없으면 무엇을 확인해야 할지 알 수 없다: " + r.getDataQualityWarnings());
    }
}
