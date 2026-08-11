package com.wastesim.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioIntentDetectorTest {

    @Test
    void detectsEachButtonLabelVerbatim() {
        // 사이드바 버튼 문구 그대로 입력해도 매칭돼야 한다.
        assertEquals("occupation-mix", ScenarioIntentDetector.detect("거주민 구성별 최적 수거시각"));
        assertEquals("collection-sweep", ScenarioIntentDetector.detect("수거시각 sweep (06~18시)"));
        assertEquals("behavior-grid", ScenarioIntentDetector.detect("행동 변동 α×β 민감도"));
        assertEquals("infra-grid", ScenarioIntentDetector.detect("인프라 용량×임계 트레이드오프"));
        assertEquals("density", ScenarioIntentDetector.detect("밀도: 빌라촌 vs 원룸촌"));
        assertEquals("collection-schedule", ScenarioIntentDetector.detect("수거 스케줄(다회·격일·주말)"));
        assertEquals("multi-truck", ScenarioIntentDetector.detect("다중 트럭·구역 분할"));
        assertEquals("waste-separation", ScenarioIntentDetector.detect("분리배출(일반·음식물·재활용)"));
        assertEquals("new-occupations", ScenarioIntentDetector.detect("확장 거주민(야간·1인직장인)"));
        assertEquals("coupling-variants", ScenarioIntentDetector.detect("결합모델 변형(귀가·임대인)"));
        assertEquals("monthly-waste", ScenarioIntentDetector.detect("월별 배출량(1년·최다 달)"));
        assertEquals("truck-route", ScenarioIntentDetector.detect("차종 × 방문 순서 탐색"));
    }

    @Test
    void truckRouteSearchDoesNotStealMultiTruck() {
        // "트럭"이 두 규칙에 함께 걸린다. 대수를 묻는 기존 요청이 탐색으로 새면
        // 사용자가 물은 구역 분할 효과 대신 조합 순위표가 돌아온다.
        assertEquals("multi-truck", ScenarioIntentDetector.detect("다중 트럭·구역 분할"));
        assertEquals("multi-truck", ScenarioIntentDetector.detect("트럭을 여러 대로 나누면 어떻게 돼?"));

        // 차종·순서를 실제로 가리키는 요청만 탐색으로 간다.
        assertEquals("truck-route", ScenarioIntentDetector.detect("차종이랑 방문 순서 조합 중에 민원 가장 적은 거 찾아줘"));
        assertEquals("truck-route", ScenarioIntentDetector.detect("1톤이랑 5톤 중에 뭐가 최적인지 비교해줘"));
        assertEquals("truck-route", ScenarioIntentDetector.detect("수거 순서를 바꿔가며 최적 조합 탐색해줘"));
    }

    @Test
    void detectsNaturalPhrasingVariants() {
        assertEquals("occupation-mix", ScenarioIntentDetector.detect("거주민 구성별로 최적 수거시각 비교해줘"));
        assertEquals("density", ScenarioIntentDetector.detect("빌라촌이랑 원룸촌 밀도 차이 비교해줘"));
        assertEquals("waste-separation", ScenarioIntentDetector.detect("분리배출 효과 실험해줘"));
        assertEquals("new-occupations", ScenarioIntentDetector.detect("야간 근무자 많으면 어떻게 되는지 실험해줘"));
        assertEquals("multi-truck", ScenarioIntentDetector.detect("트럭 여러 대로 구역 분할하면 어때"));
    }

    @Test
    void returnsNullForUnrelatedOrSingleRunMessages() {
        assertNull(ScenarioIntentDetector.detect("12시에 수거해줘"));
        assertNull(ScenarioIntentDetector.detect("이 시뮬레이션은 뭘 하는 거야?"));
        assertNull(ScenarioIntentDetector.detect(null));
    }
}
