package com.wastesim.ses;

import java.util.List;

/**
 * 엔티티 하나를 아래로 여는 방식. SES가 세 가지만 두는 것이 이 구조의 요점이다 —
 * "…로 구성됨"(aspect), "…중 하나"(spec), "동종 개체 다수"(multi).
 *
 * <p>이 셋이 그대로 <b>사용자에게 물어봐야 할 것의 종류</b>가 된다. spec은 골라야 하고,
 * multi는 몇 개인지 정해야 한다. aspect는 묻지 않는다 — 다 함께 있는 것이므로 고를 것이 없다.
 */
public record Decomposition(Kind kind, String name, List<String> children) {

    public enum Kind { ASPECT, SPEC, MULTI }

    public Decomposition {
        children = List.copyOf(children);
    }
}
