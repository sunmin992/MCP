package com.wastesim.ses;

import java.util.List;

/**
 * SES 트리에서 <b>사용자가 정해 주어야 트리가 닫히는 자리</b>.
 *
 * <p>가설이 걸린 자리다 — "SES로 변환하면 무엇을 물어야 하는지가 정해진다"는 주장은,
 * 이 목록이 서브태스크 문항 목록과 맞아떨어지는가로 검증된다.
 *
 * <p>종류마다 SES가 알려 주는 양이 다르다. {@link SpecChoice}는 허용값까지 구조에서
 * 나오지만, {@link AttributeValue}는 "물어야 한다"까지만 나온다 — 속성에는 자료형이 없다.
 */
public sealed interface DecisionPoint {

    String id();

    String entity();

    /** …중 하나. 축의 자식들이 그대로 허용값이 된다. */
    record SpecChoice(String entity, String axis, List<String> options) implements DecisionPoint {
        public SpecChoice {
            options = List.copyOf(options);
        }

        @Override
        public String id() {
            return "spec:" + entity + ":" + axis;
        }
    }

    /**
     * 동종 개체를 몇 개 둘 것인가.
     *
     * <p>M1 — 이전에는 복제되는 개체 이름({@code member}, 예: "거주민")도 필드로 들고
     * 있었지만 채우기만 하고 아무도 읽지 않았다(YAGNI). 필요해지면
     * {@code DecisionPointExtractor}가 만드는 자리에서 {@code multi.children().get(0)}로
     * 다시 얻을 수 있으니, 쓰지 않는 필드를 미리 들고 다니지 않는다.
     */
    record MultiCount(String entity) implements DecisionPoint {
        @Override
        public String id() {
            return "multi:" + entity;
        }
    }

    /** 속성 하나의 값. */
    record AttributeValue(String entity, String attribute) implements DecisionPoint {
        @Override
        public String id() {
            return "attr:" + entity + ":" + attribute;
        }
    }

    /**
     * 이 결합을 살릴 것인가. 값이 아니라 <b>연결 구조</b>를 정하는 답이다 — 교통을 끄면
     * 교통 구역에서 나가는 두 선이 통째로 죽는다.
     */
    record CouplingActivation(String couplingFrom, String couplingTo, String condition)
            implements DecisionPoint {
        @Override
        public String id() {
            return "coupling:" + couplingFrom + "->" + couplingTo;
        }

        @Override
        public String entity() {
            return couplingFrom;
        }
    }
}
