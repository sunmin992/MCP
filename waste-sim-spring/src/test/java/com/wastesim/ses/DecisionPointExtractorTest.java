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

    /**
     * I4 — 이 연구가 재려는 숫자(총 결정 지점과 종류별 개수)를 못 박는다. 지금까지는
     * 이 개수를 아무 테스트도 단언하지 않아서, 트리가 바뀌어 지점이 늘거나 줄어도
     * 아무도 모르게 지나갔다. 값은 {@code 유도본-v4-대조.md}의 "SES에 자리가 있는데
     * 묻지 않는 27곳" 절과 이 테스트가 서로를 지켜야 한다 — 트리가 바뀌면 둘 다
     * 같이 갱신해야 한다.
     */
    @Test
    void totalDecisionPointCountsArePinned() {
        List<DecisionPoint> ps = points();
        assertEquals(55, ps.size(), "총 결정 지점 수가 바뀌었다 — 트리가 바뀌었으면 "
                + "유도본-v4-대조.md의 '묻지 않는 지점' 절도 함께 갱신해야 한다");
        assertEquals(43, ps.stream().filter(DecisionPoint.AttributeValue.class::isInstance).count(),
                "AttributeValue 개수");
        assertEquals(5, ps.stream().filter(DecisionPoint.MultiCount.class::isInstance).count(),
                "MultiCount 개수");
        assertEquals(5, ps.stream().filter(DecisionPoint.SpecChoice.class::isInstance).count(),
                "SpecChoice 개수");
        assertEquals(2, ps.stream().filter(DecisionPoint.CouplingActivation.class::isInstance).count(),
                "CouplingActivation 개수");
    }

    @Test
    void idsAreUnique() {
        List<DecisionPoint> ps = points();
        Set<String> ids = ps.stream().map(DecisionPoint::id).collect(Collectors.toSet());
        assertEquals(ps.size(), ids.size(), "지점 id가 겹치면 문항이 서로를 덮어쓴다");
    }
}
