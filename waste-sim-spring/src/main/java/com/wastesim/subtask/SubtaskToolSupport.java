package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ValidationError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서브태스크 MCP 도구 3종이 공유하는 인자 해석·응답 조립(SDD 2.18.8).
 *
 * <p>세 도구가 같은 방식으로 세트를 찾고 같은 모양으로 서브태스크를 내보내야 하는데,
 * 그 코드를 도구마다 복사하면 <b>한 도구만 다른 세트를 보게 되는</b> 종류의 어긋남이
 * 생긴다. 특히 버전 불일치 거부(FR-138)는 세 도구에서 규칙이 같아야 의미가 있다 —
 * 한 곳이라도 "가까운 버전으로 맞춰 주면" 그 경로로 우회할 수 있다.
 */
final class SubtaskToolSupport {

    private SubtaskToolSupport() {}

    /**
     * 요청이 가리키는 세트를 찾는다.
     *
     * @return 세트, 또는 거부 사유(둘 중 하나만 채워진다)
     */
    static Resolved resolveSet(JangnyangSubtaskCatalog catalog, JsonNode args) {
        JsonNode versionNode = args == null ? null : args.get("version");
        if (versionNode == null || versionNode.isNull()) {
            return Resolved.of(catalog.latest());
        }
        if (!versionNode.isIntegralNumber()) {
            return Resolved.error(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "version",
                    "version은 정수여야 한다(받은 값: " + versionNode.asText() + ")."));
        }
        int version = versionNode.asInt();
        JangnyangSubtaskDefinition def = catalog.byVersion(version);
        if (def == null) {
            // 가까운 버전으로 대체하지 않는다(FR-138·D-45·UT-300) — 조용히 다른 세트를
            // 주면 진행 중인 세션이 어떤 질문을 받았는지 사후에 재구성할 수 없다.
            return Resolved.error(new ValidationError(ErrorCode.OUT_OF_RANGE, "version",
                    "존재하지 않는 서브태스크 세트 버전이다: " + version
                            + " (등록된 버전: " + catalog.versions() + ")"));
        }
        return Resolved.of(def);
    }

    /** {@code subtaskSetId}가 왔으면 세트와 일치하는지 확인한다(FR-138). */
    static ValidationError checkSetId(JangnyangSubtaskDefinition def, JsonNode args) {
        String given = args == null ? null : args.path("subtaskSetId").asText(null);
        if (given == null || given.isBlank()) return null;
        if (!def.subtaskSetId().equals(given)) {
            return new ValidationError(ErrorCode.INVALID_ARGUMENTS, "subtaskSetId",
                    "세트 ID가 다르다(요청: " + given + ", 실제: " + def.subtaskSetId() + ").");
        }
        return null;
    }

    /** 세트를 MCP 응답 모양으로 편다. 불변 뷰이므로 응답을 통해 세트를 바꿀 수 없다(UT-302). */
    static Map<String, Object> describe(JangnyangSubtaskDefinition def) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subtaskSetId", def.subtaskSetId());
        out.put("version", def.version());
        out.put("immutable", def.immutable());
        out.put("hash", def.hash());
        List<Map<String, Object>> groups = new ArrayList<>();
        for (SubtaskGroup g : def.groups()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("order", g.order());
            gm.put("name", g.name());
            gm.put("description", g.description());
            groups.add(Ordered.copyOf(gm));
        }
        out.put("groups", List.copyOf(groups));
        List<Map<String, Object>> items = new ArrayList<>();
        for (JangnyangSubtask s : def.ordered()) items.add(describe(s));
        out.put("subtasks", List.copyOf(items));
        return Ordered.copyOf(out);
    }

    /** FR-120이 요구하는 열 항목을 하나도 빠짐없이 내보낸다. */
    static Map<String, Object> describe(JangnyangSubtask s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("order", s.order());
        // 단계 정보 — 외부 클라이언트도 사용자에게 ST 번호 대신 단계를 보여야 한다.
        m.put("group", s.group());
        m.put("stage", s.stage().name());
        m.put("question", s.question());
        m.put("answerField", s.answerField());
        m.put("answerType", s.answerType().name());
        m.put("required", s.required());
        m.put("allowsNotApplicable", s.allowsNotApplicable());
        m.put("allowedRange", describe(s.allowedRange()));
        m.put("validationRule", s.validationRule());
        m.put("retryQuestion", s.retryQuestion());
        m.put("completionCondition", s.completionCondition());
        return Ordered.copyOf(m);
    }

    private static Map<String, Object> describe(AllowedRange r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("description", r.description());
        if (r.min() != null) m.put("min", r.min());
        if (r.max() != null) m.put("max", r.max());
        if (r.minLength() != null) m.put("minLength", r.minLength());
        if (r.maxLength() != null) m.put("maxLength", r.maxLength());
        if (r.minItems() != null) m.put("minItems", r.minItems());
        if (r.maxItems() != null) m.put("maxItems", r.maxItems());
        if (r.sumTo() != null) m.put("sumTo", r.sumTo());
        if (!r.valuesOrEmpty().isEmpty()) m.put("values", r.valuesOrEmpty());
        return Ordered.copyOf(m);
    }

    /** 오류 항목을 MCP 응답 모양으로 — 서브태스크 ID·사유 코드·재질문 문장(SDD 2.18.8). */
    static List<Map<String, Object>> describe(List<SubtaskError> errors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SubtaskError e : errors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subtaskId", e.subtaskId());
            m.put("code", e.code() == null ? ErrorCode.INVALID_ARGUMENTS.name() : e.code().name());
            m.put("reason", e.reason());
            m.put("retryQuestion", e.retryQuestion());
            out.add(Ordered.copyOf(m));
        }
        return List.copyOf(out);
    }

    /**
     * {@code answers} 인자를 서브태스크 ID → 원시 값 맵으로 편다.
     *
     * <p>두 표기를 모두 받는다 — 객체({@code {"ST-01": "..."}})와 배열
     * ({@code [{"subtaskId":"ST-01","value":"..."}]}). 외부 MCP 클라이언트가 어느 쪽을
     * 보낼지 강제할 수 없어서인데, 어느 쪽이든 <b>같은 검증기</b>로 들어가므로 관대함이
     * 검증까지 번지지는 않는다.
     */
    static Map<String, Object> readAnswers(JsonNode args) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode node = args == null ? null : args.get("answers");
        if (node == null || node.isNull()) return out;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), toJava(e.getValue())));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("subtaskId").asText(item.path("id").asText(null));
                if (id == null || id.isBlank()) id = item.path("answerField").asText(null);
                if (id == null || id.isBlank()) continue;
                out.put(id, toJava(item.get("value")));
            }
        }
        return out;
    }

    /** JSON 값을 검증기가 받는 자바 값으로. 목록은 목록으로, 나머지는 스칼라로 유지한다. */
    private static Object toJava(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : n) list.add(toJava(item));
            return list;
        }
        if (n.isBoolean()) return n.booleanValue();
        if (n.isIntegralNumber()) return n.longValue();
        if (n.isNumber()) return n.doubleValue();
        return n.asText();
    }

    record Resolved(JangnyangSubtaskDefinition def, ValidationError error) {
        static Resolved of(JangnyangSubtaskDefinition d) { return new Resolved(d, null); }
        static Resolved error(ValidationError e) { return new Resolved(null, e); }
        boolean ok() { return def != null; }
    }
}
