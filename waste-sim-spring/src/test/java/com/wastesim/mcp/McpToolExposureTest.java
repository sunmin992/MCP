package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>실제로 등록된</b> MCP 도구의 노출 규모를 고정한다.
 *
 * <p><b>왜 스프링 컨텍스트를 띄우는가</b>: 이 검증을 손으로 만든 {@link McpToolRegistry}로
 * 하면 <b>테스트가 세는 것과 서버가 노출하는 것이 달라진다.</b> 실제로 그 일이 있었다 —
 * 필수 3종만 넣은 레지스트리로 개수를 통과시키는 동안, 선택 3종이 함께 등록돼 서버는 더
 * 많은 도구를 내보내고 있었다. 테스트가 자기가 만든 목록을 세고 있으면 무엇이 등록됐는지는
 * 영영 확인하지 못한다.
 *
 * <p>엔드포인트는 {@code POST /mcp} 하나뿐이다. 라즈베리파이 엣지 도메인을 분리하면서
 * {@code /mcp/{slug}}·{@code McpDomain}·도메인 경계 검사가 함께 사라졌고, 그래서 이
 * 클래스가 세는 목록도 하나가 됐다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpToolExposureTest {

    @Autowired McpToolCatalog catalog;
    @Autowired McpToolRegistry independentTools;

    private final ObjectMapper om = new ObjectMapper();

    private List<String> exposedNames() throws Exception {
        JsonNode tools = catalog.toolsList(om).path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    @Test
    @DisplayName("서버는 장량동 8종만 노출한다 — 모델 2 + 고정 3 + 서브태스크 3")
    void exposesEightWasteTools() throws Exception {
        List<String> names = exposedNames();
        assertEquals(8, names.size(), () -> "노출 도구 수가 다르다: " + names);
        assertEquals(names.size(), names.stream().distinct().count(), "도구 이름이 중복됐다");

        assertTrue(names.containsAll(List.of(
                "run_waste_simulation", "run_waste_simulation_devs",
                "run_scenario", "list_scenarios", "update_route_sequence",
                "get_jangnyang_fixed_subtasks", "validate_jangnyang_subtask_answers",
                "build_jangnyang_scenario")), () -> "빠진 도구가 있다: " + names);
    }

    @Test
    @DisplayName("엣지 도구는 하나도 남아 있지 않다 — 도메인이 분리됐다")
    void noEdgeToolsRemain() throws Exception {
        for (String n : exposedNames()) {
            assertFalse(n.contains("edge"), "엣지 도구가 남아 있다: " + n);
            assertFalse(n.contains("heatsink"), "엣지 도구가 남아 있다: " + n);
            assertFalse(n.contains("fan"), "엣지 도구가 남아 있다: " + n);
            assertFalse(n.contains("ptm"), "엣지 도구가 남아 있다: " + n);
        }
        for (McpToolProvider p : independentTools.all()) {
            assertTrue(p.toolName().contains("jangnyang"),
                    "장량동 외 도구가 등록돼 있다: " + p.toolName());
        }
    }

    @Test
    @DisplayName("FR-137 선택 3종은 구현돼 있지만 빈으로 등록되지 않아 노출되지 않는다")
    void optionalToolsAreImplementedButNotRegistered() throws Exception {
        List<String> optional = List.of("submit_jangnyang_subtask_answer",
                "get_jangnyang_subtask_progress", "reset_jangnyang_subtask_session");

        for (String n : optional) {
            assertNull(independentTools.byToolName(n), n + "이 레지스트리에 등록돼 있다");
            assertFalse(exposedNames().contains(n), n + "이 노출된다");
        }

        // 클래스는 남아 있다 — 지운 게 아니라 노출만 하지 않는 상태라는 확인이다.
        // 다시 열려면 애너테이션을 붙이고 위 기대 개수를 함께 올린다.
        assertDoesNotThrow(() ->
                Class.forName("com.wastesim.subtask.SubmitJangnyangSubtaskAnswerTool"));
        assertDoesNotThrow(() ->
                Class.forName("com.wastesim.subtask.GetJangnyangSubtaskProgressTool"));
        assertDoesNotThrow(() ->
                Class.forName("com.wastesim.subtask.ResetJangnyangSubtaskSessionTool"));
    }

    @Test
    @DisplayName("등록된 모든 도구가 유효한 JSON Schema를 갖는다")
    void everyRegisteredToolHasAValidSchema() throws Exception {
        for (McpToolProvider p : independentTools.all()) {
            assertFalse(p.description().isBlank(), p.toolName() + "에 설명이 없다");
            JsonNode schema = om.readTree(p.inputSchemaJson());
            assertEquals("object", schema.path("type").asText(),
                    p.toolName() + "의 스키마 최상위 type이 object가 아니다");
        }
    }
}
