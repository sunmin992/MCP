package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 요청 프로필로 시뮬레이터 후보를 찾는다 — 명세 3·4단계.
 *
 * <p><b>못 고른 것은 오류가 아니다.</b> 맞는 서버가 없다는 것은 답이고, 오류로 내면 LLM 이
 * 다시 시도하거나 사용자에게 장애라고 말한다. 빈 목록과 사유를 낸다.
 */
@Component
public class FindSimulatorsTool implements McpToolProvider {

    private final CandidateMatcher matcher;
    private final ObjectMapper mapper;

    public FindSimulatorsTool(CandidateMatcher matcher, ObjectMapper mapper) {
        this.matcher = matcher;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "find_simulators"; }

    @Override
    public String description() {
        return "요청에서 뽑은 조건으로 시뮬레이터 후보를 찾는다. 후보마다 왜 맞는지(reasons)와 "
                + "요청한 것 중 무엇을 못 하는지(mismatches)를 함께 낸다. 모르는 항목은 비워 두고 "
                + "추정해 채우지 않는다 — 채우면 그 추정으로 서버가 골라진다.";
    }

    @Override
    public String inputSchemaJson() {
        return """
            {"type":"object",
             "properties":{
               "domain":{"type":"string","description":"도메인. 예: \\"쓰레기수거\\""},
               "spatialScale":{"type":"string","description":"공간 규모. 예: \\"한 동네\\""},
               "environmentConditions":{"type":"array","items":{"type":"string"},
                 "description":"환경 조건. 예: [\\"평일 교통량\\"]"},
               "objective":{"type":"string","description":"목적. 예: \\"민원이 가장 적은 수거 시각\\""},
               "comparisonAxes":{"type":"array","items":{"type":"string"},
                 "description":"무엇을 바꿔 가며 비교할 것인가"}},
             "required":["domain"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        RequestProfile profile;
        try {
            profile = new RequestProfile(
                    args.path("domain").asText(null),
                    args.path("spatialScale").asText(null),
                    strings(args, "environmentConditions"),
                    args.path("objective").asText(null),
                    strings(args, "comparisonAxes"));
        } catch (IllegalArgumentException e) {
            return ToolFailure.of("domain", e.getMessage());
        }

        try {
            List<MatchResult> matches = matcher.match(profile);

            var root = mapper.createObjectNode();
            root.put("domain", profile.domain());
            root.put("matchCount", matches.size());
            root.set("matches", mapper.valueToTree(matches));
            if (matches.isEmpty()) {
                root.put("note", "도메인 '" + profile.domain() + "' 을 다루는 서버가 등록돼 있지 않습니다. "
                        + "list_candidates 로 등록된 서버를 확인하거나 도메인을 다시 보십시오.");
            }
            return ToolResult.ok(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return ToolFailure.of("profile", "후보를 찾지 못했습니다: " + e.getMessage());
        }
    }

    /** 없으면 {@code null} 로 남긴다 — 빈 목록("그런 조건 없음")과 구분해야 한다. */
    private List<String> strings(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (!node.isArray()) return null;
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }
}
