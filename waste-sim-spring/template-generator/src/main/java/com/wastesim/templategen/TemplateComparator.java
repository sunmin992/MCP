package com.wastesim.templategen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 생성한 초안을 손으로 쓴 템플릿과 칸별로 대조한다.
 *
 * <p>짝은 {@code configField}로 맞춘다 — 답변키({@code truckCount})는 제공자가 지은 이름이라
 * 코드에 없지만, 설정 필드({@code numTrucks})는 양쪽에 같다.
 *
 * <p>채점하는 칸은 코드에서 뽑을 수 있다고 주장하는 여섯 칸이다: 값 종류 · 선택지 · 기본값 ·
 * 하한 · 상한 · 단위. 질문 문장 · SES 경로 · 노드 종류는 자유 서술이라 일치를 셀 수 없고,
 * 생성 조건은 이 판이 뽑지 않으므로(항상 ALWAYS) 채점하면 우연히 맞은 칸이 점수가 된다 —
 * 따로 센다.
 */
public final class TemplateComparator {

    static final List<String> SCORED = List.of("valueType", "allowed", "defaultValue", "min", "max", "unit");

    /** 칸 하나의 대조. {@code handHasValue}가 거짓이면 "둘 다 비어 있음" 으로 맞은 칸이다. */
    public record Cell(String field, Object hand, Object generated, boolean match, boolean handHasValue) {
    }

    public record Row(String templateId, String configField, boolean found, List<Cell> cells,
                      String handGenerateWhen) {
    }

    public record Report(List<Row> rows, List<String> notExposed, Map<String, String> skipped) {

        public int total() {
            return rows.size() * SCORED.size();
        }

        public int matched() {
            return (int) rows.stream().flatMap(r -> r.cells().stream()).filter(Cell::match).count();
        }

        /** 손으로 쓴 쪽에 값이 있는 칸만 — "비어 있어서 맞은" 칸을 뺀 점수. */
        public int totalWithValue() {
            return (int) rows.stream().flatMap(r -> r.cells().stream()).filter(Cell::handHasValue).count();
        }

        public int matchedWithValue() {
            return (int) rows.stream().flatMap(r -> r.cells().stream())
                    .filter(c -> c.handHasValue() && c.match()).count();
        }

        public long conditional() {
            return rows.stream().filter(r -> !"ALWAYS".equals(r.handGenerateWhen())).count();
        }
    }

    public Report compare(JsonNode handRoot, TemplateGenerator.Result generated) {
        Map<String, GeneratedTemplate> byField = new LinkedHashMap<>();
        generated.templates().forEach(t -> byField.put(t.configField(), t));

        List<Row> rows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JsonNode h : handRoot.path("templates")) {
            String field = h.path("configField").asText();
            GeneratedTemplate g = byField.get(field);
            used.add(field);
            List<Cell> cells = new ArrayList<>();
            for (String f : SCORED) {
                Object hv = handValue(h, f);
                Object gv = g == null ? null : generatedValue(g, f);
                boolean match = g != null && same(hv, gv);
                cells.add(new Cell(f, hv, gv, match, hasValue(hv)));
            }
            rows.add(new Row(h.path("templateId").asText(), field, g != null, cells,
                    h.path("generateWhen").asText()));
        }
        List<String> notExposed = byField.keySet().stream().filter(f -> !used.contains(f)).toList();
        return new Report(rows, notExposed, generated.skipped());
    }

    private static Object handValue(JsonNode h, String f) {
        JsonNode n = h.get(f);
        if (n == null || n.isNull()) return f.equals("allowed") ? List.of() : null;
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            n.forEach(e -> out.add(e.isNumber() ? (Object) e.asDouble() : e.asText()));
            return out;
        }
        if (n.isNumber()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean();
        return n.asText();
    }

    private static Object generatedValue(GeneratedTemplate g, String f) {
        return switch (f) {
            case "valueType" -> g.valueType();
            case "allowed" -> g.allowed();
            case "defaultValue" -> g.defaultValue() instanceof Number n ? (Object) n.doubleValue() : g.defaultValue();
            case "min" -> g.min();
            case "max" -> g.max();
            case "unit" -> g.unit();
            default -> throw new IllegalArgumentException(f);
        };
    }

    /** 선택지는 순서를 보지 않는다. 수는 double 로 맞춘다. */
    static boolean same(Object a, Object b) {
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            return la.size() == lb.size() && new HashSet<>(la).equals(new HashSet<>(lb));
        }
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a, b);
    }

    private static boolean hasValue(Object v) {
        if (v == null) return false;
        return !(v instanceof List<?> l) || !l.isEmpty();
    }
}
