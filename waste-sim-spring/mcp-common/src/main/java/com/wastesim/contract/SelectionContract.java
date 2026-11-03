package com.wastesim.contract;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.readiness.ValidationReceipt;
import java.util.*;

/** Client-forwarded broker evidence, not an authentication token or execution approval. */
public final class SelectionContract {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SelectionContract() {}
    public static ObjectNode handoff(String matchId, JsonNode card, JsonNode requirements) {
        ObjectNode out = JSON.createObjectNode();
        out.put("schemaVersion", "1.0.0"); out.put("matchId", matchId);
        out.put("serverId", card.path("serverId").asText());
        out.put("cardDigest", ValidationReceipt.fingerprint(card));
        out.set("requirements", RequirementContract.validate(requirements));
        return out;
    }
    public static JsonNode requirements(JsonNode args, JsonNode card) {
        JsonNode selection = args.path("selection");
        if (selection.isMissingNode()) return RequirementContract.validate(args.get("requirements"));
        if (!selection.isObject()) throw new IllegalArgumentException("selection must be an object");
        RequirementContract.version(selection);
        if (selection.path("matchId").asText().isBlank()
                || !selection.path("serverId").asText().equals(card.path("serverId").asText())
                || !selection.path("cardDigest").asText().equals(ValidationReceipt.fingerprint(card)))
            throw new IllegalArgumentException("선택 서버 또는 능력 카드가 달라졌습니다. 브로커에서 다시 조회하십시오");
        JsonNode requirements = RequirementContract.validate(selection.get("requirements"));
        if (args.has("requirements") && !requirements.equals(RequirementContract.validate(args.get("requirements"))))
            throw new IllegalArgumentException("선택 당시 요구와 현재 요구가 다릅니다. 다시 매칭하십시오");
        return requirements;
    }
    public record Report(boolean ready, List<RequirementContract.Check> checks, List<String> errors) {}
    /** Verify capabilities AND the generated artifacts; a card match alone never proves configuration. */
    public static Report verify(JsonNode requirements, JsonNode card, JsonNode configs,
                                Collection<String> observations, Collection<String> available) {
        List<RequirementContract.Check> checks = RequirementContract.evaluate(requirements, card).stream().map(c -> {
            boolean regionResolved = c.field().equals("regionId") && configs.isArray() && !configs.isEmpty();
            if (regionResolved) for (JsonNode cfg : configs)
                regionResolved &= c.requested().equals(cfg.path("regionId"))
                        && cfg.path("regionDataDigest").isTextual() && !cfg.path("regionDataDigest").asText().isBlank();
            if (c.status().equals("CONDITIONAL") && (regionResolved || !c.field().equals("regionId")
                    && available.contains(c.requested().asText()) && observations.contains(c.requested().asText())))
                return new RequirementContract.Check(c.field(), c.requested(), c.declared(), "SATISFIED",
                        "CONFIGURATION_VERIFIED", "NONE", c.required());
            return c;
        }).toList();
        List<String> errors = new ArrayList<>();
        for (var check : checks) if (check.required() && !check.status().equals("SATISFIED"))
            errors.add(check.field() + ": " + check.code());
        if (!configs.isArray() || configs.isEmpty()) errors.add("실행 설정이 없습니다");
        for (JsonNode cfg : configs) {
            if (requirements.has("regionId") && !requirements.get("regionId").equals(cfg.path("regionId")))
                errors.add("regionId: 요청과 실제 설정 불일치");
            if (requirements.has("simulationDuration") && (!cfg.path("days").isNumber()
                    || Math.abs(RequirementContract.seconds(requirements.get("simulationDuration")) - cfg.path("days").asDouble() * 86400) > 1e-6))
                errors.add("simulationDuration: 요청과 실제 설정 불일치");
            requirements.path("constraints").fields().forEachRemaining(e -> {
                if (!cfg.path(e.getKey()).isNumber() || Math.abs(cfg.path(e.getKey()).asDouble() - e.getValue().asDouble()) > 1e-9)
                    errors.add("constraints." + e.getKey() + ": 요청과 실제 설정 불일치");
            });
        }
        for (JsonNode metric : requirements.path("requiredOutputs"))
            if (!observations.contains(metric.asText()) || !available.contains(metric.asText()))
                errors.add("requiredOutputs." + metric.asText() + ": 관측 설정 누락 또는 현재 구성에서 미지원");
        return new Report(errors.isEmpty(), checks, List.copyOf(errors));
    }
}
