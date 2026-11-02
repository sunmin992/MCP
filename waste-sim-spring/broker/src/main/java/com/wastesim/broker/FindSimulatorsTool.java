package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.ToolFailure;
import com.wastesim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
    private final MatchRounds rounds;

    /** 고친 조회는 다섯 번까지. 첫 조회가 1바퀴이므로 7바퀴째부터 대조하지 않는다. */
    private static final int MAX_REVISIONS = 5;

    private static final Logger callLog = LoggerFactory.getLogger("mcp.calls");

    /** 서버 주소 → 지금 응답하는가. 추천 · 제안에는 응답하는 서버만 싣는다. */
    private final Predicate<String> reachable;

    @Autowired
    public FindSimulatorsTool(CandidateMatcher matcher, ObjectMapper mapper, Predicate<String> reachable,
                              MatchRounds rounds) {
        this.matcher = matcher;
        this.mapper = mapper;
        this.reachable = reachable;
        this.rounds = rounds;
    }

    /** 모든 서버가 응답한다고 보는 시험용 생성자. */
    FindSimulatorsTool(CandidateMatcher matcher, ObjectMapper mapper) {
        this(matcher, mapper, endpoint -> true, new MatchRounds());
    }

    /** 응답 여부를 정하는 시험용 생성자. */
    FindSimulatorsTool(CandidateMatcher matcher, ObjectMapper mapper, Predicate<String> reachable) {
        this(matcher, mapper, reachable, new MatchRounds());
    }

    @Override public String toolName() { return "find_simulators"; }

    @Override
    public String description() {
        return "요청에서 뽑은 조건으로 시뮬레이터 MCP 서버를 찾는다. verdict 가 MATCH 면 recommended 의 "
                + "서버(endpoint)로 가고, 그 대화에서는 브로커를 다시 부르지 않는다(nextStep 참고). ADJUST_REQUEST 면 그대로 받을 서버가 없다 — suggestions 의 "
                + "requestAdjustments 가 요청을 어떻게 고치면 그 서버를 쓸 수 있는지 말하므로 사용자에게 "
                + "그대로 전하고 고를지 묻는다(changesPurpose 가 참이면 다른 질문이 된다는 뜻이다). "
                + "CHOOSE 면 그대로 맞는 서버가 여럿이다 — differences 를 사용자에게 보여 주고 고르게 한 뒤 "
                + "고른 서버의 endpoint 로 간다. "
                + "NONE 이면 부를 수 있는 서버가 없다. 모르는 항목은 비워 두고 추정해 채우지 않는다 — "
                + "채우면 그 추정으로 서버가 골라진다. "
                + "응답의 round.matchId 는 이 매칭의 이름이다 — 요청을 고쳐 다시 조회할 때 matchId 로 넘긴다. "
                + "고친 요청으로 쓰려는 서버가 정해졌으면 targetServerId 로 함께 넘겨 그 서버하고만 대조한다. "
                + "고친 조회는 5번까지 센다 — 넘기면 대조 없이 NONE 이다.";
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
                 "description":"무엇을 바꿔 가며 비교할 것인가 — 비교할 값이 아니라 바꾸는 항목의 이름을 적는다. 예: [\\"수거차 대수\\"] (\\"수거차 3대\\", \\"수거차 2대\\" 가 아니다), [\\"수거 시각\\"] (\\"밤 10시 수거\\" 가 아니다)"},
               "population":{"type":"array","items":{"type":"string"},
                 "description":"거주민에 관한 구절. 구절 하나에 거주민 하나로 나눠 사용자 말 그대로 적는다. 예: [\\"학생이 많음\\",\\"주부가 많음\\"]"},
               "matchId":{"type":"string","description":"첫 조회 응답의 round.matchId. 고친 요청으로 다시 조회할 때만 보낸다"},
               "targetServerId":{"type":"string","description":"고친 요청으로 쓰려는 서버의 id. matchId 와 함께만 보낸다"}},
             "required":["domain"]}
            """;
    }

    @Override
    public ToolResult call(JsonNode args) {
        return call(args, null);
    }

    public ToolResult call(JsonNode args, String session) {
        String matchId = args.path("matchId").asText(null);
        String targetServerId = args.path("targetServerId").asText(null);
        if (matchId != null && matchId.isBlank()) matchId = null;
        if (targetServerId != null && targetServerId.isBlank()) targetServerId = null;
        if (matchId == null && targetServerId != null) {
            return ToolFailure.of("matchId", "targetServerId 는 matchId 와 함께 보내십시오 — 첫 조회에서 받은 matchId 입니다");
        }
        RequestProfile profile;
        try {
            profile = new RequestProfile(
                    args.path("domain").asText(null),
                    args.path("spatialScale").asText(null),
                    strings(args, "environmentConditions"),
                    args.path("objective").asText(null),
                    strings(args, "comparisonAxes"),
                    strings(args, "population"));
        } catch (IllegalArgumentException e) {
            return ToolFailure.of("domain", e.getMessage());
        }

        int round;
        List<String> removed = List.of();
        boolean purposeChanged = false;
        if (matchId == null) {
            matchId = rounds.open(session, profile);
            round = 1;
        } else {
            var thread = rounds.next(matchId, session, profile, targetServerId);
            if (thread.isEmpty()) {
                return ToolFailure.of("matchId", "모르는 매칭 id 입니다 — 브로커가 다시 시작됐을 수 있습니다. "
                        + "matchId 없이 새 요청으로 조회하십시오");
            }
            round = thread.get().round();
            removed = thread.get().removedConditions();
            purposeChanged = thread.get().purposeChanged();
        }

        // 명세 3단계 — LLM 이 요청에서 뽑은 것. 여기서만 서버가 실제로 받은 값을 볼 수 있다.
        callLog.info("[3 조회] domain={} · scale={} · population={} · env={} · objective={}",
                profile.domain(), profile.spatialScale(), profile.population(),
                profile.environmentConditions(), profile.objective());
        try {
            var root = mapper.createObjectNode();
            root.put("domain", profile.domain());
            var roundNode = root.putObject("round");
            roundNode.put("matchId", matchId);
            roundNode.put("round", round);
            roundNode.put("revisions", round - 1);
            roundNode.put("remainingRevisions", Math.max(0, MAX_REVISIONS + 1 - round));
            var removedNode = roundNode.putArray("removedConditions");
            removed.forEach(removedNode::add);
            roundNode.put("purposeChanged", purposeChanged);
            // 바퀴마다 한 줄 — 고친 조회가 어디서 조건을 덜어 냈는지 로그로 본다.
            final String roundLog = "바퀴 " + round + " · 고친 " + (round - 1) + " · 제거 " + removed
                    + " · 목적변경 " + purposeChanged;
            if (round > MAX_REVISIONS + 1) {
                // 고친 조회를 다 썼다. 맞는 요청이어도 대조하지 않는다.
                root.put("verdict", "NONE");
                root.put("noneReason", "REVISION_LIMIT");
                root.put("note", "고친 조회 " + MAX_REVISIONS + "번 안에 맞는 시뮬레이터에 닿지 못했습니다");
                root.put("nextStep", "사용자에게 고친 조회 " + MAX_REVISIONS
                        + "번 안에 맞는 시뮬레이터에 닿지 못했다고 전하십시오.");
                callLog.info("[4 매칭] {} — NONE 고친 조회 한도", roundLog);
                return ToolResult.ok(mapper.writeValueAsString(root));
            }

            // 목표 서버가 등록돼 있고 부를 수 있으면 그 카드 하나와만 대조한다. 아니면 전체 대조.
            JsonNode target = targetServerId == null ? null : matcher.card(targetServerId)
                    .filter(c -> !c.path("fictional").asBoolean(false) && reachable.test(c.path("endpoint").asText()))
                    .orElse(null);
            boolean targetUnavailable = targetServerId != null && target == null;
            List<MatchResult> matches = target != null
                    ? List.of(matcher.evaluateOne(target, profile)) : matcher.match(profile);
            root.put("matchCount", matches.size());

            // 추천은 부를 수 있는 서버 가운데 요청을 그대로 받는 것만. 지어낸 후보는 맞아도
            // 추천하지 않는다 — example.invalid 로 LLM 을 보내면 부를 수 없는 서버를 부른다.
            // 응답하지 않는 서버도 뺀다. 강제 종료된 서버는 등록이 남아 있어, 보지 않으면 부를 수
            // 없는 주소로 MATCH 가 나간다. 주소마다 한 번만 본다.
            Map<String, Boolean> alive = new HashMap<>();
            Map<String, String> down = new LinkedHashMap<>();
            Predicate<MatchResult> callable = m -> {
                if (m.fictional()) return false;
                boolean up = alive.computeIfAbsent(m.endpoint(), reachable::test);
                if (!up) down.put(m.serverId(), m.endpoint());
                return up;
            };
            List<MatchResult> fits = matches.stream()
                    .filter(m -> m.fitsAsIs() && callable.test(m)).toList();
            if (fits.size() == 1) {
                MatchResult fit = fits.get(0);
                root.put("verdict", "MATCH");
                root.set("recommended", mapper.valueToTree(fit));
                // 넘겨준 뒤에는 브로커가 할 일이 없다. 안내하지 않으면 LLM 이 단계마다 브로커를 다시
                // 불러 같은 조회를 되풀이한다.
                root.put("nextStep", "이 대화에서는 브로커를 다시 부르지 마십시오. 이후 단계는 "
                        + fit.endpoint() + " 의 시뮬레이터 MCP 도구만 씁니다 — get_templates → "
                        + "plan_subtasks → validate_answers → build_scenario → get_scenario_status → "
                        + "run_scenario_by_token. 사용자가 전혀 다른 시뮬레이션을 새로 요청할 때만 다시 조회합니다.");
                callLog.info("[4 매칭] {} — MATCH → {} ({}) · 근거 {}",
                        roundLog, fit.serverId(), fit.endpoint(), fit.reasons().size());
            } else if (fits.size() > 1) {
                // 브로커가 고르지 않는다. 지역 · 모델 가정처럼 결과를 바꾸는 선택을 몰래 하면 사용자는
                // 그 가정으로 나온 답인 줄 모른다.
                List<Difference> diffs = matcher.differences(fits, profile);
                root.put("verdict", "CHOOSE");
                root.set("candidates", mapper.valueToTree(fits));
                root.set("differences", mapper.valueToTree(diffs));
                root.put("nextStep", diffs.isEmpty()
                        ? "후보들이 이 요청에 대해 같습니다. 첫 번째 서버(" + fits.get(0).serverId() + ")의 "
                                + fits.get(0).endpoint() + " 로 가십시오. 그 대화에서는 브로커를 다시 부르지 마십시오."
                        : "사용자에게 differences 를 보여 주고 어느 서버를 쓸지 물으십시오. 고른 서버의 endpoint 로 "
                                + "가고, 그 대화에서는 브로커를 다시 부르지 마십시오.");
                callLog.info("[4 매칭] {} — CHOOSE → {} · 차이 {}",
                        roundLog,
                        fits.stream().map(MatchResult::serverId).toList(),
                        diffs.isEmpty() ? "없음(동등한 후보)" : diffs.stream().map(Difference::item).toList());
            } else {
                // 그대로 받을 서버가 없다. 도메인이 맞는 쪽을 먼저, 다른 쪽을 뒤에 두고 각각
                // 요청을 어떻게 고치면 되는지 싣는다.
                // 같은 도메인에 부를 수 있는 후보가 하나라도 있으면 그것만 싣는다. 없을 때만 다른 도메인.
                List<MatchResult> suggestions = new ArrayList<>(matches.stream().filter(callable).toList());
                if (suggestions.isEmpty() && target == null) {
                    matcher.outsideDomain(profile).stream().filter(callable).forEach(suggestions::add);
                }
                // 보기 위한 순서다 — 브로커가 고르지 않는다. 목적이 바뀌는 후보는 뒤, 고칠 것이 적은 순, 서버 id.
                suggestions.sort(Comparator
                        .comparing((MatchResult m) -> m.requestAdjustments().stream()
                                .anyMatch(RequestAdjustment::changesPurpose))
                        .thenComparingInt(m -> m.requestAdjustments().size())
                        .thenComparing(MatchResult::serverId));
                if (suggestions.isEmpty()) {
                    root.put("verdict", "NONE");
                    root.put("noneReason", "NO_CALLABLE_SERVER");
                    callLog.info("[4 매칭] {} — NONE 부를 수 있는 서버 없음", roundLog);
                    if (!down.isEmpty()) {
                        // 서버는 있는데 꺼져 있다. "없다" 고 하면 사용자는 다시 켜면 될 일을 포기한다.
                        root.put("nextStep", "사용자에게 등록된 시뮬레이터 " + down.keySet()
                                + " 가 지금 응답하지 않아 쓸 수 없다고 전하십시오. 서버가 다시 켜지면 같은 요청으로 다시 조회합니다.");
                        root.put("note", "등록된 시뮬레이터 서버 " + down.keySet() + " 가 지금 응답하지 않습니다"
                                + " — 꺼져 있거나 연결할 수 없습니다. 이 밖에 부를 수 있는 서버는 없습니다.");
                    } else {
                        root.put("nextStep", "사용자에게 지금 쓸 수 있는 시뮬레이터가 없다고 전하십시오.");
                        root.put("note", "부를 수 있는 시뮬레이터 서버가 등록돼 있지 않습니다"
                                + (matches.isEmpty() ? "" : " — 도메인이 맞는 것은 지어낸 후보뿐입니다")
                                + ". list_candidates 로 등록된 서버를 확인하십시오.");
                    }
                } else {
                    root.put("verdict", "ADJUST_REQUEST");
                    root.put("nextStep", "사용자에게 후보별 requestAdjustments 를 모두 보이고, 어느 서버를 목표로 할지와 "
                            + "고친 요청을 함께 받으십시오. 고친 요청으로 find_simulators 를 다시 부를 때 matchId 와 "
                            + "targetServerId 를 넘깁니다. 남은 고친 조회: "
                            + roundNode.path("remainingRevisions").asInt() + "번.");
                    for (MatchResult m : suggestions) {
                        callLog.info("[4 매칭] ADJUST_REQUEST → {} 쓰려면 고칠 것: {}", m.serverId(),
                                m.requestAdjustments().stream()
                                        .map(a -> a.axis() + "='" + a.current() + "'" + (a.changesPurpose() ? "(목적 바뀜)" : ""))
                                        .toList());
                    }
                    root.set("suggestions", mapper.valueToTree(suggestions));
                    root.put("note", (matches.isEmpty()
                            ? "도메인 '" + profile.domain() + "' 을 다루는 서버가 등록돼 있지 않습니다. "
                            : "요청을 그대로 받을 수 있는 서버가 없습니다. ")
                            + "suggestions 의 requestAdjustments 대로 요청을 고치면 그 서버를 쓸 수 있습니다 — "
                            + "사용자에게 전하고 고칠지 물으십시오.");
                }
            }
            // 시뮬레이터의 build_scenario 는 실험 변수를 하나만 받는다. 비교 축이 여럿인데 그대로 넘기면
            // LLM 은 시뮬레이터 도구 스키마를 읽고서야 알게 되고, 그 사이 하나를 몰래 고르기 쉽다(2026-10-27 NT-D7).
            String verdict = root.path("verdict").asText();
            List<String> axes = profile.comparisonAxes();
            if (axes != null && axes.size() > 1 && (verdict.equals("MATCH") || verdict.equals("CHOOSE"))) {
                String warning = "비교 축이 " + axes.size() + "개입니다(" + String.join(", ", axes) + "). 시뮬레이터는 "
                        + "시나리오 하나에서 한 가지만 바꿔 비교합니다 — 사용자에게 무엇을 바꿔 볼지 하나를 고르게 하고, "
                        + "나머지는 어떤 값으로 고정할지 물으십시오. 둘 다 보려면 시나리오를 따로 만듭니다.";
                root.put("comparisonWarning", warning);
                root.put("nextStep", warning + " " + root.path("nextStep").asText());
                callLog.info("[4 매칭] 비교 축 {}개 → 하나만 고르라고 안내", axes.size());
            }
            if (targetUnavailable) {
                var tu = root.putObject("targetUnavailable");
                tu.put("serverId", targetServerId);
                tu.put("note", "고른 서버를 지금 쓸 수 없어 전체 후보와 다시 대조했습니다");
            }
            if (!down.isEmpty()) {
                // 사용자에게 "그 서버는 지금 꺼져 있다" 고 말할 수 있게 남긴다 — 조용히 빼면 왜 다른
                // 서버로 갔는지, 왜 맞는 서버가 없다는지 알 수 없다.
                root.set("unreachable", mapper.valueToTree(down));
                root.put("unreachableNote", "등록돼 있지만 지금 응답하지 않아 추천 · 제안에서 뺀 서버입니다. "
                        + "그 서버가 다시 켜지면 같은 요청으로 다시 조회할 수 있습니다.");
                callLog.info("[4 매칭] 응답 없음 → 추천에서 뺌: {}", down);
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
