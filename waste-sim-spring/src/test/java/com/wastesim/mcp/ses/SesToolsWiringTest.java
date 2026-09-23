package com.wastesim.mcp.ses;

import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스프링 컨텍스트가 실제로 뜨고, 다섯 도구가 레지스트리에 모이는가.
 *
 * <p>이 저장소에는 컨텍스트를 세우는 테스트가 없었다. 도구는 전부 단위 테스트에서
 * {@code new} 로 조립해 시험하므로, 빈 배선이 어긋나도 테스트는 전부 초록이고
 * <b>서버를 띄울 때만</b> 터진다. 그 구멍을 여기서 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SesToolsWiringTest {

    @Autowired
    private McpToolRegistry registry;

    @Test
    void 다섯_도구가_레지스트리에_모인다() {
        List<String> names = registry.all().stream().map(McpToolProvider::toolName).sorted().toList();
        assertTrue(names.containsAll(List.of(
                        "build_scenario", "get_capability", "get_templates",
                        "plan_subtasks", "validate_answers")),
                "tools/list 에 실리지 않은 도구가 있다: " + names);
    }

    @Test
    void 도구_이름이_겹치지_않는다() {
        List<String> names = registry.all().stream().map(McpToolProvider::toolName).toList();
        assertEquals(names.size(), names.stream().distinct().count(),
                "같은 이름의 도구가 둘이면 라우팅이 하나를 조용히 덮는다: " + names);
    }

    @Test
    void 이름으로_찾을_수_있다() {
        assertNotNull(registry.byToolName("build_scenario"));
        assertNull(registry.byToolName("없는도구"));
    }
}
