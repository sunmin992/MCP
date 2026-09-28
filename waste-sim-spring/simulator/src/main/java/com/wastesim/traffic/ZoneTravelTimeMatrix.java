package com.wastesim.traffic;

import org.springframework.stereotype.Component;

/**
 * <b>교통 구역</b> 사이의 자유주행시간. {@code ZONE_PROXY_HYBRID} 모드가 읽는다.
 *
 * <p>이 값은 OSRM이 OpenStreetMap 도로망 형상과 가정 속도로 산출한 것이고 <b>현장 주행
 * 기록이 아니다.</b> 청소차가 실제로 그 구간을 달린 시간을 잰 것이 아니므로 차량 특성·정차·
 * 신호 대기가 반영돼 있지 않다.
 *
 * <p>{@link TravelTimeMatrix}와 같은 파서를 쓰지만 <b>다른 빈이고 다른 파일</b>이다. 수거
 * 지점 간 행렬과 구역 간 행렬을 한 빈에 담으면, 구간 키가 양쪽 모두 {@code Node_A->Node_B}
 * 형태라서 어느 이름공간의 값인지 구별할 수 없다 — 이 프로젝트가 하루를 들여 갈라낸 구분이
 * 바로 그것이다.
 *
 * <p>구역 간 행렬에는 <b>대각 성분이 없다.</b> 구역은 점이 아니라 영역이라 자기 자신까지의
 * 주행시간이 정의되지 않는다. 같은 구역 안의 이동은
 * {@code SimulationConfig.intraZoneTravelMinutes}가 담당한다.
 */
@Component
public class ZoneTravelTimeMatrix extends TravelTimeMatrix {

    public ZoneTravelTimeMatrix() {
        super(ZONE_RESOURCE);
    }
}
