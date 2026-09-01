package com.wastesim.site;

/**
 * 쓰레기를 배출하는 수거 대상 한 곳과 그 실제 좌표.
 *
 * <p><b>교통 앵커와 다른 것이다.</b> {@code traffic/jangryang-nodes.json}의 Node_A~D는 실측
 * 교통량 링크의 귀속점이고 — 학교·사거리·아파트가 그 자리에 오는 이유다 — 이 레코드는
 * 쓰레기가 나오는 곳이다. 하나의 교통 앵커를 여러 수거 지점이 공유할 수 있다.
 *
 * <p>v1.12까지 이 둘은 {@code Node_A} 하나로 겹쳐 있었고, 그래서 "노드가 교통 관측 지점인가
 * 수거 대상 건물인가"라는 질문에 코드 안에서 답이 나오지 않았다. 답은 둘 다이며 서로 다른
 * 것이었다.
 *
 * @param id            지점 id. {@code Node_A}~{@code Node_Z} — 엔진이 건물 인덱스에 붙이는
 *                      라벨, 서브태스크 ST-005가 사용자에게 받는 이름과 같은 체계다.
 * @param longitude     경도(WGS84)
 * @param latitude      위도(WGS84)
 * @param name          사람이 알아보는 이름
 * @param adminDivision 행정동 — 이 프로젝트는 장량동만 다루므로 확인 대상이다
 * @param source        좌표의 출처. <b>무엇을 보고 이 좌표를 정했는지</b>가 들어간다 —
 *                      비어 있으면 다음 사람은 그 좌표를 신뢰할 근거가 없다
 * @param snapMeters    OSRM이 이 좌표를 도로로 스냅하며 밀린 거리(m). 임계값을 넘으면
 *                      그 지점의 이동시간은 요청한 위치의 값이 아니다
 */
public record CollectionSite(
        String id,
        double longitude,
        double latitude,
        String name,
        String adminDivision,
        String source,
        double snapMeters) {
}
