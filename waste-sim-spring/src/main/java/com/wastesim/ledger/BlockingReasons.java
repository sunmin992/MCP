package com.wastesim.ledger;

/**
 * 실행을 막은 사유의 어휘.
 *
 * <p><b>왜 한자리에 모으는가</b>: 차단 사유는 원장 밖으로 나가 보고서와 시험이 문자열로
 * 대조하는 값이다. 만드는 쪽이 여러 클래스에 흩어져 있으면 같은 사유가 두 가지 철자로
 * 쌓이고, 그러면 "무엇 때문에 막혔는가"를 세는 일이 조용히 틀린다 —
 * {@link ValueSource#NOT_APPLICABLE_BY_RULE}을 상수로 못박은 것과 같은 이유다.
 *
 * <p>시험 쪽은 일부러 날문자열을 쓴다. 시험이 이 상수를 참조하면 값을 바꿔도 시험이
 * 따라 바뀌어, 아무것도 고정하지 못한다.
 */
public final class BlockingReasons {

    private BlockingReasons() { }

    /** 활성 가지인데 쓸 수 있는 값이 없다. */
    public static final String REQUIRED_VALUE_UNRESOLVED = "required_value_unresolved";

    /** 활성 조건이 의존하는 값이 아직 없어 이 가지가 사는지 죽는지 모른다. */
    public static final String ACTIVATION_UNKNOWN = "activation_unknown";

    /** 이 값이 딛고 있던 상위 값이 바뀌었다. */
    public static final String UPSTREAM_VALUE_CHANGED = "upstream_value_changed";

    /** 도구 호출이 시간 안에 돌아오지 않았다. 재요청하지 않는다. */
    public static final String TOOL_TIMEOUT = "tool_timeout";

    /** 출처가 허용 나이를 넘겼다. */
    public static final String SOURCE_EXPIRED = "source_expired";

    /** 세트 버전이 바뀌어 이전 판정의 전제가 통째로 달라졌다. */
    public static final String SET_VERSION_CHANGED = "set_version_changed";
}
