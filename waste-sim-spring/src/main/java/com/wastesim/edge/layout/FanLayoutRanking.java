package com.wastesim.edge.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 배치 후보들을 냉각점수로 줄 세운다.
 *
 * <h3>동률은 실제로 생긴다 — ID 규칙이 장식이 아니다</h3>
 * 좌·우 위치효율이 대칭이라(좌측 상단 0.82 = 우측 상단 0.82) 좌우를 뒤집은 배치는
 * 점수도 편차도 완전히 같아진다. 60조합 중 P10(하단 흡기 + 좌측 상단 배기)과
 * P18(하단 흡기 + 우측 상단 배기)이 그렇고, 이 둘이 실제로 2·3위를 나눠 갖는다.
 *
 * <p>이 동률은 계산 오차가 아니라 <b>모델이 좌우를 구별할 근거를 갖고 있지 않다</b>는
 * 사실 그대로다. 그래서 임의로 우열을 만들지 않고 조합 ID 오름차순으로 고정한다 —
 * 같은 입력이면 항상 같은 순위가 나와야 사용자가 결과를 재현할 수 있다.
 */
public final class FanLayoutRanking {

    private FanLayoutRanking() {}

    public static final String STATUS_RANKED = "RANKED";

    /** 이 도구가 물리 모델이 아님을 응답 최상단에서 밝히는 표식. */
    public static final String MODEL_KIND = "EMPIRICAL_SCORE_NOT_PHYSICS";

    /**
     * 동률 허용 오차. 0으로 두면 부동소수점 끝자리가 순위를 정한다 — 물리적으로
     * 구분되지 않는 차이로 순위가 뒤집히면 사용자가 재현할 수 없다
     * ({@code FanSweepResult.TIE_TOLERANCE}와 같은 이유).
     */
    public static final double TIE_TOLERANCE = 1e-9;

    public static final String TIE_BREAK =
            "coolingScore 동률이면 advisorySpreadC가 작은 쪽, 그래도 같으면 조합 ID 오름차순";

    /**
     * 항상 함께 나가는 경고. 하나라도 빠지면 임시 추정값이 확정값처럼 읽힌다.
     */
    public static final List<String> WARNINGS = List.of(
            "FAN_SPEC_NOT_VERIFIED",
            "ADVISORY_TEMP_ANCHORED_ESTIMATE",
            "ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR");

    /** 엑셀 "추천 결과" 시트의 권장 실측 순서. */
    public static final List<String> RECOMMENDED_MEASUREMENT_STEPS = List.of(
            "상위 3개 배치 각 3회 반복",
            "무팬 및 팬 1개 기준선과 비교",
            "최고온도·노드별 온도·회복시간·소음·전력 기록");

    /** 순위 한 줄. */
    public record Entry(int rank, FanLayoutCandidate candidate, FanLayoutScore score) {}

    public static List<Entry> rank(List<FanLayoutCandidate> candidates) {
        record Scored(FanLayoutCandidate candidate, FanLayoutScore score) {}

        List<Scored> scored = new ArrayList<>();
        for (FanLayoutCandidate c : candidates) scored.add(new Scored(c, FanLayoutScoreModel.score(c)));

        scored.sort(Comparator
                .comparingDouble((Scored s) -> quantize(s.score().coolingScore())).reversed()
                .thenComparingDouble(s -> quantize(s.score().advisorySpreadC()))
                .thenComparing(s -> s.candidate().id()));

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            out.add(new Entry(i + 1, scored.get(i).candidate(), scored.get(i).score()));
        }
        return out;
    }

    /**
     * 허용 오차 안의 차이를 같은 값으로 뭉갠다 — 그래야 다음 비교 기준(편차 → ID)이
     * 실제로 순위를 가른다. 오차를 무시하고 raw 값을 비교하면 1e-15 차이 때문에
     * 편차 규칙이 영원히 발동하지 않는다.
     */
    private static double quantize(double v) {
        return Math.round(v / TIE_TOLERANCE) * TIE_TOLERANCE;
    }
}
