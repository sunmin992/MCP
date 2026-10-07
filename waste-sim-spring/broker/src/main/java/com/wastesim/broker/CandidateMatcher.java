package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 요청 프로필과 능력 카드를 대조해 후보를 고른다 — 명세 4단계.
 *
 * <p><b>점수를 세지 않는다.</b> 도메인이 다른 서버는 뺀다. 같은 도메인 안에서는 요청 조건마다
 * 맞는지만 보고, 하나라도 어긋나면 그 자리와 고칠 방법을 남긴다. 그대로 맞는 서버가 여럿이면
 * 브로커가 고르지 않는다 — 서로 다른 점을 내고 사용자가 고른다.
 *
 * <p><b>어휘는 카드가 선언한다.</b> 요청은 사람 말("쓰레기수거")로 오고 카드는 슬러그
 * ("waste-collection")를 쓴다. 그 사이를 잇는 표를 매칭기가 들면 서버가 늘 때마다
 * 매칭기를 고쳐야 하고, 외부 서버가 등록해 오면 그 말을 아예 모른다. 카드가 자기를
 * 부르는 말을 함께 적는다 — {@code unsupported[].matchKeys} 가 이미 쓰던 방식이다.
 *
 * <p><b>어긋난 자리마다 고칠 방법을 함께 낸다.</b> "못 합니다" 로 끝나면 LLM 은 사용자에게
 * 돌려줄 말이 없다. 고칠 방법은 카드에 적힌 것 — 분석 단위, 미지원 사유와 대안, 답할 수
 * 있는 질문 — 에서만 만든다.
 */
@Component
public class CandidateMatcher {

    private final CandidateRegistry registry;

    public CandidateMatcher(CandidateRegistry registry) {
        this.registry = registry;
    }

    /** 도메인이 맞는 후보. @return 점수 내림차순. 동점이면 서버 id 오름차순. 없으면 빈 목록 */
    public List<MatchResult> match(RequestProfile profile) {
        return evaluateAll(profile, true);
    }

    /**
     * 도메인이 다른 후보. 그대로는 답할 수 없지만, 사용자가 질문을 바꿀 의향이 있으면 무엇을
     * 물을 수 있는지 보여 줄 자리다. 조정 제안의 첫 줄이 늘 도메인이고 {@code changesPurpose} 가 참이다.
     */
    public List<MatchResult> outsideDomain(RequestProfile profile) {
        return evaluateAll(profile, false);
    }

    private List<MatchResult> evaluateAll(RequestProfile profile, boolean domainMatched) {
        List<MatchResult> out = new ArrayList<>();
        for (JsonNode card : registry.cards()) {
            if (domainMatches(card, profile.domain()) != domainMatched) continue;
            out.add(evaluate(card, profile, domainMatched));
        }
        out.sort(Comparator.comparing(MatchResult::serverId));
        return List.copyOf(out);
    }

    private MatchResult evaluate(JsonNode card, RequestProfile profile, boolean domainMatched) {
        List<String> reasons = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        List<RequestAdjustment> adjustments = new ArrayList<>();

        if (domainMatched) {
            reasons.add("도메인: " + profile.domain() + " → " + text(card.path("domain")));
        } else {
            mismatches.add("도메인: " + profile.domain() + " 는 이 서버의 도메인("
                    + text(card.path("domain")) + ")이 아닙니다");
            adjustments.add(new RequestAdjustment("domain", profile.domain(),
                    "이 서버는 " + text(card.path("domainAliases")) + " 를 다룹니다. "
                            + "답할 수 있는 질문: " + answerableLabels(card),
                    "요청한 도메인을 다루지 않습니다 — 요청을 고치는 게 아니라 다른 질문을 하게 됩니다",
                    true));
        }

        // 공간 규모 — 미제시면 세지 않는다. 묻지 않아 모르는 것을 못한다고 하면 안 된다.
        String scale = profile.spatialScale();
        if (scale != null) {
            JsonNode unit = card.path("analysisUnit");
            String unitLabel = unit.path("label").asText();
            // 못 한다는 선언을 먼저 본다 — 환경 조건과 같은 이유다. "장량동 전체" 가 느슨한
            // 별칭에 걸려 규모 점수를 받으면 안 된다.
            JsonNode blocked = findUnsupported(card, scale);
            if (blocked == null && anyKeyMatches(unit.path("matchKeys"), scale)) {
                reasons.add("공간 규모: " + scale + " → " + unitLabel
                        + "(" + unit.path("scope").asText() + ")");
            } else {
                mismatches.add("공간 규모: " + scale + " 는 이 서버의 분석 단위("
                        + unitLabel + ")와 맞지 않습니다");
                adjustments.add(new RequestAdjustment("spatialScale", scale,
                        "공간 규모를 '" + unitLabel + "' 로 바꾸면 됩니다"
                                + (blocked == null ? "" : alternativesSuffix(blocked)),
                        blocked != null ? blocked.path("reason").asText()
                                : unit.path("rationale").asText("이 서버의 분석 단위는 " + unitLabel + " 입니다"),
                        false));
            }
        }

        // 거주민 — 사용자가 말한 구절 하나하나가 카드의 거주민 유형에 닿아야 한다. 카드가 거주민을
        // 적지 않았으면 된다고 하지 않는다 — 적혀 있지 않은 능력을 약속하면 엉뚱한 서버로 간다.
        if (profile.population() != null) {
            JsonNode types = card.path("populationTypes");
            for (String phrase : profile.population()) {
                JsonNode type = findPopulation(card, phrase);
                if (type != null) {
                    reasons.add("거주민: " + phrase + " → " + type.path("key").asText());
                } else if (!types.isArray() || types.isEmpty()) {
                    mismatches.add("거주민: " + phrase + " — 이 카드는 다루는 거주민을 적지 않았습니다");
                    adjustments.add(new RequestAdjustment("population", phrase,
                            "'" + phrase + "' 조건을 빼면 됩니다",
                            "이 카드는 다루는 거주민을 적지 않았습니다", false));
                } else {
                    mismatches.add("거주민: " + phrase + " — 이 서버가 모델링하는 거주민에 없습니다");
                    adjustments.add(new RequestAdjustment("population", phrase,
                            "이 서버가 다루는 거주민으로 바꾸면 됩니다: " + populationLabels(card),
                            "이 서버가 모델링하는 거주민에 없습니다", false));
                }
            }
        }

        // 환경 조건 — 지원하면 근거, 못 하면 카드가 적은 사유를 그대로 옮긴다.
        if (profile.environmentConditions() != null) {
            for (String cond : profile.environmentConditions()) {
                // 못 한다는 선언을 먼저 본다. 지원 목록을 먼저 보면 느슨한 별칭에 걸려
                // 못 하는 일로 점수를 받는다 — "평일 교통량" 이 "평일"(수거 요일)에 걸려
                // 교통을 못 하는 서버가 교통 점수를 챙기는 일이 실제로 있었다.
                JsonNode blocked = findUnsupported(card, cond);
                if (blocked != null) {
                    mismatches.add("환경 조건: " + cond + " — "
                            + blocked.path("capability").asText() + " 미지원. "
                            + blocked.path("reason").asText());
                    adjustments.add(new RequestAdjustment("environmentConditions", cond,
                            "'" + cond + "' 조건을 빼면 됩니다" + alternativesSuffix(blocked),
                            blocked.path("reason").asText(), false));
                    continue;
                }
                JsonNode supported = findEnvironment(card, cond);
                if (supported != null) {
                    reasons.add("환경 조건: " + cond + " → " + supported.path("key").asText());
                } else {
                    mismatches.add("환경 조건: " + cond + " — 이 카드가 다루는지 적혀 있지 않습니다");
                    adjustments.add(new RequestAdjustment("environmentConditions", cond,
                            "'" + cond + "' 조건을 빼면 됩니다. 이 서버가 다루는 환경 조건: "
                                    + environmentKeys(card),
                            "이 카드가 다루는지 적혀 있지 않습니다", false));
                }
            }
        }

        // 목적 — 카드가 답할 수 있는 질문(objectiveMatchKeys)에 닿는가. 그 표가 없는 카드는
        // 판단하지 않는다 — 적혀 있지 않은 것을 못 한다고 하면 안 된다.
        String objective = profile.objective();
        if (objective != null) {
            JsonNode blocked = findUnsupported(card, objective);
            if (blocked != null) {
                mismatches.add("목적: " + objective + " — " + blocked.path("capability").asText()
                        + " 미지원. " + blocked.path("reason").asText());
                adjustments.add(new RequestAdjustment("objective", objective,
                        "목적을 바꾸면 됩니다" + alternativesSuffix(blocked),
                        blocked.path("reason").asText(), false));
            } else {
                JsonNode keys = card.path("applicability").path("objectiveMatchKeys");
                List<String> hits = new ArrayList<>();
                for (JsonNode q : keys) {
                    if (anyKeyMatches(q.path("matchKeys"), objective)) hits.add(q.path("label").asText());
                }
                if (!hits.isEmpty()) {
                    reasons.add("목적: " + objective + " → " + String.join(", ", hits));
                } else if (keys.isArray() && !keys.isEmpty()) {
                    mismatches.add("목적: " + objective + " — 이 서버가 답할 수 있는 질문에 없습니다");
                    // 도메인이 다르면 도메인 조정이 이미 답할 수 있는 질문을 보여 준다 — 같은 목록을
                    // 두 번 싣지 않는다.
                    if (domainMatched) {
                        adjustments.add(new RequestAdjustment("objective", objective,
                                "이 서버가 답할 수 있는 질문 가운데 하나로 바꾸면 됩니다: " + answerableLabels(card),
                                "목적이 카드의 답할 수 있는 질문과 닿지 않습니다", false));
                    }
                }
            }
        }

        return new MatchResult(card.path("serverId").asText(), card.path("name").asText(),
                card.path("endpoint").asText(), card.path("fictional").asBoolean(false),
                reasons, mismatches, adjustments);
    }

    /** 슬러그로도, 카드가 선언한 말로도 맞는다. */
    private boolean domainMatches(JsonNode card, String domain) {
        return anyKeyMatches(card.path("domain"), domain)
                || anyKeyMatches(card.path("domainAliases"), domain);
    }

    private JsonNode findEnvironment(JsonNode card, String phrase) {
        for (JsonNode e : card.path("applicability").path("environmentMatchKeys")) {
            if (anyKeyMatches(e.path("matchKeys"), phrase)) return e;
        }
        return null;
    }

    private JsonNode findUnsupported(JsonNode card, String phrase) {
        for (JsonNode u : card.path("unsupported")) {
            if (anyKeyMatches(u.path("matchKeys"), phrase)) return u;
        }
        return null;
    }

    /** 요청 구절에 맞는 거주민 유형. 없으면 {@code null}. 차이 찾기도 같은 대조를 쓴다. */
    static JsonNode findPopulation(JsonNode card, String phrase) {
        for (JsonNode t : card.path("populationTypes")) {
            if (anyKeyMatches(t.path("matchKeys"), phrase)) return t;
        }
        return null;
    }

    /** 조정 제안에 보일 거주민 목록. 사람 말(첫 matchKey)과 서버 값(key)을 함께 적는다. */
    private String populationLabels(JsonNode card) {
        List<String> out = new ArrayList<>();
        card.path("populationTypes").forEach(t ->
                out.add(t.path("matchKeys").path(0).asText() + "(" + t.path("key").asText() + ")"));
        return String.join(", ", out);
    }

    /** 미지원 항목이 적은 대안. 없으면 빈 문자열 — 대안이 없는데 있는 척하지 않는다. */
    private String alternativesSuffix(JsonNode unsupported) {
        String alts = text(unsupported.path("alternatives"));
        return alts.isBlank() ? "" : ". 카드가 적은 대안: " + alts;
    }

    private String answerableLabels(JsonNode card) {
        List<String> out = new ArrayList<>();
        card.path("applicability").path("objectiveMatchKeys").forEach(q -> out.add(q.path("label").asText()));
        return out.isEmpty() ? text(card.path("applicability").path("answerableQuestions")) : String.join(", ", out);
    }

    private String environmentKeys(JsonNode card) {
        List<String> out = new ArrayList<>();
        card.path("applicability").path("environmentMatchKeys").forEach(e -> out.add(e.path("key").asText()));
        return String.join(", ", out);
    }

    /**
     * 카드가 선언한 말 중 하나가 요청 구절에 들어 있는가.
     *
     * <p>{@code CapabilityCardLoader.matchOne} 과 같은 방향이다 — 카드의 말을 요청 안에서
     * 찾는다. 반대로 하면 "교통" 이라는 말 하나가 "교통량 반영 안 함" 같은 문장에도 걸린다.
     */
    private static boolean anyKeyMatches(JsonNode keys, String phrase) {
        if (phrase == null || phrase.isBlank() || !keys.isArray()) return false;
        String haystack = phrase.toLowerCase();
        for (JsonNode k : keys) {
            String key = k.asText().toLowerCase();
            if (!key.isBlank() && haystack.contains(key)) return true;
        }
        return false;
    }

    private String text(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        arrayNode.forEach(n -> out.add(n.asText()));
        return String.join(", ", out);
    }
}
