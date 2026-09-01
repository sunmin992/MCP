package com.wastesim.model;

/**
 * 구간 이동시간을 무엇으로 계산할 것인가.
 *
 * <p>기본은 {@link #LEGACY_CONSTANT}이며 <b>기존 결과를 그대로 낸다</b>. 실제 도로 기반
 * 계산은 선택 기능이고, 재 보기 전에는 기본값을 바꾸지 않는다.
 */
public enum TravelTimeMode {

    /**
     * 구간마다 같은 상수(분)를 쓰고 기동성·혼잡 가중치로 보정한다. v1.0부터의 계산이다.
     *
     * <p>이 상수(기본 15분)에는 <b>이동뿐 아니라 정차·상차 시간이 섞여 있을 수 있다.</b>
     * 원본 DEVS 모델에도 Java 엔진에도 지점별 서비스 시간이 따로 없어서, 한 값이 둘을
     * 함께 떠맡아 왔다. 실측과 대조해 보면 이 값이 이동시간만으로는 지나치게 크다 —
     * 장량동 확정 좌표 기준 구간 실측이 2.5~3.2분인데 15분을 쓴다.
     */
    LEGACY_CONSTANT,

    /**
     * 실제 도로 자유주행시간에 시간대 혼잡 보정을 곱하고, 지점별 정차시간을 더한다.
     *
     * <p>자유주행시간은 <b>미리 계산된 행렬</b>에서 읽는다({@code TravelTimeMatrix}).
     * 시뮬레이션 도중 OSRM을 부르지 않는 이유는 재현성이다(NFR-02) — 같은 시드·같은
     * 파라미터가 같은 결과를 내야 하는데, 외부 서비스 호출은 그 보장을 깨고 네트워크
     * 가용성에 결과를 묶는다.
     *
     * <p>행렬이 경로의 모든 구간을 덮지 못하면 <b>실행을 막는다.</b> 조용히 상수 모드로
     * 되돌리면 두 모드의 결과가 구별되지 않아, 무엇으로 계산한 값인지 알 수 없게 된다.
     */
    OSRM_HYBRID;

    /** 이름 → enum (대소문자 무관, 하이픈 허용). 알 수 없으면 예외. */
    public static TravelTimeMode fromName(String name) {
        if (name == null || name.isBlank()) return LEGACY_CONSTANT;
        String v = name.trim().replace('-', '_').toUpperCase();
        for (TravelTimeMode m : values()) {
            if (m.name().equals(v)) return m;
        }
        throw new IllegalArgumentException("Unknown travel time mode: " + name);
    }
}
