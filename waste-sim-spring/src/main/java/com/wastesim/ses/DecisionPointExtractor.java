package com.wastesim.ses;

import java.util.ArrayList;
import java.util.List;

/**
 * 트리를 훑어 결정해야 할 자리를 뽑는다.
 *
 * <p><b>aspect에서는 아무것도 나오지 않는다.</b> "…로 구성됨"은 다 함께 있다는 뜻이므로
 * 고를 것이 없다. 물어야 하는 것은 "…중 하나"(spec)와 "몇 개"(multi)와 값이다.
 */
public final class DecisionPointExtractor {

    /** multi 보유자가 복제 수를 적는 속성 이름. 이 속성은 MultiCount와 같은 결정이다. */
    private static final String COUNT_ATTRIBUTE = "개수";

    private DecisionPointExtractor() { }

    public static List<DecisionPoint> extract(EntityStructure structure) {
        List<DecisionPoint> points = new ArrayList<>();

        for (SesEntity entity : structure.entities()) {
            boolean isMultiHolder = entity.multiAspect().isPresent();

            entity.multiAspect().ifPresent(multi -> points.add(
                    new DecisionPoint.MultiCount(entity.name(), multi.children().get(0))));

            for (Decomposition axis : entity.specAxes()) {
                points.add(new DecisionPoint.SpecChoice(entity.name(), axis.name(), axis.children()));
            }

            for (String attribute : entity.attributes()) {
                // 복제 수를 두 번 묻지 않는다 — MultiCount가 이미 그 결정이다.
                if (isMultiHolder && COUNT_ATTRIBUTE.equals(attribute)) continue;
                points.add(new DecisionPoint.AttributeValue(entity.name(), attribute));
            }
        }

        for (Coupling c : structure.couplings()) {
            if (c.activeWhen() != null && !c.activeWhen().isBlank()) {
                points.add(new DecisionPoint.CouplingActivation(c.from(), c.to(), c.activeWhen()));
            }
        }

        return List.copyOf(points);
    }
}
