package com.wastesim.site;

/**
 * V-T3(대형 차량 진입 불가) 검증에 쓰는 <b>가상</b> 수거 지점.
 *
 * <p>장량동의 실제 네 지점은 확정 좌표 기준으로 모두 간선 도로에 접해 있어 대형 차량이
 * 들어간다. 그래서 운영 데이터에는 골목이 하나도 없고 V-T3는 실제 데이터로 발동하지 않는다 —
 * 그것이 사실에 맞는 결과다. 검증 로직 자체는 계속 지켜야 하므로 가상 지점을 여기 둔다.
 * 테스트를 통과시키려고 운영 데이터를 사실과 다르게 두지 않기 위한 분리다.
 */
public final class TestSites {

    private TestSites() {}

    /**
     * 네 지점이 모두 <b>같은</b> 교통 구역(Node_A)에 속하는 가상 지점 집합.
     *
     * <p>{@code ZONE_PROXY_HYBRID}의 구역 내 이동을 확인하려면 그런 상황이 필요하다. 확정된
     * 네 지점은 서로 다른 구역에 흩어져 있어 구역 내 이동이 한 번도 일어나지 않는다 — 그것이
     * 사실에 맞는 상태이므로 운영 데이터를 바꾸지 않고 여기에 가상 집합을 둔다.
     */
    public static CollectionSiteRegistry allInZoneA() {
        CollectionSiteRegistry r = new CollectionSiteRegistry("/collection/test-one-zone-sites.json");
        r.load();
        return r;
    }

    /** Node_C·Node_D가 골목인 가상 지점 집합. */
    public static CollectionSiteRegistry withAlleys() {
        CollectionSiteRegistry r = new CollectionSiteRegistry("/collection/test-alley-sites.json");
        r.load();
        return r;
    }
}
