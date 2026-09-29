package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private static final Logger callLog = LoggerFactory.getLogger("mcp.calls");

    /** 제안은 몇 개까지. 많이 내면 LLM 이 사용자에게 고를 수 없는 목록을 넘긴다. */
    private static final int MAX_SUGGESTIONS = 3;

    public FindSimulatorsTool(CandidateMatcher matcher, ObjectMapper mapper) {
        this.matcher = matcher;
        this.mapper = mapper;
    }

    @Override public String toolName() { return "find_simulators"; }

    @Override
    public String description() {
        return "요청에서 뽑은 조건으로 시뮬레이터 MCP 서버를 찾는다. verdict 가 MATCH 면 recommended 의 "
                + "서버(endpoint)로 가면 된다. ADJUST_REQUEST 면 그대로 받을 서버가 없다 — suggestions 의 "
                + "requestAdjustments 가 요청을 어떻게 고치면 그 서버를 쓸 수 있는지 말하므로 사용자에게 "
                + "그대로 전하고 고를지 묻는다(changesPurpose 가 참이면 다른 질문이 된다는 뜻이다). "
                + "NONE 이면 부를 수 있는 서버가 없다. 모르는 항목은 비워 두고 추정해 채우지 않는다 — "
                + "채우면 그 추정으로 서버가 골라진다.";
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

        // 명세 3단계 — LLM 이 요청에서 뽑은 것. 여기서만 서버가 실제로 받은 값을 볼 수 있다.
        callLog.info("[3 조회] domain={} · scale={} · env={} · objective={}",
                profile.domain(), profile.spatialScale(), profile.environmentConditions(), profile.objective());
        try {
            List<MatchResult> matches = matcher.match(profile);

            var root = mapper.createObjectNode();
            root.put("domain", profile.domain());
            root.put("matchCount", matches.size());

            // 추천은 부를 수 있는 서버 가운데 요청을 그대로 받는 것만. 지어낸 후보는 맞아도
            // 추천하지 않는다 — example.invalid 로 LLM 을 보내면 부를 수 없는 서버를 부른다.
            Optional<MatchResult> fit = matches.stream()
                    .filter(m -> !m.fictional() && m.fitsAsIs()).findFirst();
            if (fit.isPresent()) {
                root.put("verdict", "MATCH");
                root.set("recommended", mapper.valueToTree(fit.get()));
                callLog.info("[4 매칭] MATCH → {} ({}) · 근거 {}",
                        fit.get().serverId(), fit.get().endpoint(), fit.get().reasons().size());
            } else {
                // 그대로 받을 서버가 없다. 도메인이 맞는 쪽을 먼저, 다른 쪽을 뒤에 두고 각각
                // 요청을 어떻게 고치면 되는지 싣는다.
                List<MatchResult> suggestions = new ArrayList<>();
                matches.stream().filter(m -> !m.fictional()).forEach(suggestions::add);
                matcher.outsideDomain(profile).stream().filter(m -> !m.fictional()).forEach(suggestions::add);
                if (suggestions.isEmpty()) {
                    root.put("verdict", "NONE");
                    callLog.info("[4 매칭] NONE — 부를 수 있는 서버 없음");
                    root.put("note", "부를 수 있는 시뮬레이터 서버가 등록돼 있지 않습니다"
                            + (matches.isEmpty() ? "" : " — 도메인이 맞는 것은 지어낸 후보뿐입니다")
                            + ". list_candidates 로 등록된 서버를 확인하십시오.");
                } else {
                    root.put("verdict", "ADJUST_REQUEST");
                    for (MatchResult m : suggestions.subList(0, Math.min(MAX_SUGGESTIONS, suggestions.size()))) {
                        callLog.info("[4 매칭] ADJUST_REQUEST → {} 쓰려면 고칠 것: {}", m.serverId(),
                                m.requestAdjustments().stream()
                                        .map(a -> a.axis() + "='" + a.current() + "'" + (a.changesPurpose() ? "(목적 바뀜)" : ""))
                                        .toList());
                    }
                    root.set("suggestions", mapper.valueToTree(
                            suggestions.subList(0, Math.min(MAX_SUGGESTIONS, suggestions.size()))));
                    root.put("note", (matches.isEmpty()
                            ? "도메인 '" + profile.domain() + "' 을 다루는 서버가 등록돼 있지 않습니다. "
                            : "요청을 그대로 받을 수 있는 서버가 없습니다. ")
                            + "suggestions 의 requestAdjustments 대로 요청을 고치면 그 서버를 쓸 수 있습니다 — "
                            + "사용자에게 전하고 고칠지 물으십시오.");
                }
            }
            root.set("matches", mapper.valueToTree(matches));
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
