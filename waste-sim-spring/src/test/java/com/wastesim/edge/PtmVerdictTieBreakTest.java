package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PTM 승자 판정의 <b>동률 처리</b>를 고정한다.
 *
 * <p>예전에는 {@code min(총에너지)} 하나뿐이라, 에너지가 사실상 같으면 리스트에서 먼저 나온
 * 방식(항상 최대)이 그냥 이겼다. 제어 방식은 서너 개뿐이고 부하가 평탄하면 방식 간 에너지가
 * 수 J 안에서 겹치는 일이 흔한데, 그때 "왜 이게 이겼는가"에 답할 수 없는 승자가 나왔다.
 *
 * <p>지금은 팬 스윕({@link FanSweepResult#select})과 같은 규칙을 쓴다 — 상대 오차
 * {@link FanSweepResult#TIE_TOLERANCE} 안이면 동률로 보고, 최고 온도 → 평균 팬 듀티 →
 * 회전수 변경 횟수 순으로 고른 뒤 <b>동률이었다는 사실을 결과에 남긴다</b>(D-25).
 */
class PtmVerdictTieBreakTest {

    private final ObjectMapper om = new ObjectMapper();
    private final SimulatePtmControlTool tool =
            new SimulatePtmControlTool(new EdgeThermalProfileStore(), new AiLoadProfileService());

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String json) {
        try {
            ToolResult tr = tool.call(om.readTree(json));
            assertTrue(tr.ready(), () -> "실행됐어야 한다: " + tr.errors());
            return (Map<String, Object>) tr.result();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 승자 판정은 별도 키가 아니라 최상위 출력에 펼쳐진다(putAll). */
    private static Map<String, Object> verdict(Map<String, Object> out) {
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("runs");
    }

    private static double num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).doubleValue();
    }

    @Test
    @DisplayName("팬이 한 번도 돌지 않는 조건에서는 세 방식이 모두 동률로 보고된다")
    void allModesTieWhenFanNeverSpins() {
        // 목표 2FPS·주변 20℃면 온도가 30℃ 부근에 머물러 어느 제어기도 팬을 켜지 않는다.
        // 세 방식의 총에너지·최고온도·듀티가 전부 같아지므로, 여기서 "predictive가 이겼다"
        // 같은 결론이 나오면 그건 없는 우열을 만든 것이다.
        Map<String, Object> out = run("""
            {"board":"pi5","cooling":"active","workloadMode":"target_fps","targetFps":2,
             "loadSeconds":600,"ambientTempC":20,
             "modes":["fixed","reactive","predictive"],"fixedPwmPercent":0}
            """);

        assertEquals("OK", out.get("status"));
        @SuppressWarnings("unchecked")
        List<String> tied = (List<String>) out.get("energyTiedModes");
        assertNotNull(tied, "에너지가 같으면 그 사실이 결과에 있어야 한다 — 없으면 사용자는 "
                + "'이 방식이 더 효율적이다'라는 없는 결론을 읽는다");
        assertEquals(3, tied.size(), "세 방식 모두 팬을 안 돌리므로 전부 동률이다");
        assertNotNull(out.get("tieBreak"), "무엇으로 갈랐는지도 함께 적어야 한다");
        assertNotNull(out.get("best"), "동률이어도 결정론적으로 하나는 고른다");

        // 실제로 값이 같은지 — 동률 표시가 맞는 근거다.
        List<Map<String, Object>> rs = runs(out);
        double e0 = num(rs.get(0), "totalEnergyJ");
        for (Map<String, Object> r : rs) {
            assertEquals(e0, num(r, "totalEnergyJ"), e0 * FanSweepResult.TIE_TOLERANCE);
            assertEquals(0.0, num(r, "meanFanDutyPercent"), 1e-9, "팬이 돌지 않아야 하는 조건이다");
        }
    }

    @Test
    @DisplayName("에너지 동률이면 더 시원한 방식이 이긴다 — 리스트 순서로 정해지지 않는다")
    void tieIsBrokenByPeakTemperature() {
        Map<String, Object> out = run("""
            {"board":"pi5","cooling":"active","workloadMode":"target_fps","targetFps":2,
             "loadSeconds":600,"ambientTempC":20,
             "modes":["fixed","reactive","predictive"],"fixedPwmPercent":0}
            """);
        Map<String, Object> v = verdict(out);
        Object tiedModes = v.get("energyTiedModes");
        if (tiedModes == null) return;   // 이 조건에서 동률이 아니면 검증할 것이 없다

        @SuppressWarnings("unchecked")
        List<String> tied = (List<String>) tiedModes;
        double bestTemp = runs(out).stream()
                .filter(r -> tied.contains(r.get("mode")))
                .mapToDouble(r -> num(r, "peakTempC"))
                .min().orElseThrow();
        Map<String, Object> winner = runs(out).stream()
                .filter(r -> v.get("best").equals(r.get("mode")))
                .findFirst().orElseThrow();

        assertEquals(bestTemp, num(winner, "peakTempC"), 1e-9,
                "동률 집합 안에서 가장 시원한 방식이 이겨야 한다");
    }

    @Test
    @DisplayName("에너지 차이가 뚜렷하면 동률 표시 없이 에너지 최소 방식이 이긴다")
    void clearWinnerHasNoTieAnnotation() {
        // burst 부하에서 항상 최대와 예측형은 팬 에너지가 확연히 갈린다.
        Map<String, Object> out = run("""
            {"board":"pi5","cooling":"active","workloadMode":"max_throughput",
             "loadSeconds":1800,"ambientTempC":25,"aiLoadProfileId":"burst",
             "modes":["always_max","predictive"]}
            """);
        Map<String, Object> v = verdict(out);
        if (!"OK".equals(v.get("status"))) return;   // 둘 다 스로틀링이면 이 테스트의 관심사가 아니다

        List<Map<String, Object>> rs = runs(out);
        double e0 = num(rs.get(0), "totalEnergyJ");
        double e1 = num(rs.get(1), "totalEnergyJ");
        if (Math.abs(e0 - e1) <= Math.min(e0, e1) * FanSweepResult.TIE_TOLERANCE) return;

        assertNull(v.get("energyTiedModes"),
                "차이가 뚜렷한데 동률로 표시하면 반대 방향의 오해가 생긴다");
        String cheaper = e0 <= e1 ? (String) rs.get(0).get("mode") : (String) rs.get(1).get("mode");
        assertEquals(cheaper, v.get("best"));
    }

    @Test
    @DisplayName("같은 입력은 같은 승자를 낸다 — 동률 처리가 재현성을 깨지 않는다")
    void verdictIsDeterministic() {
        String json = """
            {"board":"pi5","cooling":"active","workloadMode":"target_fps","targetFps":2,
             "loadSeconds":600,"ambientTempC":20,"modes":["fixed","reactive","predictive"],
             "fixedPwmPercent":0}
            """;
        assertEquals(verdict(run(json)).get("best"), verdict(run(json)).get("best"));
    }
}
