package com.wastesim.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;

/**
 * 브로커가 받는 요구(공통 계약)의 근거 검사. 요구는 모든 시뮬레이터가 같은 계약을 쓰므로 이 검사도 시뮬레이터에 묶이지 않는다.
 *
 * <p>요구의 항목은 LLM 이 사용자 발화에서 옮긴 것이다. 발화 목록이 오면 항목마다 근거 구절(evidence)이 실제 발화에 있어야 하고,
 * 값이 구절로 설명되어야 한다.
 * <ul>
 *   <li>regionId: 구절에 지역명이 들어 있어야 한다.</li>
 *   <li>simulationDuration · timeResolution: 구절의 숫자가 값과 같고 구절에 그 단위의 말(일·주·시간·분·초)이 있어야 한다.
 *       "이틀" 같은 한국어 수 표현은 숫자로 바꿔 본다. "며칠" 처럼 수가 없는 구절은 값을 설명하지 못하므로 막는다.</li>
 *   <li>constraints · preferences: 단위를 모르므로 알려진 단위 바꿈 가운데 하나로라도 값이 설명되면 통과다. 수가 없는 구절은 막는다.
 *       선호(preferences)의 구절에는 "가능하면 · 좋겠어" 같은 선호 표현이 있어야 하고, 필수(constraints)의 구절에는 없어야 한다.</li>
 *   <li>requiredOutputs: 구절이 발화에 있는지만 본다. 어떤 지표로 옮겼는지가 맞는지는 판단하지 못한다.</li>
 * </ul>
 * 근거 구절의 키는 항목 이름이다(constraints · preferences 는 "constraints.필드" 또는 "필드").
 */
public final class RequirementEvidence {
    private RequirementEvidence() {}

    private static final Map<String, List<String>> TIME_WORDS = Map.of(
            "second", List.of("초", "second"), "minute", List.of("분", "minute"), "hour", List.of("시간", "hour"),
            "day", List.of("일", "날", "day"), "week", List.of("주", "week"));

    /** 선호 조건의 표지. 추출 규칙 "가능하면/좋겠어는 preferences, 그 밖은 constraints" 와 같은 기준이다. */
    private static final List<String> PREFERENCE_WORDS = List.of("가능하면", "가능한한", "가급적", "되도록", "웬만하면", "좋겠", "이왕이면", "선호");

    public static EvidenceGuard.Result check(JsonNode requirements, List<String> utterances) {
        List<String> blocks = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        if (requirements == null || !requirements.isObject()) return new EvidenceGuard.Result(blocks, unverified);
        JsonNode evidence = requirements.path("evidence");
        if (requirements.has("regionId")) {
            String region = requirements.get("regionId").asText();
            quote(evidence, "regionId", utterances, blocks).ifPresent(q -> {
                if (!EvidenceGuard.squash(q).contains(EvidenceGuard.squash(region)))
                    blocks.add("regionId: 근거 구절 '" + q + "' 에 지역명 '" + region + "' 이(가) 없습니다 — 사용자가 말한 지역명 그대로 넣으십시오");
            });
        }
        for (String key : List.of("simulationDuration", "timeResolution")) {
            if (!requirements.has(key)) continue;
            JsonNode v = requirements.get(key);
            quote(evidence, key, utterances, blocks).ifPresent(q -> {
                List<Double> nums = EvidenceGuard.numbers(q);
                if (nums.isEmpty()) { blocks.add(noNumber(key, q)); return; }
                List<String> words = TIME_WORDS.getOrDefault(v.path("unit").asText(), List.of());
                boolean number = nums.stream().anyMatch(n -> n == v.path("value").asDouble());
                boolean word = words.stream().anyMatch(q::contains);
                if (!number || !word)
                    blocks.add(key + ": 사용자 발화 '" + q + "' 와 " + v.path("value").asText() + " " + v.path("unit").asText()
                            + " 이(가) 맞지 않습니다 — 사용자가 말한 수와 단위 그대로 넣으십시오");
            });
        }
        for (String group : List.of("constraints", "preferences")) {
            // 사용자 표현 형식 [{name, value, unit}] — 근거 키는 "constraints.이름" 또는 "이름"
            for (JsonNode c : requirements.path(group).isArray() ? requirements.get(group) : JsonNodeFactory.instance.arrayNode()) {
                String name = c.path("name").asText(), key = group + "." + name;
                Optional<String> q = evidence.has(key) ? quote(evidence, key, utterances, blocks)
                        : quote(evidence, name, utterances, blocks, key);
                q.ifPresent(text -> {
                    if (EvidenceGuard.numbers(text).isEmpty()) { blocks.add(noNumber(key, text)); return; }
                    strength(group, key, text, blocks);
                    double value = c.path("value").asDouble();
                    if (EvidenceGuard.numbers(text).stream().noneMatch(n -> n == value))
                        blocks.add(key + ": 사용자 발화 '" + text + "' 와 값 " + c.path("value").asText() + " 이(가) 다릅니다 — 사용자가 말한 수 그대로 넣으십시오");
                    else if (c.hasNonNull("unit") && !text.contains(c.get("unit").asText()))
                        blocks.add(key + ": 사용자 발화 '" + text + "' 에 단위 '" + c.get("unit").asText() + "' 이(가) 없습니다 — 사용자가 말한 단위 그대로 넣으십시오");
                });
            }
            requirements.path(group).fields().forEachRemaining(e -> {
                String key = group + "." + e.getKey();
                Optional<String> q = evidence.has(key) ? quote(evidence, key, utterances, blocks)
                        : quote(evidence, e.getKey(), utterances, blocks, key);
                q.ifPresent(text -> {
                    if (EvidenceGuard.numbers(text).isEmpty()) { blocks.add(noNumber(key, text)); return; }
                    strength(group, key, text, blocks);
                    double value = e.getValue().asDouble();
                    boolean explained = EvidenceGuard.numbers(text).stream().anyMatch(n -> Arrays.asList(null, "minute", "day", "ratio", "kg", "won")
                            .stream().anyMatch(u -> UnitEvidence.explains(n, value, text, u)));
                    if (!explained)
                        blocks.add(key + ": 사용자 발화 '" + text + "' 와 값 " + e.getValue().asText() + " 이(가) 다릅니다 — 사용자가 말한 값으로 고치거나 다시 물으십시오");
                });
            });
        }
        if (requirements.path("requiredOutputs").isArray() && !requirements.path("requiredOutputs").isEmpty()) {
            quote(evidence, "requiredOutputs", utterances, blocks).ifPresent(q -> unverified.add("requiredOutputs"));
        }
        return new EvidenceGuard.Result(blocks, unverified);
    }

    private static String noNumber(String key, String quote) {
        return key + ": 근거 구절 '" + quote + "' 에 수가 없습니다 — 사용자가 수를 말하지 않았으면 요구에서 빼거나 사용자에게 물으십시오";
    }

    /** 선호/필수를 바꿔 넣으면 후보 범위가 달라진다 — 필수를 선호로 내리면 카드 범위를 넘는 요청도 통과한다. */
    private static void strength(String group, String key, String quote, List<String> blocks) {
        String q = EvidenceGuard.squash(quote);
        boolean preference = PREFERENCE_WORDS.stream().anyMatch(q::contains);
        if (group.equals("preferences") && !preference)
            blocks.add(key + ": 근거 구절 '" + quote + "' 에 '가능하면 · 좋겠어' 같은 선호 표현이 없습니다 — 사용자가 선호로 말하지 않았으면 constraints 에 넣고, 말했으면 그 표현까지 구절에 넣으십시오");
        else if (group.equals("constraints") && preference)
            blocks.add(key + ": 근거 구절 '" + quote + "' 은(는) 선호 표현입니다 — preferences 에 넣으십시오");
    }

    private static Optional<String> quote(JsonNode evidence, String key, List<String> utterances, List<String> blocks) {
        return quote(evidence, key, utterances, blocks, key);
    }

    private static Optional<String> quote(JsonNode evidence, String key, List<String> utterances, List<String> blocks, String label) {
        String q = KoreanNumbers.normalize(evidence.path(key).asText("").trim());
        if (q.isEmpty()) {
            blocks.add(label + ": 근거 구절(requirements.evidence." + key + ")이 없습니다 — 사용자가 말한 조건만 요구에 넣고, 말한 구절을 함께 넣으십시오. 말하지 않은 조건은 빼십시오");
            return Optional.empty();
        }
        if (!EvidenceGuard.found(q, utterances)) {
            blocks.add(label + ": 근거 구절 '" + q + "' 이(가) 사용자 발화에 없습니다 — 사용자가 말하지 않은 조건이면 요구에서 빼거나 사용자에게 물으십시오");
            return Optional.empty();
        }
        return Optional.of(q);
    }
}
