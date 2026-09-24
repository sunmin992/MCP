package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 요청 프로필과 능력 카드를 대조해 후보를 고른다 — 명세 4단계.
 *
 * <p><b>거르고 나서 점수를 센다.</b> 도메인이 다른 서버는 다른 축이 아무리 맞아도 답을 낼
 * 수 없으므로 점수로 깎지 않고 뺀다. 점수로 깎으면 도메인이 다른 서버가 조건을 많이 맞춰
 * 1등이 되는 일이 생긴다.
 *
 * <p><b>어휘는 카드가 선언한다.</b> 요청은 사람 말("쓰레기수거")로 오고 카드는 슬러그
 * ("waste-collection")를 쓴다. 그 사이를 잇는 표를 매칭기가 들면 서버가 늘 때마다
 * 매칭기를 고쳐야 하고, 외부 서버가 등록해 오면 그 말을 아예 모른다. 카드가 자기를
 * 부르는 말을 함께 적는다 — {@code unsupported[].matchKeys} 가 이미 쓰던 방식이다.
 */
@Component
public class CandidateMatcher {

    /** 공간 규모가 맞았을 때. 도메인 다음으로 크게 가르는 축이다. */
    private static final int SCALE_POINT = 2;
    /** 환경 조건 하나가 지원될 때. */
    private static final int ENV_POINT = 2;

    private final CandidateRegistry registry;

    public CandidateMatcher(CandidateRegistry registry) {
        this.registry = registry;
    }

    /** @return 점수 내림차순. 동점이면 서버 id 오름차순. 맞는 후보가 없으면 빈 목록 */
    public List<MatchResult> match(RequestProfile profile) {
        List<MatchResult> out = new ArrayList<>();

        for (JsonNode card : registry.cards()) {
            if (!domainMatches(card, profile.domain())) continue;

            int score = 0;
            List<String> reasons = new ArrayList<>();
            List<String> mismatches = new ArrayList<>();

            String serverId = card.path("serverId").asText();
            reasons.add("도메인: " + profile.domain() + " → " + text(card.path("domain")));

            // 공간 규모 — 미제시면 세지 않는다. 묻지 않아 모르는 것을 못한다고 하면 안 된다.
            String scale = profile.spatialScale();
            if (scale != null) {
                JsonNode unit = card.path("analysisUnit");
                if (anyKeyMatches(unit.path("matchKeys"), scale)) {
                    score += SCALE_POINT;
                    reasons.add("공간 규모: " + scale + " → " + unit.path("label").asText()
                            + "(" + unit.path("scope").asText() + ")");
                } else {
                    mismatches.add("공간 규모: " + scale + " 는 이 서버의 분석 단위("
                            + unit.path("label").asText() + ")와 맞지 않습니다");
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
                        continue;
                    }
                    JsonNode supported = findEnvironment(card, cond);
                    if (supported != null) {
                        score += ENV_POINT;
                        reasons.add("환경 조건: " + cond + " → " + supported.path("key").asText());
                    } else {
                        mismatches.add("환경 조건: " + cond + " — 이 카드가 다루는지 적혀 있지 않습니다");
                    }
                }
            }

            out.add(new MatchResult(serverId, card.path("name").asText(), score, reasons, mismatches));
        }

        out.sort(Comparator.comparingInt(MatchResult::score).reversed()
                .thenComparing(MatchResult::serverId));
        return List.copyOf(out);
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

    /**
     * 카드가 선언한 말 중 하나가 요청 구절에 들어 있는가.
     *
     * <p>{@code CapabilityCardLoader.matchOne} 과 같은 방향이다 — 카드의 말을 요청 안에서
     * 찾는다. 반대로 하면 "교통" 이라는 말 하나가 "교통량 반영 안 함" 같은 문장에도 걸린다.
     */
    private boolean anyKeyMatches(JsonNode keys, String phrase) {
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
