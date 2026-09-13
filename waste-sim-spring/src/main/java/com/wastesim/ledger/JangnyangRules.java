package com.wastesim.ledger;

/**
 * 장량동 시뮬레이터의 활성 규칙집.
 *
 * <p>지금 조건부 결합은 교통 둘뿐이다({@code SesPruner} 주석 참고). 규칙을 미리 늘리지
 * 않는 이유는, 쓰이지 않는 규칙은 틀려도 아무도 모르기 때문이다.
 */
public final class JangnyangRules {

    /** 교통 결합이 사는 조건. 답변 하나가 값이 아니라 연결 구조를 바꾸는 자리다. */
    public static final String TRAFFIC_APPLY = "traffic-apply";

    private static final RuleRegistry REGISTRY = new RuleRegistry()
            .register(TRAFFIC_APPLY, RuleRegistry.fieldEquals("trafficMode", "APPLY"));

    private JangnyangRules() { }

    public static RuleRegistry registry() {
        return REGISTRY;
    }
}
