package com.wastesim.templategen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 설정 필드에 대한 조건식. 생성 조건을 코드에서 뽑은 결과와 손으로 쓴 조건을 같은 꼴로 놓고
 * 진리표로 맞대기 위한 것이다.
 *
 * <p>원자는 둘이다. {@link Atom}은 설정 필드에 대한 비교, {@link Runtime}은 설정만으로는
 * 정해지지 않는 조건(실행 중의 경로 · 날짜 등)이다. 실행 조건은 <b>"그럴 수 있다"</b>로 읽는다 —
 * 어떤 설정에서 그 필드가 쓰일 <i>가능성</i>이 있는지가 생성 조건의 질문이기 때문이다.
 */
sealed interface Cond {

    Cond TRUE = new Const(true);
    Cond FALSE = new Const(false);

    record Const(boolean value) implements Cond {
        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    record And(List<Cond> parts) implements Cond {
        @Override
        public String toString() {
            return join(parts, " && ");
        }
    }

    record Or(List<Cond> parts) implements Cond {
        @Override
        public String toString() {
            return join(parts, " || ");
        }
    }

    record Not(Cond inner) implements Cond {
        @Override
        public String toString() {
            boolean bare = inner instanceof Atom a && (a.op().equals("present") || a.op().equals("empty"))
                    || inner instanceof Runtime || inner instanceof Const;
            return "!" + (bare ? inner.toString() : "(" + inner + ")");
        }
    }

    /**
     * 필드 비교. {@code op}는 {@code present}(null 이 아님) · {@code empty}(빈 목록) ·
     * {@code == != < <= > >=}. 값은 Boolean · String(enum 상수) · Double.
     */
    record Atom(String field, String op, Object value) implements Cond {
        @Override
        public String toString() {
            return switch (op) {
                case "present", "empty" -> op + "(" + field + ")";
                default -> field + " " + op + " " + show(value);
            };
        }
    }

    /** 설정만으로 정해지지 않는 조건. 문장은 코드의 식이나 그 뜻이다. */
    record Runtime(String text) implements Cond {
        @Override
        public String toString() {
            return "?{" + text + "}";
        }
    }

    // ── 만들기 ────────────────────────────────────────────────────────────────

    static Cond and(List<Cond> parts) {
        List<Cond> flat = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Cond p : parts) {
            if (p.equals(TRUE)) continue;
            if (p.equals(FALSE)) return FALSE;
            for (Cond q : p instanceof And a ? a.parts() : List.of(p)) {
                if (seen.add(q.toString())) flat.add(q);
            }
        }
        if (flat.isEmpty()) return TRUE;
        return flat.size() == 1 ? flat.get(0) : new And(List.copyOf(flat));
    }

    static Cond or(List<Cond> parts) {
        List<Cond> flat = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Cond p : parts) {
            if (p.equals(FALSE)) continue;
            if (p.equals(TRUE)) return TRUE;
            for (Cond q : p instanceof Or o ? o.parts() : List.of(p)) {
                if (seen.add(q.toString())) flat.add(q);
            }
        }
        if (flat.isEmpty()) return FALSE;
        return flat.size() == 1 ? flat.get(0) : new Or(List.copyOf(flat));
    }

    static Cond not(Cond c) {
        if (c.equals(TRUE)) return FALSE;
        if (c.equals(FALSE)) return TRUE;
        if (c instanceof Not n) return n.inner();
        return new Not(c);
    }

    /** {@code given(x)} = 값이 있고 빈 목록이 아니다. */
    static Cond given(String field) {
        return and(List.of(new Atom(field, "present", null), not(new Atom(field, "empty", null))));
    }

    /** 이 필드에 대한 원자를 실행 조건으로 바꾼다. 필드 자신의 존재 확인은 생성 조건이 아니다. */
    static Cond selfAsRuntime(Cond c, String field) {
        return switch (c) {
            case Atom a when a.field().equals(field) -> new Runtime("자기 자신: " + a);
            case And a -> and(a.parts().stream().map(p -> selfAsRuntime(p, field)).toList());
            case Or o -> or(o.parts().stream().map(p -> selfAsRuntime(p, field)).toList());
            case Not n -> not(selfAsRuntime(n.inner(), field));
            default -> c;
        };
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────────

    static void collect(Cond c, Set<String> fields, Set<String> runtimes, Map<String, Set<Object>> constants) {
        switch (c) {
            case Atom a -> {
                fields.add(a.field());
                if (a.value() != null) constants.computeIfAbsent(a.field(), k -> new LinkedHashSet<>()).add(a.value());
            }
            case Runtime r -> runtimes.add(r.text());
            case And a -> a.parts().forEach(p -> collect(p, fields, runtimes, constants));
            case Or o -> o.parts().forEach(p -> collect(p, fields, runtimes, constants));
            case Not n -> collect(n.inner(), fields, runtimes, constants);
            case Const k -> { }
        }
    }

    /** 값 표지 — 목록 · 널 허용 필드의 상태. */
    enum Mark { NULL, EMPTY, NONEMPTY }

    static boolean eval(Cond c, Map<String, Object> config, Map<String, Boolean> runtime) {
        return switch (c) {
            case Const k -> k.value();
            case And a -> a.parts().stream().allMatch(p -> eval(p, config, runtime));
            case Or o -> o.parts().stream().anyMatch(p -> eval(p, config, runtime));
            case Not n -> !eval(n.inner(), config, runtime);
            case Runtime r -> runtime.getOrDefault(r.text(), true);
            case Atom a -> evalAtom(a, config.getOrDefault(a.field(), Mark.NULL));
        };
    }

    private static boolean evalAtom(Atom a, Object v) {
        boolean isNull = v == Mark.NULL;
        return switch (a.op()) {
            case "present" -> !isNull;
            case "empty" -> v == Mark.EMPTY;
            case "==" -> !isNull && same(v, a.value());
            case "!=" -> isNull || !same(v, a.value());
            default -> {
                if (!(v instanceof Double d) || !(a.value() instanceof Number k)) yield false;
                double x = k.doubleValue();
                yield switch (a.op()) {
                    case "<" -> d < x;
                    case "<=" -> d <= x;
                    case ">" -> d > x;
                    case ">=" -> d >= x;
                    default -> false;
                };
            }
        };
    }

    private static boolean same(Object v, Object k) {
        if (v instanceof Double d && k instanceof Number n) return d == n.doubleValue();
        return Objects.equals(v, k);
    }

    /**
     * 실행 조건을 "그럴 수 있다" 로 읽어 평가한다 — 실행 조건 값의 어떤 조합에서든 참이 되면 참.
     * 실행 조건이 많으면(12개 넘게) 조합을 다 보지 않고 참으로 둔다.
     */
    static boolean mayHold(Cond c, Map<String, Object> config) {
        Set<String> fields = new LinkedHashSet<>();
        Set<String> runtimes = new LinkedHashSet<>();
        collect(c, fields, runtimes, new LinkedHashMap<>());
        List<String> rs = new ArrayList<>(runtimes);
        if (rs.size() > 12) return true;
        for (int mask = 0; mask < (1 << rs.size()); mask++) {
            Map<String, Boolean> r = new LinkedHashMap<>();
            for (int i = 0; i < rs.size(); i++) r.put(rs.get(i), (mask & (1 << i)) != 0);
            if (eval(c, config, r)) return true;
        }
        return false;
    }

    // ── 쓰기 · 파싱 ────────────────────────────────────────────────────────────

    private static String join(List<Cond> parts, String sep) {
        List<String> s = new ArrayList<>();
        for (Cond p : parts) s.add(wrap(p));
        return String.join(sep, s);
    }

    private static String wrap(Cond c) {
        return c instanceof And || c instanceof Or ? "(" + c + ")" : c.toString();
    }

    private static String show(Object v) {
        if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(v);
    }

    /**
     * 손으로 쓴 조건을 읽는다. 문법: {@code ||} · {@code &&} · {@code !} · 괄호 · {@code true}/{@code false} ·
     * {@code given(f)} · {@code present(f)} · {@code empty(f)} · {@code f 연산자 값}.
     * 값은 수 · true/false · enum 상수 이름.
     */
    static Cond parse(String text) {
        return new Parser(text).parseAll();
    }

    final class Parser {
        private final List<String> tokens = new ArrayList<>();
        private int pos;

        Parser(String text) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\s*(\\|\\||&&|==|!=|<=|>=|[<>!()]|-?[0-9]+(?:\\.[0-9]+)?|[A-Za-z_][A-Za-z_0-9]*)")
                    .matcher(text);
            int at = 0;
            while (at < text.length() && m.find(at) && m.start() == at) {
                tokens.add(m.group(1));
                at = m.end();
            }
            if (!text.substring(at).isBlank()) throw new IllegalArgumentException("읽을 수 없는 조건: " + text);
        }

        Cond parseAll() {
            Cond c = or();
            if (pos != tokens.size()) throw new IllegalArgumentException("남은 토큰: " + tokens.subList(pos, tokens.size()));
            return c;
        }

        private Cond or() {
            List<Cond> parts = new ArrayList<>(List.of(and()));
            while (peek("||")) {
                pos++;
                parts.add(and());
            }
            return Cond.or(parts);
        }

        private Cond and() {
            List<Cond> parts = new ArrayList<>(List.of(unary()));
            while (peek("&&")) {
                pos++;
                parts.add(unary());
            }
            return Cond.and(parts);
        }

        private Cond unary() {
            if (peek("!")) {
                pos++;
                return Cond.not(unary());
            }
            if (peek("(")) {
                pos++;
                Cond c = or();
                expect(")");
                return c;
            }
            String t = next();
            if (t.equals("true")) return TRUE;
            if (t.equals("false")) return FALSE;
            if (t.equals("given") || t.equals("present") || t.equals("empty")) {
                expect("(");
                String f = next();
                expect(")");
                return t.equals("given") ? given(f) : new Atom(f, t, null);
            }
            String op = next();
            if (!List.of("==", "!=", "<", "<=", ">", ">=").contains(op)) {
                throw new IllegalArgumentException("연산자가 아님: " + op);
            }
            String v = next();
            Object value = v.equals("true") || v.equals("false") ? (Object) Boolean.valueOf(v)
                    : v.matches("-?[0-9]+(\\.[0-9]+)?") ? (Object) Double.valueOf(v) : v;
            return new Atom(t, op, value);
        }

        private boolean peek(String s) {
            return pos < tokens.size() && tokens.get(pos).equals(s);
        }

        private String next() {
            if (pos >= tokens.size()) throw new IllegalArgumentException("조건이 끝났습니다");
            return tokens.get(pos++);
        }

        private void expect(String s) {
            if (!next().equals(s)) throw new IllegalArgumentException(s + " 가 필요합니다");
        }
    }
}
