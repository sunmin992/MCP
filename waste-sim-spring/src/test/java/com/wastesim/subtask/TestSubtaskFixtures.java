package com.wastesim.subtask;

import com.wastesim.mcp.PythonWasteSimAdapter;
import com.wastesim.mcp.SimulationModelRegistry;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;

import java.util.List;

/**
 * 수집 계층 테스트가 공유하는 조립 — 실제 구현을 그대로 물린다.
 *
 * <p>검증기·조립기를 모킹하지 않는 것이 의도다. 이 계층의 테스트는 "서버가 판정을
 * 소유하는가"를 보는 것이라, 판정기를 가짜로 바꾸면 정확히 그 성질이 검증에서 빠진다.
 *
 * <p>{@code public}인 이유: 다른 패키지(예: {@code com.wastesim.llm})의 테스트도 같은
 * 실제 조립이 필요하다. 거기서 이 조립을 그대로 다시 베끼면 "검증기·조립기는 언제나
 * 진짜를 쓴다"는 의도가 두 곳으로 갈라져 한쪽만 낡은 채 남을 수 있다.
 */
public final class TestSubtaskFixtures {

    private TestSubtaskFixtures() {}

    public static SubtaskSessionService service(JangnyangSubtaskCatalog catalog) {
        return service(catalog, new InMemorySubtaskSessionStore());
    }

    public static SubtaskSessionService service(JangnyangSubtaskCatalog catalog, SubtaskSessionStore store) {
        JangnyangCompletenessChecker checker = new JangnyangCompletenessChecker();
        return new SubtaskSessionService(
                catalog,
                new JangnyangSubtaskValidator(),
                checker,
                builder(checker),
                store);
    }

    /**
     * 실제 어댑터가 들어 있는 모델 레지스트리를 물린다 — 엔진별 지원 판정을 어댑터에게
     * 묻는 경로이므로, 빈 레지스트리로 두면 "미지원 설정을 실행 전에 막는다"는 성질이
     * 검증에서 그대로 빠진다. Java 엔진은 등록하지 않는다: 그 어댑터를 만들려면 시뮬레이션
     * 도구 전체가 필요한데, 지원 범위가 "전부"라 없을 때와 판정이 같다.
     */
    public static JangnyangScenarioBuilder builder(JangnyangCompletenessChecker checker) {
        TrafficDataService traffic = new TrafficDataService();
        return new JangnyangScenarioBuilder(checker, new SimulationConfigValidator(traffic), traffic,
                new SimulationModelRegistry(List.of(new PythonWasteSimAdapter())));
    }
}
