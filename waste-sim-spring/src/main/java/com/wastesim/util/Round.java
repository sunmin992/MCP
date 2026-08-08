package com.wastesim.util;

/**
 * 표시용 반올림 헬퍼 — 내부 계산은 원본 정밀도로 하고 결과를 낼 때만 자른다.
 * 시뮬레이션 엔진·서비스·엣지 보정기가 공유해 동일한 반올림 규칙을 쓰도록 한 곳에 모았다.
 */
public final class Round {

    private Round() {}

    /** 소수 첫째 자리까지 반올림. */
    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 소수 둘째 자리까지 반올림. */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
