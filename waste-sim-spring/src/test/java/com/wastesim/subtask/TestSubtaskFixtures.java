package com.wastesim.subtask;

import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;

/**
 * 수집 계층 테스트가 공유하는 조립 — 실제 구현을 그대로 물린다.
 *
 * <p>검증기·조립기를 모킹하지 않는 것이 의도다. 이 계층의 테스트는 "서버가 판정을
 * 소유하는가"를 보는 것이라, 판정기를 가짜로 바꾸면 정확히 그 성질이 검증에서 빠진다.
 */
final class TestSubtaskFixtures {

    private TestSubtaskFixtures() {}

    static SubtaskSessionService service(JangnyangSubtaskCatalog catalog) {
        return service(catalog, new InMemorySubtaskSessionStore());
    }

    static SubtaskSessionService service(JangnyangSubtaskCatalog catalog, SubtaskSessionStore store) {
        TrafficDataService traffic = new TrafficDataService();
        JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
        return new SubtaskSessionService(
                catalog,
                new JangnyangSubtaskValidator(),
                checker,
                new JangnyangScenarioBuilder(checker, new SimulationConfigValidator(traffic), traffic),
                store);
    }

    static JangnyangScenarioBuilder builder(JangnyangCompletenessChecker checker) {
        TrafficDataService traffic = new TrafficDataService();
        return new JangnyangScenarioBuilder(checker, new SimulationConfigValidator(traffic), traffic);
    }
}
