package com.wastesim.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** SES 트리 원본에서 커밋되는 파생물을 만든다. 파생물이 원본과 어긋나면 SesDerivationTest 가 잡는다. */
public final class SesDerivation {

    private SesDerivation() {
    }

    /** 템플릿 JSON. 각 템플릿 = 결정의 템플릿 칸 + sesPath + nodeKind. 조건의 {@code active} 는 펼친다. */
    public static ObjectNode templates(SesTree ses) {
        Map<String, JsonNode> conditions = conditions(ses);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("sesId", ses.sesId());
        out.put("sesVersion", ses.sesVersion());
        ArrayNode templates = out.putArray("templates");
        for (SesTree.Decision d : ses.decisions()) {
            // 값을 누가 정할 수 있는지는 결정마다 반드시 적는다 — 확인 화면 · 질문 정책이 이것으로 갈린다.
            if (!com.wastesim.template.SubtaskTemplate.ROLES.contains(d.fields().path("decisionRole").asText())
                    || d.fields().path("roleBasis").asText().isBlank())
                throw new IllegalArgumentException("decisionRole(INTENT · DATA · ASSUMPTION)과 roleBasis 가 필요합니다: " + d.sesPath());
            ObjectNode t = d.fields().deepCopy();
            t.remove("constraint");   // 브로커에 공개할 조건 표현 — 카드로만 간다(derivedCard)
            if (t.has("generateWhen")) t.set("generateWhen", conditions.get(t.path("answerKey").asText()));
            t.put("sesPath", d.sesPath());
            t.put("nodeKind", d.nodeKind());
            templates.add(t);
        }
        return out;
    }

    /**
     * 카드의 sesDigest. decisionPoint = path · kind · field + 결정의 card 칸.
     *
     * <p>조건 설명(generateWhen) · 범위(range 의 min/max/unit) · 기본값(default)은 card 칸에 손으로 적지 않고
     * 결정의 조건식 · 범위 · 기본값에서 만든다. 손으로 적으면 규칙을 고칠 때 카드만 낡은 채 남는다.
     */
    public static ObjectNode digest(SesTree ses) {
        Map<String, JsonNode> conditions = conditions(ses);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("sesId", ses.sesId());
        out.put("sesVersion", ses.sesVersion());
        ArrayNode entities = out.putArray("entities");
        ses.raw().get("entities").forEach(e -> {
            if (e.hasNonNull("label")) {
                entities.add(e.get("label").asText());
            }
        });
        ArrayNode points = out.putArray("decisionPoints");
        for (SesTree.Decision d : ses.decisions()) {
            if (d.card() == null) {
                continue;
            }
            ObjectNode f = d.fields();
            ObjectNode p = points.addObject();
            p.put("path", d.sesPath());
            p.put("kind", d.nodeKind());
            p.set("field", f.get("configField"));
            p.setAll(d.card());
            String when = render(conditions.get(f.path("answerKey").asText()));
            if (when != null) p.put("generateWhen", when);
            if (f.hasNonNull("min") || f.hasNonNull("max")) {
                ObjectNode range = p.path("range").isObject() ? ((ObjectNode) p.get("range")).deepCopy()
                        : JsonNodeFactory.instance.objectNode();
                for (String k : new String[]{"min", "max", "unit"}) if (f.hasNonNull(k)) range.set(k, f.get(k));
                p.set("range", range);
            }
            if (f.has("defaultValue")) p.set("default", f.get("defaultValue"));
        }
        return out;
    }

    /**
     * 카드 사본에서 SES 로 만드는 칸만 바꾼다 — sesDigest, 그리고 브로커가 판정에 쓰는 조건 범위
     * {@code capabilities.constraints} 와 조건 표현 {@code capabilities.terms.constraints}. 키 순서는 그대로.
     *
     * <p>조건으로 공개할 결정은 SES 결정의 {@code constraint: {terms: [...]}} 로 고른다. 이름은 설정 필드,
     * 범위는 결정의 min/max, 단위 · 단위말은 결정의 unit/unitWords 에서 온다. 이 칸을 손으로 적으면 SES 의
     * 범위를 고쳐도 브로커는 예전 범위로 "맞음" 을 판정한다(R3 C4: 배차 간격 최대 1439 → 120).
     */
    public static ObjectNode derivedCard(ObjectNode card, SesTree ses) {
        ObjectNode copy = card.deepCopy();
        copy.set("sesDigest", digest(ses));
        ObjectNode ranges = JsonNodeFactory.instance.objectNode();
        ObjectNode terms = JsonNodeFactory.instance.objectNode();
        for (SesTree.Decision d : ses.decisions()) {
            ObjectNode f = d.fields();
            JsonNode c = f.get("constraint");
            if (c == null) continue;
            if (!c.path("terms").isArray() || c.path("terms").isEmpty())
                throw new IllegalArgumentException("constraint.terms 는 비지 않은 배열이어야 합니다: " + d.sesPath());
            String name = f.path("configField").asText();
            ObjectNode r = ranges.putObject(name);
            for (String k : new String[]{"min", "max"}) if (f.hasNonNull(k)) r.set(k, f.get(k));
            ObjectNode t = terms.putObject(name);
            t.set("terms", c.get("terms"));
            if (f.hasNonNull("unit")) t.set("unit", f.get("unit"));
            if (f.has("unitWords")) t.set("unitWords", f.get("unitWords"));
        }
        ObjectNode caps = (ObjectNode) copy.path("capabilities");
        caps.set("constraints", ranges);
        ((ObjectNode) caps.path("terms")).set("constraints", terms);
        return copy;
    }

    /** 답변키 → {@code active} 를 펼친 조건. 없는 결정 · ALWAYS 결정 참조와 순환은 막는다. */
    static Map<String, JsonNode> conditions(SesTree ses) {
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        for (SesTree.Decision d : ses.decisions())
            if (d.fields().has("generateWhen")) raw.put(d.fields().path("answerKey").asText(), d.fields().get("generateWhen"));
        Map<String, JsonNode> out = new LinkedHashMap<>();
        raw.forEach((key, cond) -> out.put(key, expand(cond, raw, new ArrayDeque<>(java.util.List.of(key)))));
        return out;
    }

    /**
     * {@code {"active": 답변키}} 를 그 결정의 생성 조건으로 바꾼다. 결정끼리의 의존을 조건 복사 없이 적게 하려는 것 —
     * 근거 결정의 조건을 고치면 따르는 결정도 함께 바뀐다. 펼친 {@code all}/{@code any} 가 같은 연산자 안에 들어가면
     * 이어 붙여, 펼친 결과가 손으로 적던 식과 같은 꼴이 되게 한다.
     */
    static JsonNode expand(JsonNode cond, Map<String, JsonNode> raw, Deque<String> visiting) {
        if (!cond.isObject() || cond.size() != 1) return cond;
        String op = cond.fieldNames().next();
        JsonNode arg = cond.get(op);
        switch (op) {
            case "active" -> {
                String key = arg.asText();
                JsonNode target = raw.get(key);
                if (target == null) throw new IllegalArgumentException("active 가 가리키는 결정이 없습니다: " + key);
                if (target.isTextual()) throw new IllegalArgumentException("ALWAYS 결정은 active 로 참조하지 않습니다: " + key);
                if (visiting.contains(key)) throw new IllegalArgumentException("active 가 순환합니다: " + visiting + " → " + key);
                visiting.push(key);
                JsonNode r = expand(target, raw, visiting);
                visiting.pop();
                return r;
            }
            case "all", "any" -> {
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                ArrayNode items = out.putArray(op);
                for (JsonNode c : arg) {
                    JsonNode e = expand(c, raw, visiting);
                    if (e.isObject() && e.has(op) && e.size() == 1) items.addAll((ArrayNode) e.get(op));
                    else items.add(e);
                }
                return out;
            }
            case "not" -> {
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                out.set("not", expand(arg, raw, visiting));
                return out;
            }
            default -> {
                return cond;
            }
        }
    }

    /** 조건식 → 카드에 싣는 한 줄 설명. 답변키 이름 그대로 쓴다. ALWAYS 는 설명이 없다. */
    static String render(JsonNode cond) {
        if (cond == null || cond.isTextual()) return null;
        String op = cond.fieldNames().next();
        JsonNode arg = cond.get(op);
        return switch (op) {
            case "all", "any" -> {
                StringBuilder s = new StringBuilder();
                for (JsonNode c : arg) {
                    if (!s.isEmpty()) s.append(op.equals("all") ? " && " : " || ");
                    String inner = render(c);
                    boolean group = c.has("all") || c.has("any");
                    s.append(group ? "(" + inner + ")" : inner);
                }
                yield s.toString();
            }
            case "not" -> arg.has("given") ? "!" + render(arg) : "!(" + render(arg) + ")";
            case "given" -> "given(" + arg.asText() + ")";
            default -> arg.get(0).asText() + " " + switch (op) {
                case "eq" -> "==";
                case "ne" -> "!=";
                case "gt" -> ">";
                case "gte" -> ">=";
                case "lt" -> "<";
                default -> "<=";
            } + " " + arg.get(1).asText();
        };
    }
}
