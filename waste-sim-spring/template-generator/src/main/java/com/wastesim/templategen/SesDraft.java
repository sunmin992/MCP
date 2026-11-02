package com.wastesim.templategen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 생성한 템플릿 후보를 SES 트리 초안(spec §6-1 형식)으로 감싼다.
 *
 * <p>설정 클래스가 평평하므로 계층은 정할 수 없다 — 모든 결정을 루트 "초안" 아래 평평하게 두고
 * "엔티티 배치 필요" 를 붙인다. 결정 종류는 이름·값 종류 규칙으로만 정한다(LLM 없음).
 */
final class SesDraft {

    static final String ROOT = "초안";
    private static final Pattern COUNT_NAME = Pattern.compile("^num[A-Z].*|.*Count$");
    private static final ObjectMapper M = new ObjectMapper();

    private SesDraft() {
    }

    static String kindOf(GeneratedTemplate t) {
        boolean enumLike = ("ENUM".equals(t.valueType()) || "ENUM_LIST".equals(t.valueType()))
                && !t.allowed().isEmpty();
        if (enumLike) return "SPECIALIZATION";
        if ("INTEGER".equals(t.valueType()) && COUNT_NAME.matcher(t.configField()).matches()) return "MULTI_ASPECT";
        return "ATTRIBUTE";
    }

    static ObjectNode draft(TemplateGenerator.Result result) {
        ObjectNode root = M.createObjectNode();
        root.put("sesId", "draft");
        root.put("sesVersion", "0");
        root.put("root", ROOT);
        ArrayNode multi = M.createArrayNode();
        ArrayNode specs = M.createArrayNode();
        ArrayNode attrs = M.createArrayNode();
        ArrayNode decisions = M.createArrayNode();

        for (GeneratedTemplate t : result.templates()) {
            String kind = kindOf(t);
            String name = t.configField();
            ArrayNode review = M.createArrayNode();
            switch (kind) {
                case "SPECIALIZATION" -> {
                    ArrayNode c = specs.addObject().put("name", name).putArray("children");
                    t.allowed().forEach(c::add);
                }
                case "MULTI_ASPECT" -> {
                    ObjectNode m = multi.addObject();
                    m.put("kind", "multi").put("name", name);
                    m.putArray("children");
                    m.putArray("_review").add("무엇의 개수인지 확인");
                    review.add("무엇의 개수인지 확인");
                }
                default -> attrs.add(name);
            }
            t.needsReview().forEach(review::add);

            ObjectNode d = decisions.addObject();
            d.put("at", ROOT + "/" + name);
            d.put("templateId", t.templateId());
            d.put("answerKey", t.answerKey());
            d.put("valueType", t.valueType());
            d.put("generateWhen", t.generateWhen());
            if (t.generateWhenExpr() != null) d.put("generateWhenExpr", t.generateWhenExpr());
            if (!t.allowed().isEmpty()) {
                ArrayNode a = d.putArray("allowed");
                t.allowed().forEach(a::add);
            }
            boolean integral = "INTEGER".equals(t.valueType()) || "INTEGER_LIST".equals(t.valueType());
            if (t.min() != null) d.putPOJO("min", integral ? (Object) t.min().longValue() : t.min());
            if (t.max() != null) d.putPOJO("max", integral ? (Object) t.max().longValue() : t.max());
            if (t.unit() != null) d.put("unit", t.unit());
            d.putPOJO("defaultValue", t.defaultValue());
            d.put("defaultBasis", t.defaultBasis());
            d.put("question", t.question());
            d.put("configField", name);
            d.put("nodeKind", kind);
            d.set("_review", review);
        }

        ObjectNode entity = root.putObject("entities").putObject(ROOT);
        entity.set("decompositions", multi);
        entity.set("specs", specs);
        entity.set("attrs", attrs);
        entity.putArray("_review").add("엔티티 배치 필요");
        root.set("decisions", decisions);
        return root;
    }

    /** configField 로 짝지어 nodeKind 가 다른 결정. templates 는 원본에서 파생된 템플릿 JSON 이다. */
    static List<String> kindDiff(ObjectNode draft, JsonNode templates) {
        Map<String, String> original = new LinkedHashMap<>();
        for (JsonNode t : templates.path("templates")) {
            original.put(t.path("configField").asText(), t.path("nodeKind").asText());
        }
        List<String> out = new ArrayList<>();
        for (JsonNode d : draft.path("decisions")) {
            String field = d.path("configField").asText();
            String orig = original.get(field);
            String mine = d.path("nodeKind").asText();
            if (orig != null && !orig.equals(mine)) out.add(field + ": 초안 " + mine + " · 원본 " + orig);
        }
        return out;
    }

    /** kindDiff 결과("필드: 초안 X · 원본 Y")를 보고서 절로 만든다. */
    static String kindDiffMarkdown(List<String> diff) {
        StringBuilder sb = new StringBuilder("## 결정 종류 차이 (초안 · 원본)\n\n");
        if (diff.isEmpty()) return sb.append("없음\n").toString();
        sb.append("| configField | 초안 | 원본 |\n|---|---|---|\n");
        for (String line : diff) {
            String[] f = line.split(": 초안 | · 원본 ");
            sb.append("| ").append(f[0]).append(" | ").append(f[1]).append(" | ").append(f[2]).append(" |\n");
        }
        return sb.toString();
    }
}
