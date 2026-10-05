package com.wastesim.templategen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 조건식 둘을 진리표로 맞댄다.
 *
 * <p>필드마다 시험할 값을 정한다: 불리언은 참/거짓, enum 은 상수 전부와 null, 목록은 null/빈/채움,
 * 수는 조건에 나온 경계값의 앞뒤. 이 격자 위에서 둘이 모두 같으면 같은 조건으로 본다.
 */
final class Truth {

    private Truth() {
    }

    /**
     * 필드의 값 종류 · 선택지 · 범위. 생성한 템플릿에서 가져온다. 범위가 있으면 진리표에 범위 안의
     * 값만 넣는다 — 검증기가 거절하는 설정(건물 0동)에서 갈리는 조건은 생성 조건이 아니다.
     */
    record Kind(String valueType, List<String> allowed, Double min, Double max,
                boolean minExclusive, boolean maxExclusive) {

        Kind(String valueType, List<String> allowed) {
            this(valueType, allowed, null, null, false, false);
        }

        boolean inRange(double v) {
            if (min != null && (minExclusive ? v <= min : v < min)) return false;
            return max == null || (maxExclusive ? v < max : v <= max);
        }
    }

    /** 손으로 쓴 조건(H)에 대한 생성한 조건(G)의 관계. */
    enum Relation {
        /** 같다. */
        EQUIVALENT,
        /** G 가 더 넓다 — H 가 참이면 G 도 참. 묻지 않아도 될 때 더 묻는다. */
        WEAKER,
        /** G 가 더 좁다 — G 가 참이면 H 도 참. 물어야 할 때 안 물을 수 있다. */
        STRONGER,
        /** 어느 쪽도 포함하지 않는다. */
        DIFFERENT
    }

    record Comparison(Relation relation, String counterexample) {
    }

    static Comparison compare(Cond hand, Cond gen, Map<String, Kind> kinds) {
        boolean handImpliesGen = true;
        boolean genImpliesHand = true;
        String example = null;
        for (Map<String, Object> a : assignments(List.of(hand, gen), kinds)) {
            boolean h = Cond.mayHold(hand, a);
            boolean g = Cond.mayHold(gen, a);
            if (h && !g) handImpliesGen = false;
            if (g && !h) genImpliesHand = false;
            if (h != g && example == null) example = a + " → 손 " + h + " · 생성 " + g;
        }
        Relation r = handImpliesGen && genImpliesHand ? Relation.EQUIVALENT
                : handImpliesGen ? Relation.WEAKER
                : genImpliesHand ? Relation.STRONGER
                : Relation.DIFFERENT;
        return new Comparison(r, example);
    }

    /** 어떤 설정에서든 참일 수 있는가 — 그러면 생성 조건은 ALWAYS 다. */
    static boolean alwaysMay(Cond c, Map<String, Kind> kinds) {
        for (Map<String, Object> a : assignments(List.of(c), kinds)) {
            if (!Cond.mayHold(c, a)) return false;
        }
        return true;
    }

    static List<Map<String, Object>> assignments(List<Cond> conds, Map<String, Kind> kinds) {
        Set<String> fields = new LinkedHashSet<>();
        Map<String, Set<Object>> constants = new LinkedHashMap<>();
        Set<String> presenceFields = new LinkedHashSet<>();
        for (Cond c : conds) {
            Cond.collect(c, fields, new LinkedHashSet<>(), constants);
            presence(c, presenceFields);
        }
        List<String> order = new ArrayList<>(fields);
        List<List<Object>> domains = new ArrayList<>();
        for (String f : order) domains.add(domain(f, kinds.get(f), constants.getOrDefault(f, Set.of()),
                presenceFields.contains(f)));

        List<Map<String, Object>> out = new ArrayList<>();
        product(order, domains, 0, new LinkedHashMap<>(), out);
        return out;
    }

    private static void product(List<String> order, List<List<Object>> domains, int i,
                                Map<String, Object> cur, List<Map<String, Object>> out) {
        if (out.size() > 20_000) return;
        if (i == order.size()) {
            out.add(new LinkedHashMap<>(cur));
            return;
        }
        for (Object v : domains.get(i)) {
            cur.put(order.get(i), v);
            product(order, domains, i + 1, cur, out);
        }
        cur.remove(order.get(i));
    }

    private static List<Object> domain(String field, Kind kind, Set<Object> constants, boolean presence) {
        Set<Object> d = new LinkedHashSet<>();
        String vt = kind == null ? "" : kind.valueType();
        switch (vt) {
            case "BOOLEAN" -> {
                d.add(true);
                d.add(false);
            }
            case "INTEGER_LIST", "ENUM_LIST", "STRING_LIST" -> {
                d.add(Cond.Mark.NULL);
                d.add(Cond.Mark.EMPTY);
                d.add(Cond.Mark.NONEMPTY);
            }
            case "ENUM", "STRING" -> {
                d.addAll(kind.allowed());
                for (Object c : constants) if (c instanceof String) d.add(c);
                d.add(Cond.Mark.NULL);
            }
            default -> {
                d.add(0.0);
                d.add(1.0);
                for (Object c : constants) {
                    if (c instanceof Number n) {
                        d.add(n.doubleValue() - 1);
                        d.add(n.doubleValue());
                        d.add(n.doubleValue() + 1);
                    } else {
                        d.add(c);
                    }
                }
                if (presence || kind == null) d.add(Cond.Mark.NULL);
                if (kind != null) d.removeIf(v -> v instanceof Double x && !kind.inRange(x));
                if (kind != null && kind.min() != null && d.stream().noneMatch(v -> v instanceof Double)) {
                    d.add(kind.min() + (kind.minExclusive() ? 1 : 0));
                }
            }
        }
        return new ArrayList<>(d);
    }

    private static void presence(Cond c, Set<String> out) {
        switch (c) {
            case Cond.Atom a -> {
                if (a.op().equals("present") || a.op().equals("empty")) out.add(a.field());
            }
            case Cond.And a -> a.parts().forEach(p -> presence(p, out));
            case Cond.Or o -> o.parts().forEach(p -> presence(p, out));
            case Cond.Not n -> presence(n.inner(), out);
            default -> { }
        }
    }
}
