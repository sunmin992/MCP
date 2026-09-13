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

    /**
     * 이 규칙이 읽는 답변 필드. <b>이름의 정본은 {@code SesFieldMapping}의 커플링 바인딩이다.</b>
     *
     * <p>상수로 드러내는 이유는 시험이 대조할 자리를 만들기 위해서다. 여기 적힌 이름이 SES
     * 쪽과 갈라지면 규칙은 답을 영영 찾지 못해 {@link Activation#UNKNOWN}만 돌려주고, 모든
     * 실행이 {@code activation_unknown}으로 막힌다 — 아무것도 터지지 않은 채로.
     */
    public static final String TRAFFIC_MODE_FIELD = "trafficMode";

    /** 교통 결합을 살리는 답변값. 정본은 같은 바인딩의 허용값 목록이다. */
    public static final String TRAFFIC_APPLY_VALUE = "APPLY";

    private static final RuleRegistry REGISTRY = new RuleRegistry()
            .register(TRAFFIC_APPLY,
                    RuleRegistry.fieldEquals(TRAFFIC_MODE_FIELD, TRAFFIC_APPLY_VALUE));

    private JangnyangRules() { }

    public static RuleRegistry registry() {
        return REGISTRY;
    }
}
