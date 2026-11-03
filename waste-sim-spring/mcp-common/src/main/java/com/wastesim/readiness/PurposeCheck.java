package com.wastesim.readiness;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Checks an explicit, reviewable purpose specification; does not claim to understand arbitrary prose. */
public record PurposeCheck(String status, boolean acceptable, List<String> reasons, JsonNode specification) {
    public static PurposeCheck evaluate(JsonNode purpose, String actualKey, List<?> actualValues,
                                        List<String> observations, Set<String> available) {
        return evaluate(purpose, actualKey, actualValues, actualValues, observations, available);
    }
    public static PurposeCheck evaluate(JsonNode purpose, String actualKey, List<?> actualValues, List<?> effectiveValues,
                                        List<String> observations, Set<String> available) {
        if (purpose == null || purpose.isNull() || purpose.isMissingNode())
            return new PurposeCheck("NOT_SPECIFIED", true, List.of("목적 명세가 없어 사용자 요청과 설정의 일치 여부를 확인해야 합니다"), null);
        List<String> errors = new ArrayList<>();
        String mode = purpose.path("mode").asText();
        if (!purpose.isObject() || !(mode.equals("COMPARE") || mode.equals("RUN"))) errors.add("목적 mode는 COMPARE 또는 RUN이어야 합니다");
        String requestedKey = purpose.path("comparisonKey").asText();
        if (mode.equals("COMPARE")) {
            if (!requestedKey.equals(actualKey)) errors.add("목적의 비교 변수와 시나리오 변수가 다릅니다: " + requestedKey + " / " + actualKey);
            if (effectiveValues.stream().map(PurposeCheck::canonical).distinct().count() < 2) errors.add("비교 목적에는 서로 다른 실행 조건이 두 개 이상 필요합니다");
            var requested = purpose.path("comparisonValues");
            if (!requested.isArray() || requested.size() < 2) errors.add("비교 목적에는 요청한 comparisonValues를 두 개 이상 지정해야 합니다");
            else {
                Set<String> wantedValues = new HashSet<>();
                requested.forEach(v -> wantedValues.add(canonical(v.isNumber() ? v.decimalValue() : v.asText())));
                Set<String> builtValues = new HashSet<>();
                actualValues.forEach(v -> builtValues.add(canonical(v)));
                if (!wantedValues.equals(builtValues)) errors.add("요청한 비교 값과 실제 시나리오의 비교 값이 다릅니다");
            }
        }
        JsonNode wanted = purpose.path("observations");
        if (!wanted.isArray() || wanted.isEmpty()) errors.add("목적에 관측할 지표를 지정해야 합니다");
        for (JsonNode metric : wanted) {
            String key = metric.asText();
            if (!metric.isTextual() || key.isBlank() || !available.contains(key)) errors.add("현재 설정에서 관측할 수 없는 지표: " + key);
            if (!observations.contains(key)) errors.add("시나리오의 관측 목록에 목적 지표가 없습니다: " + key);
        }
        return new PurposeCheck(errors.isEmpty() ? "SATISFIED" : "MISMATCH", errors.isEmpty(), List.copyOf(errors), purpose.deepCopy());
    }
    private static String canonical(Object value) {
        if (value instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return String.valueOf(value).toUpperCase(Locale.ROOT);
    }
    public static List<String> verifyResults(JsonNode specification, JsonNode runs) {
        if (specification == null || !specification.isObject()) return List.of();
        List<String> errors = new ArrayList<>();
        if (!runs.isArray() || runs.isEmpty()) return List.of("실행 결과가 없습니다");
        int index = 0;
        for (JsonNode run : runs) {
            for (JsonNode metric : specification.path("observations")) {
                JsonNode actual = run.at("/" + metric.asText().replace(".", "/"));
                if (actual.isMissingNode() || actual.isNull()) errors.add("실행 " + index + "의 목적 지표가 누락됐습니다: " + metric.asText());
            }
            index++;
        }
        return List.copyOf(errors);
    }
}
