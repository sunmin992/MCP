package com.wastesim.model;

/**
 * 주민이 쓰레기를 <b>언제</b> 내놓는가.
 *
 * <p>기본은 {@link #PAPER_BASELINE}이며 기존 결과를 그대로 낸다. 실제 규정 기반 모드는
 * 선택 기능이고, 두 모드의 차이가 결과를 얼마나 바꾸는지 자체가 볼 만한 결과이므로
 * 논문 모델을 지우지 않는다.
 */
public enum DischargeTimeMode {

    /**
     * 논문 모델 — 주민은 <b>집을 나설 때</b> 버린다. 직업별 외출 시각이 배출 시각이다
     * (생산직 07:22 · 학생 08:58 · 주부 14:00, 표준편차 {@code leaveSigma}).
     * Choi 외(2020) Table 1, 갤럽 2014 기준.
     */
    PAPER_BASELINE,

    /**
     * 포항시 실제 규정 — 배출 허용 창(기본 20:00~06:00) 안에서 버린다.
     *
     * <p>창 안의 분포는 <b>균등</b>이다. 공식 데이터가 주는 것은 허용 창뿐이고 그 10시간 중
     * 언제 버리는지는 어디에도 없어서, 창만 아는 상태에서 가장 적은 가정을 얹는 선택이다.
     * 20시 직후에 몰린다거나 자정 무렵이 많다거나 하는 형태는 근거가 없으므로 넣지 않았다.
     *
     * <p><b>이 모드에서는 직업이 배출 시각에 영향을 주지 않는다.</b> 이 엔진에서 직업이
     * 좌우하는 것은 외출·귀가 시각뿐이고 배출량은 전역값({@code wasteMeanKg})이라, 시각이
     * 균등분포로 바뀌면 직업 구성비가 결과를 바꾸지 못한다. 논문 모델의 "직업 구성이 민원에
     * 영향을 준다"는 기제가 이 모드에서는 사라진다 — 데이터가 그 기제를 뒷받침하지 않는다는
     * 뜻이며, 감춰야 할 것이 아니라 이 모드를 고를 때 알아야 할 사실이다.
     */
    POHANG_ACTUAL;

    /** 이름 → enum (대소문자 무관, 하이픈 허용). 값이 없으면 논문 모델. */
    public static DischargeTimeMode fromName(String name) {
        if (name == null || name.isBlank()) return PAPER_BASELINE;
        String v = name.trim().replace('-', '_').toUpperCase();
        for (DischargeTimeMode m : values()) {
            if (m.name().equals(v)) return m;
        }
        throw new IllegalArgumentException("Unknown discharge time mode: " + name);
    }
}
