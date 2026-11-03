package com.wastesim.readiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Same revision identity for planning answers and scenario values. */
public final class RevisionInput {
    private RevisionInput() {}
    public static JsonNode of(JsonNode args) {
        var json = new ObjectMapper();
        var out = json.createObjectNode();
        out.set("answers", args.path(args.has("answers") ? "answers" : "values"));
        for (String k : new String[]{"selection", "regionName", "scenarioMode", "regionId", "requirements", "matchId", "origins", "variableAnswerKey", "variableValues", "userUtterances", "evidence", "purpose", "observations", "request"})
            out.set(k, args.path(k));
        return out;
    }
}
