package com.wastesim.edge.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 점수 모델의 골든 회귀 — 엑셀 dual_fan_all_layouts_preliminary.xlsx와
 * 숫자가 어긋나면 여기서 깨진다. 이 도구의 유일한 근거가 그 시트이므로,
 * 시트와의 일치가 곧 정확성 기준이다.
 */
class FanLayoutScoreModelTest {

    @Test
    @DisplayName("장착 위치 6곳의 높이·측면·위치효율이 엑셀 가정 시트와 같다")
    void positionsMatchAssumptionSheet() {
        assertEquals(6, FanMountPosition.values().length);

        assertEquals(0, FanMountPosition.BOTTOM.level());
        assertEquals(FanMountPosition.Side.CENTER, FanMountPosition.BOTTOM.side());
        assertEquals(0.95, FanMountPosition.BOTTOM.efficiency(), 1e-9);
        assertEquals("하단", FanMountPosition.BOTTOM.koLabel());

        assertEquals(2, FanMountPosition.TOP.level());
        assertEquals(FanMountPosition.Side.CENTER, FanMountPosition.TOP.side());
        assertEquals(0.90, FanMountPosition.TOP.efficiency(), 1e-9);

        assertEquals(0.78, FanMountPosition.LEFT_BOTTOM.efficiency(), 1e-9);
        assertEquals(FanMountPosition.Side.LEFT, FanMountPosition.LEFT_BOTTOM.side());
        assertEquals(0.82, FanMountPosition.LEFT_TOP.efficiency(), 1e-9);
        assertEquals(0.78, FanMountPosition.RIGHT_BOTTOM.efficiency(), 1e-9);
        assertEquals(FanMountPosition.Side.RIGHT, FanMountPosition.RIGHT_BOTTOM.side());
        assertEquals(0.82, FanMountPosition.RIGHT_TOP.efficiency(), 1e-9);
    }

    @Test
    @DisplayName("위치·방향은 영문 키와 한글 라벨을 모두 받고, 모르는 값은 null이다")
    void parseAcceptsBothNotations() {
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("bottom"));
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("  BOTTOM  "));
        assertEquals(FanMountPosition.BOTTOM, FanMountPosition.parse("하단"));
        assertEquals(FanMountPosition.LEFT_TOP, FanMountPosition.parse("left_top"));
        assertEquals(FanMountPosition.LEFT_TOP, FanMountPosition.parse("좌측 상단"));
        assertNull(FanMountPosition.parse("뒷면"));
        assertNull(FanMountPosition.parse(null));

        assertEquals(FanFlowRole.INTAKE, FanFlowRole.parse("intake"));
        assertEquals(FanFlowRole.INTAKE, FanFlowRole.parse("흡기"));
        assertEquals(FanFlowRole.EXHAUST, FanFlowRole.parse("exhaust"));
        assertEquals(FanFlowRole.EXHAUST, FanFlowRole.parse("배기"));
        assertNull(FanFlowRole.parse("순환"));
        assertNull(FanFlowRole.parse(null));
    }

    @Test
    @DisplayName("6위치 전수 열거는 15쌍 × 4방향 = 60조합이고 ID가 P01~P60이다")
    void enumeratesSixtyCombinations() {
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));

        assertEquals(60, all.size());
        assertEquals("P01", all.get(0).id());
        assertEquals("P60", all.get(59).id());

        // 위치쌍은 순서 없는 조합이라 같은 배치가 두 번 나오면 안 된다.
        Set<String> shapes = new HashSet<>();
        for (FanLayoutCandidate c : all) {
            String a = c.position1().name() + ":" + c.flow1().name();
            String b = c.position2().name() + ":" + c.flow2().name();
            List<String> pair = new ArrayList<>(List.of(a, b));
            Collections.sort(pair);
            assertTrue(shapes.add(String.join("|", pair)), "중복 조합: " + c.id());
        }
    }

    @Test
    @DisplayName("열거 순서가 고정된다 — P02는 하단 흡기 + 상단 배기, P58은 우측 하단 흡기 + 우측 상단 배기")
    void enumerationOrderIsStable() {
        List<FanLayoutCandidate> all =
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));
        Map<String, FanLayoutCandidate> byId = new LinkedHashMap<>();
        for (FanLayoutCandidate c : all) byId.put(c.id(), c);

        FanLayoutCandidate p02 = byId.get("P02");
        assertEquals(FanMountPosition.BOTTOM, p02.position1());
        assertEquals(FanFlowRole.INTAKE, p02.flow1());
        assertEquals(FanMountPosition.TOP, p02.position2());
        assertEquals(FanFlowRole.EXHAUST, p02.flow2());

        FanLayoutCandidate p58 = byId.get("P58");
        assertEquals(FanMountPosition.RIGHT_BOTTOM, p58.position1());
        assertEquals(FanFlowRole.INTAKE, p58.flow1());
        assertEquals(FanMountPosition.RIGHT_TOP, p58.position2());
        assertEquals(FanFlowRole.EXHAUST, p58.flow2());
    }

    @Test
    @DisplayName("위치를 2곳으로 줄이면 1쌍 × 4방향 = 4조합만 나온다")
    void enumerationHonoursPositionSubset() {
        List<FanLayoutCandidate> some = FanLayoutScoreModel.enumerateAll(
                List.of(FanMountPosition.BOTTOM, FanMountPosition.TOP));
        assertEquals(4, some.size());
        assertEquals("P01", some.get(0).id());
        assertEquals("P04", some.get(3).id());
    }

    private FanLayoutScore scoreOf(String id) {
        for (FanLayoutCandidate c : FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))) {
            if (c.id().equals(id)) return FanLayoutScoreModel.score(c);
        }
        throw new AssertionError("조합 없음: " + id);
    }

    @Test
    @DisplayName("골든 회귀 — 7개 조합의 점수·예상온도·편차가 엑셀 값과 일치한다")
    void goldenValuesMatchSpreadsheet() {
        // {id, coolingScore, advisoryPeakTempC, advisorySpreadC}
        double[][] golden = {
                {1,  0.7215, 62.5195, 7.785},
                {2,  1.075,  52.975,  2.25},
                {3,  0.825,  59.725,  4.75},
                {5,  0.6747, 63.7831, 8.253},
                {58, 0.83,   59.59,   4.70},
                {59, 0.58,   66.34,   7.20},
                {60, 0.656,  64.288,  8.44}
        };
        for (double[] g : golden) {
            String id = String.format("P%02d", (int) g[0]);
            FanLayoutScore s = scoreOf(id);
            assertEquals(g[1], s.coolingScore(),      1e-9, id + " 냉각점수");
            assertEquals(g[2], s.advisoryPeakTempC(), 1e-9, id + " 예상 최고온도");
            assertEquals(g[3], s.advisorySpreadC(),   1e-9, id + " 예상 편차");
            // 평균은 최고에서 고정 오프셋만큼 내린 값이다(엑셀 M열).
            assertEquals(g[2] - 5.2, s.advisoryMeanTempC(), 1e-9, id + " 예상 평균온도");
        }
    }

    @Test
    @DisplayName("관통류 보정 — 흡기가 낮으면 +0.15, 높으면 -0.10, 같은 높이면 0")
    void throughFlowBonusFollowsNaturalConvection() {
        assertEquals(0.15,  scoreOf("P02").flowBonus(), 1e-9);  // 하단 흡기 → 상단 배기
        assertEquals(-0.10, scoreOf("P03").flowBonus(), 1e-9);  // 하단 배기 ← 상단 흡기
        assertEquals(0.0,   scoreOf("P06").flowBonus(), 1e-9);  // 하단 흡기 → 좌측 하단 배기(같은 높이)
    }

    @Test
    @DisplayName("입출구 단락 — 흡·배기가 같은 측면(중앙 제외)일 때만 -0.12가 붙는다")
    void shortCircuitPenaltyOnlyOnSameSide() {
        // P58: 우측 하단 흡기 + 우측 상단 배기 → 자연대류 +0.15, 단락 -0.12 → 0.03
        assertEquals(0.03, scoreOf("P58").flowBonus(), 1e-9);
        assertTrue(scoreOf("P58").interpretation().contains("입출구 단락 가능"));
        // P02: 둘 다 중앙(하단·상단)이라 단락 페널티가 없다
        assertEquals(0.15, scoreOf("P02").flowBonus(), 1e-9);
        assertFalse(scoreOf("P02").interpretation().contains("단락"));
    }

    @Test
    @DisplayName("흐름 유형과 정체 위험이 점수·방향에서 결정된다")
    void flowTypeAndRiskAreDerived() {
        assertEquals(FanLayoutScore.FlowType.FORCED_THROUGH_FLOW, scoreOf("P02").flowType());
        assertEquals(FanLayoutScore.StagnationRisk.LOW, scoreOf("P02").stagnationRisk());

        assertEquals(FanLayoutScore.FlowType.POSITIVE_PRESSURE, scoreOf("P01").flowType());
        assertEquals(FanLayoutScore.FlowType.NEGATIVE_PRESSURE, scoreOf("P04").flowType());

        assertEquals(FanLayoutScore.StagnationRisk.MEDIUM, scoreOf("P03").stagnationRisk()); // 0.825
        assertEquals(FanLayoutScore.StagnationRisk.HIGH,   scoreOf("P59").stagnationRisk()); // 0.58
    }

    @Test
    @DisplayName("clamp는 6위치 표준 집합에서 한 번도 걸리지 않는다 — 현재 비활성 가드임을 문서화")
    void clampNeverBindsForStandardPositionSet() {
        for (FanLayoutCandidate c : FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))) {
            double s = FanLayoutScoreModel.score(c).coolingScore();
            assertTrue(s > FanLayoutScoreModel.SCORE_MIN,
                    c.id() + " 점수가 하한에 닿았다 — clamp가 순위를 바꾸고 있다: " + s);
            assertTrue(s < FanLayoutScoreModel.SCORE_MAX,
                    c.id() + " 점수가 상한에 닿았다 — clamp가 순위를 바꾸고 있다: " + s);
        }
    }

    @Test
    @DisplayName("clamp 자체는 범위를 벗어난 값에서 동작한다")
    void clampBoundsRawScore() {
        assertEquals(0.25, FanLayoutScoreModel.clampScore(-3.0), 1e-9);
        assertEquals(1.15, FanLayoutScoreModel.clampScore(9.9), 1e-9);
        assertEquals(0.90, FanLayoutScoreModel.clampScore(0.90), 1e-9);
    }

    @Test
    @DisplayName("모든 조합의 신뢰상태가 검증 전 임시값으로 표시된다")
    void everyScoreIsMarkedPreliminary() {
        for (FanLayoutCandidate c : FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))) {
            assertEquals(com.wastesim.edge.FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE,
                    FanLayoutScoreModel.score(c).sourceStatus(), c.id());
        }
    }

    @Test
    @DisplayName("1위는 P02(하단 흡기 + 상단 배기)이고 점수 내림차순으로 정렬된다")
    void bestLayoutIsBottomIntakeTopExhaust() {
        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));

        assertEquals(60, ranked.size());
        assertEquals(1, ranked.get(0).rank());
        assertEquals("P02", ranked.get(0).candidate().id());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).score().coolingScore() >= ranked.get(i).score().coolingScore(),
                    "정렬이 깨졌다: " + ranked.get(i - 1).candidate().id());
            assertEquals(i + 1, ranked.get(i).rank());
        }
    }

    @Test
    @DisplayName("좌우 대칭 동률은 조합 ID로 갈린다 — P10이 P18보다 앞선다")
    void leftRightTieIsBrokenById() {
        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));

        int p10 = -1, p18 = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).candidate().id().equals("P10")) p10 = i;
            if (ranked.get(i).candidate().id().equals("P18")) p18 = i;
        }
        assertTrue(p10 >= 0 && p18 >= 0);

        // 좌·우 위치효율이 대칭이라 점수와 편차가 완전히 같다. 이 동률은 모델의 한계이지
        // 계산 오차가 아니므로, 임의로 우열을 만들지 않고 ID 순서로 고정한다.
        assertEquals(ranked.get(p10).score().coolingScore(),
                     ranked.get(p18).score().coolingScore(), 1e-12);
        assertEquals(ranked.get(p10).score().advisorySpreadC(),
                     ranked.get(p18).score().advisorySpreadC(), 1e-12);
        assertTrue(p10 < p18, "동률이면 ID가 작은 쪽이 앞선다");
        assertEquals(2, ranked.get(p10).rank());
    }

    @Test
    @DisplayName("입력 순서가 아니라 ID 동률 규칙이 P10-P18 순서를 결정한다")
    void leftRightTieOrderSurvivesReversedInput() {
        // enumerateAll은 이미 ID 오름차순(P01→P60)이라, 그대로 넣으면 안정 정렬 특성 때문에
        // ID 비교 규칙을 지워도 우연히 같은 결과가 나온다 — 그래서는 이 규칙이 실제로
        // 동작하는지 검증할 수 없다. 입력을 뒤집어(P60→P01) 삽입 순서와 ID 순서를
        // 일부러 어긋나게 만들어야, ID 동률 규칙이 빠졌을 때 이 테스트가 실패한다.
        List<FanLayoutCandidate> reversed = new ArrayList<>(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));
        Collections.reverse(reversed);

        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(reversed);

        int p10 = -1, p18 = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).candidate().id().equals("P10")) p10 = i;
            if (ranked.get(i).candidate().id().equals("P18")) p18 = i;
        }
        assertTrue(p10 >= 0 && p18 >= 0);

        // 두 숫자 비교키(점수·편차)가 완전히 같음을 다시 확인 — 그래야 아래 순서가
        // 숫자 키가 아니라 ID 규칙 때문임이 분명해진다.
        assertEquals(ranked.get(p10).score().coolingScore(),
                     ranked.get(p18).score().coolingScore(), 1e-12);
        assertEquals(ranked.get(p10).score().advisorySpreadC(),
                     ranked.get(p18).score().advisorySpreadC(), 1e-12);

        assertEquals(p10 + 1, p18, "P10과 P18은 순위표에서 인접해야 한다");
        assertTrue(p10 < p18, "입력을 뒤집어도 ID가 작은 P10이 앞서야 한다 — ID 동률 규칙이 결정한 것이다");
    }

    @Test
    @DisplayName("점수가 같으면 예상 편차가 작은 쪽이 앞선다")
    void tieOnScoreIsBrokenBySpread() {
        // 같은 점수를 만들되 편차만 다르게 — 방향이 같은 조합은 편차에 +2가 붙는다.
        List<FanLayoutRanking.Entry> ranked = FanLayoutRanking.rank(
                FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));
        for (int i = 1; i < ranked.size(); i++) {
            FanLayoutRanking.Entry prev = ranked.get(i - 1), cur = ranked.get(i);
            if (Math.abs(prev.score().coolingScore() - cur.score().coolingScore()) < 1e-9) {
                assertTrue(prev.score().advisorySpreadC() <= cur.score().advisorySpreadC() + 1e-9,
                        "동률에서 편차 순서가 깨졌다: " + prev.candidate().id() + " vs " + cur.candidate().id());
            }
        }
    }
}
