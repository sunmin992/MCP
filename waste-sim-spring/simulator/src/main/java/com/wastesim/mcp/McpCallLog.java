package com.wastesim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP 도구 호출마다 한 줄 — 명세 그림의 몇 단계인지와 결과 요약.
 *
 * <p>LLM 쪽 화면에서는 모델이 보낸 것과 받은 것만 보인다. 서버가 무엇을 받아 어떻게 판정했는지는
 * 여기서만 볼 수 있다. 원문을 통째로 찍지 않는다 — build_scenario 한 번이 수십 줄이라 흐름이
 * 묻힌다. 원문이 필요하면 LLM 쪽 도구 호출 블록에 있다.
 *
 * <p>줄 앞의 {@code [요청 id]} 는 {@code CorrelationIdFilter} 가 HTTP 요청마다 붙인다.
 */
@Component
public class McpCallLog {

    private static final Logger log = LoggerFactory.getLogger("mcp.calls");

    /** 도구 → 명세 그림의 단계. 없는 도구는 확인 흐름 밖의 기존 도구다. */
    private static final Map<String, String> STEP = Map.of(
            "get_capability", "카드",
            "get_templates", "5 템플릿",
            "plan_subtasks", "6 서브태스크",
            "validate_answers", "8 값 검증",
            "build_scenario", "9·10 시나리오",
            "get_scenario_status", "12 상태",
            "run_scenario_by_token", "13·14 실행");

    private final ObjectMapper mapper;

    public McpCallLog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void connected(JsonNode params) {
        log.info("[연결] MCP 클라이언트 {} {}",
                params.path("clientInfo").path("name").asText("(이름 없음)"),
                params.path("clientInfo").path("version").asText(""));
    }

    /** @param callResult MCP CallToolResult — {content:[{text}], isError} */
    public void called(String tool, JsonNode callResult) {
        String step = STEP.getOrDefault(tool, "기존 도구");
        String text = callResult.path("content").path(0).path("text").asText("");
        if (callResult.path("isError").asBoolean(false)) {
            log.info("[{}] {} → 거절: {}", step, tool, shorten(text));
            return;
        }
        log.info("[{}] {} → {}", step, tool, summarize(tool, parse(text)));
    }

    private String summarize(String tool, JsonNode v) {
        if (v == null) return "(요약할 수 없는 응답)";
        return switch (tool) {
            case "get_capability" -> v.path("serverId").asText() + " · " + v.path("analysisUnit").path("label").asText();
            case "get_templates" -> v.path("templates").size() + "개";
            case "plan_subtasks" -> v.path("counts").toString() + " complete=" + v.path("complete").asBoolean();
            case "validate_answers" -> "valid=" + v.path("valid").asBoolean()
                    + " 통과 " + v.path("normalized").size() + " · 거절 " + v.path("rejected").size()
                    + (v.path("rejected").isEmpty() ? "" : " " + shorten(v.path("rejected").toString()));
            case "build_scenario" -> v.path("scenarioId").asText() + " · " + v.path("runCount").asInt() + "벌 · "
                    + v.path("state").asText() + " · 미승인 " + v.path("unapprovedDefaults").size()
                    + (v.path("blocks").isEmpty() ? "" : " · 막힘 " + v.path("blocks").size());
            case "get_scenario_status" -> v.path("scenarioId").asText() + " · " + v.path("state").asText();
            case "run_scenario_by_token" -> v.path("scenarioId").asText() + " · " + v.path("state").asText()
                    + " · 조건 " + v.path("runs").size() + "개";
            default -> "confirmed=" + v.path("confirmed").asText("?");
        };
    }

    /** 도구 결과는 JSON 문자열이 한 번 더 문자열로 싸여 오기도 한다. */
    private JsonNode parse(String text) {
        try {
            JsonNode v = mapper.readTree(text);
            return v.isTextual() ? mapper.readTree(v.asText()) : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String shorten(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
