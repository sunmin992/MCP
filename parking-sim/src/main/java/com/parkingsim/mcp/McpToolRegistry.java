package com.parkingsim.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 등록된 {@link McpToolProvider} 를 이름으로 찾는다. 스프링이 모든 구현체 빈을 주입한다. */
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
