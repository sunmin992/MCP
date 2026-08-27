package com.wastesim.edge.layout;

import com.wastesim.edge.FanArraySpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 듀얼 팬 배치의 <b>경험적</b> 냉각 점수 모델.
 *
 * <p>출처는 dual_fan_all_layouts_preliminary.xlsx와 그 생성 스크립트
 * build_fan_layouts.mjs(2026-08-27)다. CFD도 실측도 아니고, 팬 풍량·정압, 함체 치수,
 * 통풍구 개구율, 방열판 사양은 하나도 반영돼 있지 않다. 용도는 <b>실측할 배치 후보를
 * 줄이는 것</b> 하나뿐이다.
 *
 * <h3>기존 열 스택을 참조하지 않는다</h3>
 * {@code FanArraySpec}은 "실측 보정 전까지 위치별 냉각 차이를 만들지 않는다"고 못 박고
 * 있는데, 이 클래스는 정확히 그 차이를 만드는 모델이다. 그래서 열 스택
 * ({@code ThermalSimulator}·{@code HeatsinkThermalModel}·{@code ThermalParams}·
 * {@code ThermalRun})을 <b>import 하지 않는다</b> — 의존성이 없으면 임시 계수가 물리
 * 모델 결과에 새어 들어갈 경로 자체가 없다(설계 D-43, FanLayoutIsolationTest가 고정).
 */
public final class FanLayoutScoreModel {

    private FanLayoutScoreModel() {}

    /** 방향 조합 4가지 — 엑셀의 열거 순서를 그대로 따른다(ID가 시트와 어긋나면 안 된다). */
    private static final FanFlowRole[][] FLOW_PAIRS = {
            {FanFlowRole.INTAKE,  FanFlowRole.INTAKE},
            {FanFlowRole.INTAKE,  FanFlowRole.EXHAUST},
            {FanFlowRole.EXHAUST, FanFlowRole.INTAKE},
            {FanFlowRole.EXHAUST, FanFlowRole.EXHAUST}
    };

    /**
     * 주어진 위치들에서 만들 수 있는 모든 배치를 센다.
     *
     * <p>순서가 고정돼 있어야 ID(P01~P60)가 엑셀 시트와 일치한다 — 바깥 루프가 위치 i,
     * 안쪽 루프가 j &gt; i, 그 안에서 방향 4가지다. 이 순서를 바꾸면 골든 회귀 테스트가
     * 깨진다.
     *
     * @param positions 열거에 포함할 위치(2곳 이상). 호출측이 중복·개수를 미리 검증한다
     */
    public static List<FanLayoutCandidate> enumerateAll(List<FanMountPosition> positions) {
        List<FanLayoutCandidate> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                for (FanFlowRole[] flows : FLOW_PAIRS) {
                    String id = String.format("P%02d", out.size() + 1);
                    out.add(new FanLayoutCandidate(
                            id, positions.get(i), flows[0], positions.get(j), flows[1]));
                }
            }
        }
        return out;
    }

    // ── 계수 (출처: 엑셀 "가정" 시트 + build_fan_layouts.mjs, 2026-08-27) ────────
    // 전부 임시값이다. 실측이 들어오면 앵커와 환산계수부터 교체한다(설계 §4.3).

    /** 무팬 상태의 기준 최고온도(℃) — 예상온도를 환산하는 앵커. */
    public static final double BARE_PEAK_ANCHOR_C = 82.0;
    /** 냉각점수 1.0당 내려가는 온도(℃). */
    public static final double SCORE_TO_DELTA_C = 27.0;
    /** 최고온도와 평균온도의 고정 간격(℃). */
    public static final double MEAN_OFFSET_C = 5.2;

    public static final double SPREAD_BASE = 3.0;
    public static final double SPREAD_SLOPE = 10.0;
    /** 두 팬 역할이 같으면 유로가 정해지지 않아 편차가 커진다. */
    public static final double SAME_DIRECTION_SPREAD_PENALTY = 2.0;

    public static final double SCORE_MIN = 0.25;
    public static final double SCORE_MAX = 1.15;

    public static final double INTAKE_PAIR_FACTOR = 0.78;
    public static final double EXHAUST_PAIR_FACTOR = 0.82;
    public static final double THROUGH_FLOW_FACTOR = 1.0;

    /** 흡기가 배기보다 낮다 — 자연대류와 같은 방향이라 유리하다. */
    public static final double NATURAL_CONVECTION_BONUS = 0.15;
    /** 흡기가 배기보다 높다 — 자연대류를 거스른다. */
    public static final double AGAINST_CONVECTION_PENALTY = -0.10;
    /** 흡·배기가 같은 측면이라 공기가 보드를 지나지 않고 빠져나갈 수 있다. */
    public static final double SHORT_CIRCUIT_PENALTY = -0.12;

    public static final double RISK_LOW_THRESHOLD = 0.95;
    public static final double RISK_MEDIUM_THRESHOLD = 0.78;

    /**
     * 배치 하나를 평가한다.
     *
     * <p>식은 엑셀 K~O열 수식을 그대로 옮긴 것이다.
     * <pre>
     * score = clamp(0.25, 1.15, (eff1 + eff2)/2 * pairFactor + flowBonus)
     * peak  = 82 - score * 27
     * </pre>
     */
    public static FanLayoutScore score(FanLayoutCandidate c) {
        double pairFactor = pairFactor(c);
        double rawBonus = 0.0;
        String note;

        if (!c.hasSameFlow()) {
            // 관통류 — 흡기와 배기가 정해지므로 유로의 방향을 따질 수 있다.
            FanMountPosition intake  = c.flow1() == FanFlowRole.INTAKE  ? c.position1() : c.position2();
            FanMountPosition exhaust = c.flow1() == FanFlowRole.EXHAUST ? c.position1() : c.position2();

            if (intake.level() < exhaust.level()) {
                rawBonus += NATURAL_CONVECTION_BONUS;
                note = "자연대류와 같은 아래→위 흐름";
            } else if (intake.level() > exhaust.level()) {
                rawBonus += AGAINST_CONVECTION_PENALTY;
                note = "자연대류를 거스르는 위→아래 흐름";
            } else {
                note = "같은 높이의 횡류";
            }
            // 중앙은 함체 반대면이라 단락으로 보지 않는다 — 좌·우끼리 겹칠 때만 문제다.
            if (intake.side() == exhaust.side() && intake.side() != FanMountPosition.Side.CENTER) {
                rawBonus += SHORT_CIRCUIT_PENALTY;
                note += "; 입출구 단락 가능";
            }
        } else {
            // 둘 다 흡기이거나 둘 다 배기 — 유로가 팬이 아니라 함체 틈에 맡겨진다.
            note = c.flow1() == FanFlowRole.INTAKE
                    ? "출구 면적에 따라 내부 양압"
                    : "흡기 틈 위치에 따라 내부 음압";
        }

        double meanEfficiency = (c.position1().efficiency() + c.position2().efficiency()) / 2.0;
        double score = clampScore(meanEfficiency * pairFactor + rawBonus);

        double peak = BARE_PEAK_ANCHOR_C - score * SCORE_TO_DELTA_C;
        double spread = SPREAD_BASE + (1 - score) * SPREAD_SLOPE
                + (c.hasSameFlow() ? SAME_DIRECTION_SPREAD_PENALTY : 0.0);

        return new FanLayoutScore(
                score, flowType(c), pairFactor, rawBonus,
                peak, peak - MEAN_OFFSET_C, spread,
                risk(score), note,
                FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE);
    }

    /** 점수를 물리적으로 말이 되는 범위로 자른다. 표준 6위치에서는 걸리지 않는 가드다. */
    public static double clampScore(double raw) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, raw));
    }

    private static double pairFactor(FanLayoutCandidate c) {
        if (!c.hasSameFlow()) return THROUGH_FLOW_FACTOR;
        return c.flow1() == FanFlowRole.INTAKE ? INTAKE_PAIR_FACTOR : EXHAUST_PAIR_FACTOR;
    }

    private static FanLayoutScore.FlowType flowType(FanLayoutCandidate c) {
        if (!c.hasSameFlow()) return FanLayoutScore.FlowType.FORCED_THROUGH_FLOW;
        return c.flow1() == FanFlowRole.INTAKE
                ? FanLayoutScore.FlowType.POSITIVE_PRESSURE
                : FanLayoutScore.FlowType.NEGATIVE_PRESSURE;
    }

    private static FanLayoutScore.StagnationRisk risk(double score) {
        if (score >= RISK_LOW_THRESHOLD) return FanLayoutScore.StagnationRisk.LOW;
        if (score >= RISK_MEDIUM_THRESHOLD) return FanLayoutScore.StagnationRisk.MEDIUM;
        return FanLayoutScore.StagnationRisk.HIGH;
    }
}
