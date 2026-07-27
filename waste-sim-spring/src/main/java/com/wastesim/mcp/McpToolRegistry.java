package com.wastesim.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 등록된 {@link McpToolProvider}(장량동 도메인과 무관한 독립 도구/모델) 전체를
 * toolName으로 조회할 수 있게 모아두는 레지스트리 — {@link SimulationModelRegistry}와
 * 같은 패턴이다. 스프링이 {@code List<McpToolProvider>}를 모든 구현체 빈으로
 * 자동 채워 주입하므로, 새 독립 도구를 추가해도 이 클래스는 손댈 필요가 없다.
 * 구현체가 하나도 없으면(현재 기본 상태) 빈 리스트로 주입되어 아무 영향이 없다.
 */
@Component
public class McpToolRegistry {

    private final List<McpToolProvider> providers;
    private final Map<String, McpToolProvider> byToolName = new LinkedHashMap<>();

    public McpToolRegistry(List<McpToolProvider> providers) {
        this.providers = List.copyOf(providers);
        for (McpToolProvider p : providers) {
            byToolName.put(p.toolName(), p);
        }
    }

    public List<McpToolProvider> all() {
        return providers;
    }

    public McpToolProvider byToolName(String toolName) {
        return byToolName.get(toolName);
    }
}
