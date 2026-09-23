package com.wastesim.mcp.ses;

import com.wastesim.pes.Scenario;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 검증을 마친 시나리오를 실행 요청 때까지 보관한다.
 *
 * <p>메모리에만 둔다 — 서버가 다시 뜨면 토큰도 함께 사라지는 것이 맞다. 재기동 뒤에도
 * 살아 있는 토큰은 그 사이 바뀐 리소스로 실행될 수 있다.
 */
@Component
public class ScenarioStore {

    private final Map<String, Scenario> byId = new ConcurrentHashMap<>();

    public String put(Scenario scenario) {
        byId.put(scenario.scenarioId(), scenario);
        return scenario.scenarioId();
    }

    public Optional<Scenario> get(String scenarioId) {
        return Optional.ofNullable(byId.get(scenarioId));
    }
}
