package com.wastesim.ses;

import java.util.List;
import java.util.Optional;

/** SES 트리의 마디 하나 — 이름과 속성, 그리고 아래로 여는 방식들. */
public record SesEntity(String name, List<String> attributes, List<Decomposition> decompositions) {

    public SesEntity {
        attributes = List.copyOf(attributes);
        decompositions = List.copyOf(decompositions);
    }

    /**
     * 이 엔티티가 가진 spec 축들. 하나일 거라고 가정하지 않는다 — 거주민은 직업 축과
     * 배출시각 모델 축 둘을 갖는다(ref-v7 CP-5).
     */
    public List<Decomposition> specAxes() {
        return decompositions.stream()
                .filter(d -> d.kind() == Decomposition.Kind.SPEC)
                .toList();
    }

    public Optional<Decomposition> multiAspect() {
        return decompositions.stream()
                .filter(d -> d.kind() == Decomposition.Kind.MULTI)
                .findFirst();
    }
}
