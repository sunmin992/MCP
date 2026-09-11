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

    /**
     * 루트 aspect가 갈리는 세 가지 중 이 이름 아래는 <b>출력 가지</b>다. 대상 시스템·실험은
     * 사용자가 채우는 입력이지만, 관측은 시뮬레이션을 돌려야 나오는 값이다 — 속성을 물어보면
     * "총민원을 몇 건으로 하시겠습니까"가 돼 버린다. 이름을 하드코딩해 걸러내는 임의의 예외가
     * 아니라, 루트에서의 경로(두 번째 마디)로 판정하는 SES 구조 자체의 규칙이다.
     */
    private static final String OUTPUT_BRANCH_ROOT = "관측";

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

            // 관측 가지 아래에서는 spec·multi는 그대로 두되(현재 관측 가지에는 없다) 속성만
            // 제외한다 — 값을 미리 정하는 것이 아니라 실행 결과로 채워지는 자리이기 때문이다.
            if (isOutputBranch(structure, entity.name())) continue;

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

    /**
     * 루트에서 이 엔티티까지의 경로 두 번째 마디가 "관측"이면 출력 가지다. 루트 자체나
     * 루트에 닿지 않는 이름(있을 리 없지만 방어적으로)은 제외 대상이 아니다.
     */
    private static boolean isOutputBranch(EntityStructure structure, String entityName) {
        List<String> path;
        try {
            path = structure.pathTo(entityName);
        } catch (IllegalArgumentException notReachable) {
            return false;
        }
        return path.size() >= 2 && OUTPUT_BRANCH_ROOT.equals(path.get(1));
    }
}
