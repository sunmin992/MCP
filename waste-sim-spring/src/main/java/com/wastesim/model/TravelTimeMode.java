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
     * 함께 떠맡아 왔다. OSRM 산출 자유주행시간과 대조해 보면 이동시간만으로는 지나치게 크다 —
     * 장량동 확정 구역 좌표 기준 구간이 2.5~3.2분인데 15분을 쓴다.
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
    OSRM_HYBRID,

    /**
     * 수거 지점 좌표가 없을 때 <b>교통 구역으로 근사</b>한다. 지금 당장 쓸 수 있는 모드다.
     *
     * <p>각 수거 지점은 좌표 없이 소속 교통 구역만 갖는다. 구간을 계산할 때 두 지점의 구역을
     * 보고 —
     *
     * <ul>
     *   <li><b>구역이 다르면</b> 구역 간 자유주행시간(traffic/jangryang-zone-travel-times.json)
     *       에 시간대 혼잡을 곱한다.</li>
     *   <li><b>구역이 같으면</b> {@code intraZoneTravelMinutes}(구역 내 평균 이동시간)를 쓴다 —
     *       구역 간 행렬에는 대각 성분이 없고, 있을 수도 없다.</li>
     * </ul>
     *
     * <p>여기에 도착 지점의 정차·상차 시간을 더한다.
     *
     * <p><b>지점 단위 경로 비교에는 쓸 수 없다.</b> 같은 구역 안에서 어느 순서로 도는지가
     * 결과에 반영되지 않기 때문이다 — 구역 내 이동이 전부 같은 평균값이 된다. 결과에는
     * {@link CoordinateQuality#TRAFFIC_ZONE_PROXY} 표시가 붙는다.
     */
    ZONE_PROXY_HYBRID;

    /**
     * 이 모드<b>만으로</b> 좌표 품질이 정해지는가.
     *
     * <p>{@code LEGACY_CONSTANT}는 좌표를 쓰지 않고, {@code ZONE_PROXY_HYBRID}는 정의상 구역
     * 근사다. 하지만 {@code OSRM_HYBRID}는 정해지지 않는다 — 같은 계산을 현장 GPS 좌표로도,
     * 주소 지오코딩 좌표로도 할 수 있다. 그 구분은 <b>행렬 파일이 자신의 출처로 선언</b>하는
     * 것이고 모드는 알 수 없다.
     *
     * <p>두 축을 갈라 놓고도 모드가 품질을 단정해 버리면 분리한 의미가 없어서, 여기서 세
     * 모드를 한꺼번에 정하지 않는다.
     *
     * @return 모드만으로 정해지는 품질, 아니면 비어 있음 — 호출부가 행렬의
     *         {@code coordinateSource}를 봐야 한다
     */
    public java.util.Optional<CoordinateQuality> intrinsicCoordinateQuality() {
        return switch (this) {
            case LEGACY_CONSTANT -> java.util.Optional.of(CoordinateQuality.NOT_USED);
            case ZONE_PROXY_HYBRID -> java.util.Optional.of(CoordinateQuality.TRAFFIC_ZONE_PROXY);
            case OSRM_HYBRID -> java.util.Optional.empty();
        };
    }

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
