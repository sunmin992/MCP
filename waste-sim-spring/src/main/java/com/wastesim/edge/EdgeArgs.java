package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 엣지 도구 전용 인자 파서 겸 검증기. {@code McpToolProvider}는 공용
 * {@code SimulationConfigValidator}(장량동 도메인 규칙)를 거치지 않으므로, 이
 * 클래스가 그 자리를 대신한다 — 이 프로젝트의 fail-closed 원칙 그대로, 범위를
 * 벗어난 값은 <b>실행 전에</b> 전부 모아 거부한다(GPT가 엉뚱한 숫자를 만들어도
 * 물리 계산까지 도달하지 못하게 하는 구조적 차단).
 *
 * <p>오류를 만나도 즉시 던지지 않고 계속 모으는 이유: 학생이 인자를 여러 개 잘못
 * 넣었을 때 한 번에 다 알려주는 편이 시행착오가 적기 때문이다.
 */
public class EdgeArgs {

    private final JsonNode node;
    private final String prefix;
    private final List<ValidationError> errors;

    public EdgeArgs(JsonNode node) { this(node, "", new ArrayList<>()); }

    private EdgeArgs(JsonNode node, String prefix, List<ValidationError> errors) {
        this.node = node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node;
        this.prefix = prefix;
        this.errors = errors;
    }

    /** 중첩 객체(예: heatsink)를 같은 오류 목록을 공유하는 하위 파서로 연다. */
    public EdgeArgs child(String field) {
        return new EdgeArgs(node.path(field), prefix + field + ".", errors);
    }

    public boolean has(String field) {
        return node.hasNonNull(field);
    }

    public JsonNode raw() { return node; }
    public JsonNode raw(String field) { return node.path(field); }

    public List<ValidationError> errors() { return errors; }
    public boolean hasErrors() { return !errors.isEmpty(); }

    public void reject(ErrorCode code, String field, String message) {
        errors.add(new ValidationError(code, prefix + field, message));
    }

    public double dbl(String field, double def, double min, double max) {
        if (!has(field)) return def;
        JsonNode v = node.get(field);
        if (!v.isNumber()) {
            reject(ErrorCode.INVALID_ARGUMENTS, field, "숫자여야 한다(받은 값: " + v.asText() + ")");
            return def;
        }
        double d = v.asDouble();
        if (d < min || d > max) {
            reject(ErrorCode.OUT_OF_RANGE, field,
                    String.format("허용 범위 %s~%s를 벗어났다(받은 값: %s)", fmt(min), fmt(max), fmt(d)));
            return def;
        }
        return d;
    }

    public double reqDbl(String field, double min, double max) {
        if (!has(field)) {
            reject(ErrorCode.MISSING_FIELD, field, "필수 항목이다.");
            return Double.NaN;
        }
        return dbl(field, Double.NaN, min, max);
    }

    public int intVal(String field, int def, int min, int max) {
        if (!has(field)) return def;
        JsonNode v = node.get(field);
        if (!v.isNumber()) {
            reject(ErrorCode.INVALID_ARGUMENTS, field, "정수여야 한다(받은 값: " + v.asText() + ")");
            return def;
        }
        int i = v.asInt();
        if (i < min || i > max) {
            reject(ErrorCode.OUT_OF_RANGE, field, String.format("허용 범위 %d~%d를 벗어났다(받은 값: %d)", min, max, i));
            return def;
        }
        return i;
    }

    public String str(String field, String def) {
        return has(field) ? node.get(field).asText(def) : def;
    }

    public boolean bool(String field, boolean def) {
        return has(field) ? node.get(field).asBoolean(def) : def;
    }

    /** enum 파싱 — 파서가 null을 주면 INVALID_ENUM으로 거부한다. */
    public <E> E enumVal(String field, E def, Function<String, E> parser, String allowed, boolean required) {
        if (!has(field)) {
            if (required) reject(ErrorCode.MISSING_FIELD, field, "필수 항목이다. 허용 값: " + allowed);
            return def;
        }
        E parsed = parser.apply(node.get(field).asText());
        if (parsed == null) {
            reject(ErrorCode.INVALID_ENUM, field,
                    "허용되지 않은 값 '" + node.get(field).asText() + "'. 허용 값: " + allowed);
            return def;
        }
        return parsed;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
