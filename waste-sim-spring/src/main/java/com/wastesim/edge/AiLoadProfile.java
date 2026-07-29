package com.wastesim.edge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI 데이터센터식 시변(時變) 부하 패턴 — 시각에 따라 달라지는 부하 배율.
 * {@code src/main/resources/edge/ai-load-*.json}에서
 * {@link AiLoadProfileService}가 로드한다.
 *
 * <h3>왜 필요한가</h3>
 * 기존 {@link WorkloadMode}는 상수 부하(목표 FPS 고정 / 최대 처리량)뿐이라
 * "계속 최대로 돌린다"만 표현할 수 있었다. 그 조건에서는 정상상태 온도가
 * {@code T_주변 + P·R_ja}로 정해지고 열용량 {@code C_th}는 식에 나타나지 않으므로,
 * 방열판 순위가 오직 열저항 {@code R_ja}로 결정된다 — 성능이 제일 좋은 것이
 * 항상 이기는, 결과가 뻔한 실험이다.
 *
 * <p>실제 AI 서비스 부하는 몰렸다 빠지기를 반복한다. 부하가 출렁이면 온도가
 * 과도 구간에 머물고, 이때는 {@code C_th}가 온도 진폭의 저역통과 필터로 작동한다
 * — 질량이 큰 방열판이 피크를 흡수하므로, <b>R_ja 순위와 피크 온도 순위가
 * 어긋날 수 있다.</b> 이 어긋남이 "가성비를 따지면 A가 아니라 B가 낫다"는
 * 연구 질문의 물리적 근거다.
 *
 * <h3>구간(segment) 방식을 쓴 이유</h3>
 * {@link com.wastesim.model.TrafficProfile}처럼 시간대별 배열(길이 24)로 표현할
 * 수도 있었지만, 그러면 최소 해상도가 1시간이라 이 실험에 쓸 수 없다(아래 참고).
 * 대신 "얼마 동안 어느 수준"을 그대로 나열해, 시뮬레이터와 측정 스크립트가
 * <b>완전히 같은 순서를 재생</b>할 수 있게 했다.
 *
 * <h3>시간 규모가 이 실험의 성패를 가른다</h3>
 * 구간 길이를 시정수 τ와 견주어 정해야 한다({@link #timescaleFit}).
 * <ul>
 *   <li>구간 ≫ τ (예: 시간 단위 일주기): 매 구간이 준정상상태 → 상수 부하와 같은 결과</li>
 *   <li>구간 ≈ τ (수십 초~수 분): <b>과도응답이 그대로 드러남 → 순위가 뒤집힐 수 있음</b></li>
 *   <li>구간 ≪ τ: 열용량이 전부 평균해버림 → 역시 차이 없음</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiLoadProfile {

    /** 부하 패턴이 시정수 τ에 비추어 과도응답을 드러내는가에 대한 판정. */
    public enum TimescaleFit {
        /** 구간이 τ에 비해 너무 짧다 — 열용량이 평균해버려 방열판 차이가 안 드러난다. */
        AVERAGED,
        /** 구간이 τ와 비슷하다 — 과도응답이 드러나므로 C_th 차이가 순위에 반영된다. */
        SENSITIVE,
        /** 구간이 τ에 비해 너무 길거나 부하가 일정하다 — 준정상상태라 R_ja만 결정한다. */
        QUASI_STATIC
    }

    /** 구간이 τ보다 이 배수보다 짧으면 열용량이 평균해버린다고 본다. */
    static final double AVERAGED_RATIO = 0.3;
    /** 구간이 τ보다 이 배수보다 길면 준정상상태로 본다. */
    static final double QUASI_STATIC_RATIO = 10.0;

    /**
     * 한 구간.
     *
     * @param durationSec 이 수준을 유지하는 시간(초)
     * @param level       부하 배율 0~1. 1.0이면 그 실행의 기준 부하(목표 FPS 또는
     *                    최대 처리량)를 100% 낸다
     * @param label       구간 이름(로그·그래프용, 계산에는 쓰지 않음)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(double durationSec, double level, String label) {}

    private String id;
    private String label;
    private String description;
    private List<Segment> segments = List.of();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public List<Segment> getSegments() { return segments; }
    public void setSegments(List<Segment> v) { this.segments = v == null ? List.of() : v; }

    /** 한 주기 길이(초). 구간 길이의 합이며, 이 시간이 지나면 처음부터 반복한다. */
    public double cycleSeconds() {
        return segments.stream().mapToDouble(Segment::durationSec).sum();
    }

    /**
     * 시각 {@code tSec}에서의 부하 배율. 주기를 넘어가면 처음부터 반복한다.
     * 구간이 하나도 없으면 1.0(상수 최대 부하)으로 폴백한다 — 부하 패턴은
     * 필수값이 아니므로 없을 때 실행이 막히면 안 된다.
     */
    public double levelAt(double tSec) {
        double cycle = cycleSeconds();
        if (cycle <= 0) return 1.0;
        double t = ((tSec % cycle) + cycle) % cycle;   // 음수 시각도 안전하게 감싼다
        double acc = 0;
        for (Segment s : segments) {
            acc += s.durationSec();
            if (t < acc) return clamp(s.level());
        }
        // 부동소수 누적 오차로 마지막 경계를 넘긴 경우
        return clamp(segments.get(segments.size() - 1).level());
    }

    /** 시간 가중 평균 부하 배율 — 같은 평균인데 모양만 다른 패턴끼리 비교할 때 쓴다. */
    public double meanLevel() {
        double cycle = cycleSeconds();
        if (cycle <= 0) return 1.0;
        double sum = 0;
        for (Segment s : segments) sum += clamp(s.level()) * s.durationSec();
        return sum / cycle;
    }

    /** 최대 부하 배율. */
    public double peakLevel() {
        return segments.stream().mapToDouble(s -> clamp(s.level())).max().orElse(1.0);
    }

    /** 부하 수준이 전혀 변하지 않는가(대조군인가). */
    public boolean isConstant() {
        if (segments.size() <= 1) return true;
        double first = clamp(segments.get(0).level());
        return segments.stream().allMatch(s -> Math.abs(clamp(s.level()) - first) < 1e-9);
    }

    /**
     * 이 패턴이 주어진 시정수에서 과도응답을 드러내는지 판정한다.
     *
     * <p>기준이 되는 것은 주기 전체가 아니라 <b>가장 짧은 구간</b>이다 — 온도를
     * 가장 크게 흔드는 것은 "부하가 한 수준으로 얼마나 짧게 머무는가"이기 때문이다.
     * 예를 들어 30분 주기여도 그 안의 구간이 2분이면 과도응답은 2분 규모로 나타난다.
     *
     * @param tauSec 비교 대상 시정수(초). 방열판 조건마다 다르므로 조건별로 확인할 것
     */
    public TimescaleFit timescaleFit(double tauSec) {
        if (isConstant() || tauSec <= 0) return TimescaleFit.QUASI_STATIC;
        double shortest = segments.stream().mapToDouble(Segment::durationSec)
                .filter(d -> d > 0).min().orElse(0);
        if (shortest <= 0) return TimescaleFit.QUASI_STATIC;
        if (shortest < AVERAGED_RATIO * tauSec) return TimescaleFit.AVERAGED;
        if (shortest > QUASI_STATIC_RATIO * tauSec) return TimescaleFit.QUASI_STATIC;
        return TimescaleFit.SENSITIVE;
    }

    /**
     * {@link #timescaleFit} 결과를 실험자가 바로 읽을 수 있는 문장으로 바꾼다.
     * 값만 보고 "패턴을 넣었는데 왜 순위가 그대로지?"라고 오해하는 것을 막는 것이
     * 목적이다(엣지 도구들이 {@code notes}로 이유를 설명하는 것과 같은 취지).
     */
    public String timescaleNote(double tauSec) {
        return switch (timescaleFit(tauSec)) {
            case SENSITIVE -> String.format(
                    "부하 변동이 시정수 τ=%.0f초와 비슷한 규모라 과도응답이 드러난다 — 열용량 차이가 피크 온도에 반영되므로 방열판 순위가 상수 부하와 달라질 수 있다.",
                    tauSec);
            case QUASI_STATIC -> isConstant()
                    ? "부하가 일정해 온도가 정상상태에 머문다 — 이 조건에서 순위는 오직 열저항 R_ja로 정해진다(대조군)."
                    : String.format(
                    "가장 짧은 구간이 시정수 τ=%.0f초의 %.0f배를 넘어 매 구간이 준정상상태에 도달한다 — 상수 부하와 사실상 같은 결과가 나오므로, 순위 변화를 보려면 구간을 τ 규모(수십 초~수 분)로 줄여야 한다.",
                    tauSec, QUASI_STATIC_RATIO);
            case AVERAGED -> String.format(
                    "구간이 시정수 τ=%.0f초의 %.1f배보다 짧아 열용량이 부하 변동을 전부 평균해버린다 — 온도가 거의 흔들리지 않아 방열판 차이가 드러나지 않는다.",
                    tauSec, AVERAGED_RATIO);
        };
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
