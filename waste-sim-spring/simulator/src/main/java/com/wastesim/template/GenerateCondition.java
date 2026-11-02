package com.wastesim.template;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서브태스크를 <b>언제 만드는가</b> — 템플릿 JSON 에 적힌 식.
 *
 * <p>조건을 Java 에 이름으로 박아 두면 새 시뮬레이터를 등록할 때마다 코드를 고쳐야 한다. 그래서
 * 식을 템플릿에 데이터로 적고, 서버는 이 평가기 하나로 판정한다. 식이 무엇을 읽는지는
 * {@link #inputs()} 가 식에서 바로 뽑으므로 따로 적지 않는다 — 읽는 키가 답변키인지와 순환이
 * 없는지는 {@link TemplateCatalog} 가 불러올 때 검사한다.
 *
 * <pre>
 * "ALWAYS"
 * {"all": [식...]}  {"any": [식...]}  {"not": 식}
 * {"eq": [키, 값]}  {"ne": ..}  {"gt": [키, 수]}  {"gte": ..}  {"lt": ..}  {"lte": ..}
 * {"given": 키}     값이 있고 빈 목록·빈 문자열이 아니다
 * </pre>
 *
 * <p>세 갈래로 답한다(Kleene 3값 논리). 비교는 답이 없으면 {@link Activation#UNKNOWN}(보류)이다 —
 * 보류를 거짓으로 뭉개면 조건이 나중에 참이 되어도 그 결정을 영영 묻지 않는다. {@code all} 은 하나라도
 * 거짓으로 확정되면 나머지를 몰라도 거짓이다: 차량이 1대로 정해지면 건물 수와 상관없이 배차 간격은
 * 필요 없다. {@code given} 만은 답이 없으면 거짓이다 — "말하지 않았으면 필요 없음" 인 결정에 쓴다.
 */
public final class GenerateCondition {

    public static final GenerateCondition ALWAYS = new GenerateCondition(TextNode.valueOf("ALWAYS"));

    private static final Set<String> COMPARE = Set.of("eq", "ne", "gt", "gte", "lt", "lte");

    private final JsonNode expr;
    private final List<String> inputs;

    private GenerateCondition(JsonNode expr) {
        this.expr = expr;
        Set<String> keys = new LinkedHashSet<>();
        if (!expr.isTextual()) collect(expr, keys);
        this.inputs = List.copyOf(keys);
    }

    @JsonCreator
    public static GenerateCondition of(JsonNode expr) {
        if (expr == null || expr.isNull()) throw new IllegalArgumentException("생성 조건이 비어 있습니다");
        if (expr.isTextual() && "ALWAYS".equals(expr.asText())) return ALWAYS;
        if (!expr.isObject()) throw bad(expr);
        return new GenerateCondition(expr);
    }

    @JsonValue
    public JsonNode expr() {
        return expr;
    }

    public boolean always() {
        return this == ALWAYS;
    }

    /** 이 조건이 읽는 답변키. 보류일 때 무엇을 먼저 정해야 하는지 알리는 데 쓴다. */
    public List<String> inputs() {
        return inputs;
    }

    public Activation evaluate(Map<String, Object> answers) {
        return always() ? Activation.ACTIVE : judge(expr, answers);
    }

    private static Activation judge(JsonNode e, Map<String, Object> answers) {
        String op = op(e);
        JsonNode arg = e.get(op);
        switch (op) {
            case "all", "any" -> {
                boolean all = op.equals("all");
                Activation decisive = all ? Activation.INACTIVE : Activation.ACTIVE;
                boolean unknown = false;
                for (JsonNode p : arg) {
                    Activation a = judge(p, answers);
                    if (a == decisive) return decisive;
                    if (a == Activation.UNKNOWN) unknown = true;
                }
                return unknown ? Activation.UNKNOWN : all ? Activation.ACTIVE : Activation.INACTIVE;
            }
            case "not" -> {
                return switch (judge(arg, answers)) {
                    case ACTIVE -> Activation.INACTIVE;
                    case INACTIVE -> Activation.ACTIVE;
                    case UNKNOWN -> Activation.UNKNOWN;
                };
            }
            case "given" -> {
                Object v = answers.get(arg.asText());
                boolean given = v instanceof Collection<?> c ? !c.isEmpty() : v != null && !String.valueOf(v).isBlank();
                return given ? Activation.ACTIVE : Activation.INACTIVE;
            }
            default -> {
                Object v = answers.get(arg.get(0).asText());
                if (v == null) return Activation.UNKNOWN;
                JsonNode want = arg.get(1);
                Boolean r;
                if (op.equals("eq") || op.equals("ne")) {
                    Double n = want.isNumber() ? numberOf(v) : null;
                    boolean same = want.isNumber() ? n != null && n == want.asDouble() : want.asText().equals(String.valueOf(v));
                    r = op.equals("eq") == same;
                } else {
                    Double n = numberOf(v);
                    if (n == null) return Activation.UNKNOWN;
                    double w = want.asDouble();
                    r = switch (op) {
                        case "gt" -> n > w;
                        case "gte" -> n >= w;
                        case "lt" -> n < w;
                        default -> n <= w;
                    };
                }
                return r ? Activation.ACTIVE : Activation.INACTIVE;
            }
        }
    }

    /** 식의 연산자. 모르는 꼴이면 불러올 때 바로 막는다 — 평가 중에 터지면 대화 한가운데서 멈춘다. */
    private static String op(JsonNode e) {
        if (e == null || !e.isObject() || e.size() != 1) throw bad(e);
        String op = e.fieldNames().next();
        JsonNode arg = e.get(op);
        boolean ok = switch (op) {
            case "all", "any" -> arg.isArray() && !arg.isEmpty();
            case "not" -> true;
            case "given" -> arg.isTextual();
            default -> COMPARE.contains(op) && arg.isArray() && arg.size() == 2 && arg.get(0).isTextual()
                    && (op.equals("eq") || op.equals("ne") || arg.get(1).isNumber());
        };
        if (!ok) throw bad(e);
        return op;
    }

    private static void collect(JsonNode e, Set<String> keys) {
        String op = op(e);
        JsonNode arg = e.get(op);
        switch (op) {
            case "all", "any" -> arg.forEach(p -> collect(p, keys));
            case "not" -> collect(arg, keys);
            case "given" -> keys.add(arg.asText());
            default -> keys.add(arg.get(0).asText());
        }
    }

    private static Double numberOf(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static IllegalArgumentException bad(JsonNode e) {
        return new IllegalArgumentException("생성 조건 식을 읽지 못했습니다: " + e);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GenerateCondition g && g.expr.equals(expr);
    }

    @Override
    public int hashCode() {
        return expr.hashCode();
    }

    @Override
    public String toString() {
        return expr.isTextual() ? expr.asText() : expr.toString();
    }
}
