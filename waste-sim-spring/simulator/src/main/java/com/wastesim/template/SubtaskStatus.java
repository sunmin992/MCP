package com.wastesim.template;

/**
 * 서브태스크 인스턴스의 상태.
 *
 * <p>{@link #NOT_GENERATED}와 {@link #DEFERRED}를 가르는 것이 핵심이다. 앞은 "만들 필요가
 * 없다고 <b>판정했다</b>"이고 뒤는 "아직 <b>판정할 수 없다</b>"이다. 둘을 뭉치면 조건이
 * 나중에 참이 되어도 그 결정을 영영 묻지 않는다.
 */
public enum SubtaskStatus {

    /** 값이 정해졌다. */
    FILLED,

    /** 생성됐지만 값이 없다. 물어야 한다. */
    UNFILLED,

    /** 생성 조건을 아직 판정할 수 없다. 의존하는 답이 들어오면 다시 본다. */
    DEFERRED,

    /** 생성 조건이 거짓이다. 묻지 않는다. */
    NOT_GENERATED
}
