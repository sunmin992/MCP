package com.wastesim.ses;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DecisionPointExtractorTest {

    private static List<DecisionPoint> points() {
        return DecisionPointExtractor.extract(JangnyangEntityStructure.get());
    }

    @Test
    void specAxesBecomeChoicesWithOptionsFromChildren() {
        DecisionPoint.SpecChoice truckType = points().stream()
                .filter(DecisionPoint.SpecChoice.class::isInstance)
                .map(DecisionPoint.SpecChoice.class::cast)
                .filter(p -> p.entity().equals("수거차량"))
                .findFirst().orElseThrow();
        assertEquals("차종 축", truckType.axis());
        assertEquals(List.of("5톤 차량", "2.5톤 차량", "1톤 차량"), truckType.options());
    }

    @Test
    void residentHasTwoSpecAxes() {
        long axes = points().stream()
                .filter(DecisionPoint.SpecChoice.class::isInstance)
                .filter(p -> p.entity().equals("거주민"))
                .count();
        assertEquals(2, axes, "거주민은 직업 축과 배출시각 모델 축 둘을 갖는다(CP-5)");
    }

    @Test
    void multiHolderYieldsCountAndNotDuplicateAttribute() {
        List<DecisionPoint> ps = points();
        assertTrue(ps.stream().anyMatch(p -> p.id().equals("multi:수거차량 집합")));
        assertFalse(ps.stream().anyMatch(p -> p.id().equals("attr:수거차량 집합:개수")),
                "multi 보유자의 개수 속성은 MultiCount와 같은 결정이므로 두 번 세지 않는다");
    }

    @Test
    void otherAttributesOfMultiHolderSurvive() {
        assertTrue(points().stream().anyMatch(p -> p.id().equals("attr:거주민 집합:직업구성")),
                "개수 말고 다른 속성은 그대로 남는다");
    }

    @Test
    void conditionalCouplingsBecomeActivationPoints() {
        List<DecisionPoint> activations = points().stream()
                .filter(DecisionPoint.CouplingActivation.class::isInstance)
                .toList();
        assertEquals(2, activations.size(), "교통 결합 둘만 조건부다");
    }

    @Test
    void aspectsProduceNoDecision() {
        assertFalse(points().stream().anyMatch(p -> p.id().startsWith("aspect:")),
                "aspect는 다 함께 있는 것이므로 고를 것이 없다");
    }

    @Test
    void observationBranchAttributesAreNotDecisionPoints() {
        assertFalse(points().stream().anyMatch(p -> p.id().equals("attr:민원 통계:총민원")),
                "관측은 시뮬레이션이 만들어 내는 값이지 사용자가 정하는 값이 아니다");
    }

    @Test
    void idsAreUnique() {
        List<DecisionPoint> ps = points();
        Set<String> ids = ps.stream().map(DecisionPoint::id).collect(Collectors.toSet());
        assertEquals(ps.size(), ids.size(), "지점 id가 겹치면 문항이 서로를 덮어쓴다");
    }
}
