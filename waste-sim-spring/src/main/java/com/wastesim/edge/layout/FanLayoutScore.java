package com.wastesim.edge.layout;

import com.wastesim.edge.FanArraySpec;

/**
 * 배치 하나의 평가 결과.
 *
 * <h3>온도는 1급 지표가 아니다</h3>
 * {@code advisory*} 필드는 "무팬 82 ℃"라는 <b>이 모델 안에서만 의미가 있는 앵커</b>에서
 * 선형으로 환산한 값이다. 기존 {@code simulate_edge_throttling}이나
 * {@code simulate_heatsink_layout}은 열저항·주변온도·부하 프로파일에서 온도를 계산하므로,
 * 같은 조건에서도 두 도구의 숫자가 다르다. 그래서 판단 기준은
 * {@link #coolingScore}·{@link #stagnationRisk}·{@link #advisorySpreadC}이고,
 * 온도는 응답에서 별도 블록으로 격리해 경고와 함께 내보낸다(설계 §4.4).
 *
 * @param coolingScore      상대 냉각 점수. 클수록 좋다
 * @param flowType          기류 유형
 * @param pairFactor        방향 조합 계수(점수 재현용으로 남긴다)
 * @param flowBonus         기류 보정(점수 재현용으로 남긴다)
 * @param advisoryPeakTempC 참고용 예상 최고온도(℃) — 시뮬레이터 결과와 비교 불가
 * @param advisoryMeanTempC 참고용 예상 평균온도(℃)
 * @param advisorySpreadC   참고용 예상 위치편차(℃). 작을수록 온도가 고르다
 * @param stagnationRisk    공기 정체 위험
 * @param interpretation    기류 해석 한 줄(한국어)
 * @param sourceStatus      항상 PRELIMINARY_ESTIMATE
 */
public record FanLayoutScore(double coolingScore,
                             FlowType flowType,
                             double pairFactor,
                             double flowBonus,
                             double advisoryPeakTempC,
                             double advisoryMeanTempC,
                             double advisorySpreadC,
                             StagnationRisk stagnationRisk,
                             String interpretation,
                             FanArraySpec.SourceStatus sourceStatus) {

    /** 두 팬이 만드는 함체 기류의 유형. */
    public enum FlowType {
        /** 한쪽이 넣고 다른 쪽이 뺀다 — 유로가 정해진다. */
        FORCED_THROUGH_FLOW("강제 관통류"),
        /** 둘 다 흡기 — 내부가 양압이 되고 배출은 틈에 맡긴다. */
        POSITIVE_PRESSURE("양압/자연배출"),
        /** 둘 다 배기 — 내부가 음압이 되고 흡기는 틈에 맡긴다. */
        NEGATIVE_PRESSURE("음압/자연흡기");

        private final String koLabel;
        FlowType(String koLabel) { this.koLabel = koLabel; }
        public String koLabel() { return koLabel; }
        public String wire() { return name(); }
    }

    /** 공기가 고여 국소 과열이 생길 위험. 점수에서 결정론적으로 정한다. */
    public enum StagnationRisk {
        LOW("낮음"), MEDIUM("보통"), HIGH("높음");

        private final String koLabel;
        StagnationRisk(String koLabel) { this.koLabel = koLabel; }
        public String koLabel() { return koLabel; }
        public String wire() { return name(); }
    }
}
