package com.wastesim.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 등록된 {@link SimulationModelProvider} 전체를 modelId/toolName으로 조회할 수
 * 있게 모아두는 레지스트리. 스프링이 {@code List<SimulationModelProvider>}를
 * 자동으로 모든 구현체 빈으로 채워 주입하므로, 새 모델을 추가해도 이 클래스
 * 자체는 손댈 필요가 없다(MCP_모델_연결_방법.md §2·§4).
 */
@Component
public class SimulationModelRegistry {

    /** 기존 코드(모델 선택 없이 호출)와의 하위호환을 위한 기본 모델. */
    public static final String DEFAULT_MODEL_ID = "java-devs";

    private final List<SimulationModelProvider> providers;
    private final Map<String, SimulationModelProvider> byId = new LinkedHashMap<>();
    private final Map<String, SimulationModelProvider> byToolName = new LinkedHashMap<>();

    public SimulationModelRegistry(List<SimulationModelProvider> providers) {
        this.providers = List.copyOf(providers);
        for (SimulationModelProvider p : providers) {
            byId.put(p.modelId(), p);
            byToolName.put(p.toolName(), p);
        }
    }

    public List<SimulationModelProvider> all() {
        return providers;
    }

    public SimulationModelProvider byId(String modelId) {
        return byId.get(modelId == null ? DEFAULT_MODEL_ID : modelId);
    }

    public SimulationModelProvider byToolName(String toolName) {
        return byToolName.get(toolName);
    }
}
