package com.wastesim.model;

import java.util.List;
import java.util.Locale;

/**
 * 건물을 교통 구역에 배정하는 <b>가정</b>. 실제 위치 조사가 아니다.
 *
 * <h2>왜 이것이 필요한가</h2>
 *
 * <p>{@link TravelTimeMode#ZONE_PROXY_HYBRID}는 각 지점이 어느 구역에 속하는지만 알면
 * 구역 간 이동에 OSRM 산출 시간을 쓸 수 있다. 그런데 그 매핑이 없다 — 수거 지점이 0곳이라
 * {@code CollectionSiteRegistry}가 비어 있고, 폴백으로 지점 id를 그대로 구역 id로 쓴다.
 * 그래서 4동까지는 <b>이름이 겹쳐서 우연히</b> 통과하고 5동부터 막혔다({@code Node_E}라는
 * 구역이 없다). 용량 축이 작동하는 26동 규모와 이동시간 축을 함께 쓸 수 없던 이유다.
 *
 * <p>빠진 것은 좌표가 아니라 <b>배정</b>이다. "이 건물은 A구역"은 좌표 없이도 적을 수 있다.
 *
 * <h2>이 규칙은 데이터가 아니다</h2>
 *
 * <p>장량동 건물이 실제로 어느 구역에 있는지는 조사해야 아는 사실이고, 여기 있는 규칙은
 * 그것을 <b>대신하지 않는다.</b> 규칙으로 배정한 결과는 운영 예측이 아니라 <b>가정 비교
 * 실험</b>이며, {@link DataQualityFlag#ZONE_ASSIGNMENT_ASSUMED}로 결과에 표시된다.
 *
 * <p>실제 지점이 {@code trafficZone}을 갖고 등록되면 <b>그쪽이 규칙을 이긴다.</b> 조사한
 * 사실이 가정을 밀어내는 방향이라야 한다.
 *
 * <h2>두 규칙을 함께 돌려 범위를 얻는다</h2>
 *
 * <p>{@link #CONTIGUOUS}와 {@link #ROUND_ROBIN}은 같은 건물 수를 정반대로 배정하므로,
 * 실제 배치가 무엇이든 그 사이에 있다. 한쪽 값을 답으로 인용하는 것이 아니라 <b>두 값을
 * 범위로 보고하는 것</b>이 이 규칙들의 용도다.
 *
 * <ul>
 *   <li>{@code CONTIGUOUS}: 구역 변경이 최소(구역 수 − 1회). 구역 내 이동이 대부분이라
 *       {@code intraZoneTravelMinutes}가 순회 시간을 지배한다 — 실측 함량이 가장 낮은 쪽.</li>
 *   <li>{@code ROUND_ROBIN}: 모든 구간이 구역을 넘는다. OSRM 산출 시간이 순회 시간을
 *       지배하고 구역 내 이동은 한 번도 쓰이지 않는다 — 실측 함량이 가장 높은 쪽.</li>
 * </ul>
 */
public enum ZoneAssignmentRule {

    /**
     * 배정하지 않는다. 지점 id를 그대로 구역 id로 보는 기존 폴백이며, 이름이 겹치는
     * 4동까지만 작동한다. <b>기본값</b> — 아무것도 지정하지 않은 실행의 결과를 바꾸지 않는다.
     */
    NONE("배정 없음"),

    /**
     * 앞에서부터 연속 블록으로 나눈다. 26동 · 4구역이면 A구역에 7동, 나머지에 6~7동씩이고
     * 구역 변경은 3회뿐이다.
     *
     * <p>"같은 구역의 건물들이 인접해 있고 차량이 구역 단위로 훑는다"는 가정에 해당한다.
     * 구역 내 이동이 22/25 구간이 되므로 순회 시간은 거의 전부 가정값에서 나온다.
     */
    CONTIGUOUS("연속 블록"),

    /**
     * 번갈아 배정한다. 26동 · 4구역이면 A,B,C,D,A,B,C,D,… 이고 <b>모든 구간이 구역을
     * 넘는다.</b>
     *
     * <p>"건물이 구역에 흩어져 있고 차량이 구역을 계속 오간다"는 가정에 해당한다. 구역 내
     * 이동이 한 번도 없으므로 {@code intraZoneTravelMinutes}는 결과에 관여하지 않고,
     * 순회 시간이 전부 OSRM 산출 구역 간 시간에서 나온다.
     */
    ROUND_ROBIN("번갈아 배정");

    public final String labelKo;

    ZoneAssignmentRule(String labelKo) {
        this.labelKo = labelKo;
    }

    /** 규칙으로 배정하는가. {@link #NONE}만 거짓이다. */
    public boolean assigns() {
        return this != NONE;
    }

    /**
     * 이 건물이 속할 구역. {@link #NONE}이거나 구역 목록이 비었으면 비어 있다.
     *
     * @param buildingIndex  0부터 시작하는 건물 인덱스
     * @param totalBuildings 전체 건물 수 — {@link #CONTIGUOUS}가 블록 크기를 정하는 데 쓴다
     * @param zoneIds        배정 대상 구역. <b>호출부가 정렬해서 준다</b> — 순서가 흔들리면
     *                       같은 설정이 다른 배정을 내고 재현성(NFR-02)이 깨진다.
     */
    public java.util.Optional<String> assign(int buildingIndex, int totalBuildings,
                                             List<String> zoneIds) {
        if (this == NONE || zoneIds == null || zoneIds.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (buildingIndex < 0) return java.util.Optional.empty();
        int z = zoneIds.size();
        int index = switch (this) {
            // 연속 블록 — 건물 인덱스를 구역 수로 비례 배분한다. totalBuildings가 0 이하로
            // 들어오면 나눗셈이 깨지므로 최소 1로 막는다.
            case CONTIGUOUS -> Math.min(z - 1, buildingIndex * z / Math.max(1, totalBuildings));
            case ROUND_ROBIN -> buildingIndex % z;
            case NONE -> -1;
        };
        return index < 0 ? java.util.Optional.empty() : java.util.Optional.of(zoneIds.get(index));
    }

    /** 이름 → 규칙. 대소문자·하이픈 무관. 알 수 없으면 예외(검증기가 잡는다). */
    public static ZoneAssignmentRule fromName(String name) {
        if (name == null || name.isBlank()) return NONE;
        String key = name.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (ZoneAssignmentRule r : values()) {
            if (r.name().equals(key) || r.labelKo.equals(name.trim())) return r;
        }
        throw new IllegalArgumentException("알 수 없는 구역 배정 규칙: " + name
                + " (허용: NONE, CONTIGUOUS, ROUND_ROBIN)");
    }
}
