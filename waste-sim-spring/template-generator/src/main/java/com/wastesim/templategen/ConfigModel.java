package com.wastesim.templategen;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 설정 클래스와 "이 식이 어느 설정 필드를 읽는가" 의 판정.
 *
 * <p>판정은 이름이 아니라 코드를 따른다. 게터는 본문({@code return numTrucks;})을, 해석 메서드는
 * {@code return Enum.fromName(필드);} 를 따라 필드를 찾고, 게터는 <b>설정 타입 변수</b>에서
 * 부를 때만 인정한다 — 이름만 같은 다른 객체의 게터({@code w.getThreshold()})를 섞지 않기 위해서다.
 */
final class ConfigModel {

    record FieldInfo(String name, Type type, Expression initializer, String javadoc, String location) {
        String typeName() {
            return type.asString();
        }
    }

    /** 이 필드를 가리키는 식. {@code element}면 목록 필드의 원소(foreach 변수)다. */
    record FieldRef(String field, boolean element) {
    }

    final ClassOrInterfaceDeclaration decl;
    final Map<String, FieldInfo> fields;
    /** 메서드 이름 → 그 메서드가 돌려주는 필드(게터 · 별칭 게터 · enum 해석 메서드). */
    final Map<String, String> accessors;
    private final SourceIndex index;

    ConfigModel(SourceIndex index, String configClass) {
        this.index = index;
        this.decl = index.classOf(configClass);
        this.fields = configurableFields();
        this.accessors = accessors();
    }

    String simpleName() {
        return decl.getNameAsString();
    }

    /** 세터가 있는 인스턴스 필드만 — 세터가 없으면 바깥에서 정할 수 없는 내부 상태다. */
    private Map<String, FieldInfo> configurableFields() {
        Map<String, FieldInfo> out = new LinkedHashMap<>();
        for (FieldDeclaration fd : decl.getFields()) {
            if (fd.isStatic()) continue;
            String javadoc = fd.getJavadoc()
                    .map(j -> j.getDescription().toText().replaceAll("\\s+", " ").trim())
                    .orElse(null);
            for (VariableDeclarator v : fd.getVariables()) {
                String name = v.getNameAsString();
                String setter = "set" + capitalize(name);
                boolean hasSetter = decl.getMethodsByName(setter).stream()
                        .anyMatch(m -> m.getParameters().size() == 1);
                if (!hasSetter) continue;
                out.put(name, new FieldInfo(name, v.getType(), v.getInitializer().orElse(null),
                        javadoc, SourceIndex.where(v)));
            }
        }
        return out;
    }

    /**
     * 본문이 한 줄 {@code return}인 인자 없는 메서드를 따라간다: {@code return 필드;},
     * {@code return 다른게터();}, {@code return Enum.fromName(필드);}.
     */
    private Map<String, String> accessors() {
        Map<String, Expression> returns = new HashMap<>();
        for (MethodDeclaration m : decl.getMethods()) {
            if (m.isStatic() || !m.getParameters().isEmpty() || m.getBody().isEmpty()) continue;
            List<Statement> body = m.getBody().get().getStatements();
            if (body.size() == 1 && body.get(0) instanceof ReturnStmt r && r.getExpression().isPresent()) {
                returns.put(m.getNameAsString(), r.getExpression().get());
            }
        }
        Map<String, String> out = new HashMap<>();
        for (String name : returns.keySet()) {
            Expression e = returns.get(name);
            for (int hop = 0; hop < 5 && e != null; hop++) {
                String direct = directField(e);
                if (direct != null) {
                    out.put(name, direct);
                    break;
                }
                if (e instanceof MethodCallExpr c && c.getArguments().size() == 1
                        && (c.getNameAsString().equals("fromName") || c.getNameAsString().equals("valueOf"))
                        && c.getScope().orElse(null) instanceof NameExpr owner && index.isEnum(owner.getNameAsString())) {
                    String f = directField(c.getArgument(0));
                    if (f != null) out.put(name, f);
                    break;
                }
                e = e instanceof MethodCallExpr c && c.getArguments().isEmpty()
                        && (c.getScope().isEmpty() || c.getScope().get() instanceof ThisExpr)
                        ? returns.get(c.getNameAsString()) : null;
            }
        }
        return out;
    }

    private String directField(Expression e) {
        if (e instanceof NameExpr ne && fields.containsKey(ne.getNameAsString())) return ne.getNameAsString();
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr
                && fields.containsKey(fa.getNameAsString())) {
            return fa.getNameAsString();
        }
        return null;
    }

    // ── 메서드 안의 문맥 ───────────────────────────────────────────────────────

    /** 메서드 하나를 읽을 때의 문맥: 설정 객체를 담은 변수, 필드 값을 받은 지역 변수. */
    final class Scope {
        final boolean insideConfig;
        final Set<String> configVars = new HashSet<>();
        final Map<String, FieldRef> locals = new HashMap<>();
        /** {@code x = 조건 ? 값 : null} 로 받은 지역 변수 → 그 삼항식. {@code x != null}이 조건과 같다. */
        final Map<String, ConditionalExpr> nullTernaries = new HashMap<>();
        /** 지역 변수의 정의 자리 — 정의 자체는 읽기로 세지 않고 쓰이는 자리를 센다. */
        final Set<Node> localDefinitions = new HashSet<>();
        /** 매개변수 · 지역 변수 이름 → 선언 타입. */
        final Map<String, String> varTypes = new HashMap<>();

        Scope(MethodDeclaration m) {
            this.insideConfig = m.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(c -> c == decl).orElse(false);
            String configName = simpleName();
            m.getParameters().forEach(p -> varTypes.put(p.getNameAsString(), p.getType().asString()));
            m.findAll(VariableDeclarator.class).forEach(v -> varTypes.put(v.getNameAsString(), v.getType().asString()));
            m.getParameters().forEach(p -> {
                if (p.getType().asString().equals(configName)) configVars.add(p.getNameAsString());
            });
            m.findAll(VariableDeclarator.class).forEach(v -> {
                if (v.getType().asString().equals(configName)) configVars.add(v.getNameAsString());
            });
            m.findAll(VariableDeclarator.class).forEach(v -> v.getInitializer().ifPresent(init -> {
                FieldRef ref = fieldOf(init, this);
                if (ref != null) {
                    locals.put(v.getNameAsString(), ref);
                    localDefinitions.add(init);
                }
                Expression bare = unwrap(init);
                if (bare instanceof ConditionalExpr ce
                        && (ce.getThenExpr().isNullLiteralExpr() || ce.getElseExpr().isNullLiteralExpr())) {
                    nullTernaries.put(v.getNameAsString(), ce);
                }
            }));
            m.findAll(ForEachStmt.class).forEach(fe -> {
                FieldRef ref = fieldOf(fe.getIterable(), this);
                if (ref != null && !ref.element()) {
                    locals.put(fe.getVariableDeclarator().getNameAsString(), new FieldRef(ref.field(), true));
                }
            });
        }
    }

    Scope scope(MethodDeclaration m) {
        return new Scope(m);
    }

    /** 이 식이 설정 필드를 읽는가. 아니면 {@code null}. */
    FieldRef fieldOf(Expression e, Scope s) {
        if (e instanceof EnclosedExpr en) return fieldOf(en.getInner(), s);
        if (e instanceof CastExpr c) return fieldOf(c.getExpression(), s);
        if (e instanceof MethodCallExpr call && call.getArguments().isEmpty()) {
            if (!onConfig(call, s)) return null;
            String n = call.getNameAsString();
            String viaBody = accessors.get(n);
            if (viaBody != null) return new FieldRef(viaBody, false);
            String bare = n.startsWith("get") ? n.substring(3) : n.startsWith("is") ? n.substring(2) : null;
            if (bare != null && !bare.isEmpty() && fields.containsKey(decapitalize(bare))) {
                return new FieldRef(decapitalize(bare), false);
            }
            return null;
        }
        if (e instanceof NameExpr ne) {
            String n = ne.getNameAsString();
            if (s.locals.containsKey(n)) return s.locals.get(n);
            if (s.insideConfig && fields.containsKey(n)) return new FieldRef(n, false);
            return null;
        }
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr && s.insideConfig
                && fields.containsKey(fa.getNameAsString())) {
            return new FieldRef(fa.getNameAsString(), false);
        }
        return null;
    }

    /** {@code cfg.hasX()} 꼴이면 X 필드. "값이 들어 있는가" 를 묻는 관례다. */
    String presenceField(MethodCallExpr call, Scope s) {
        String n = call.getNameAsString();
        if (!call.getArguments().isEmpty() || !n.startsWith("has") || n.length() <= 3 || !onConfig(call, s)) {
            return null;
        }
        String f = decapitalize(n.substring(3));
        return fields.containsKey(f) ? f : null;
    }

    /** 설정 객체에서 부른 호출인가: 설정 변수 · this · 설정 클래스 안의 맨 호출. */
    boolean onConfig(MethodCallExpr call, Scope s) {
        Expression scope = call.getScope().orElse(null);
        if (scope == null) return s.insideConfig;
        if (scope instanceof ThisExpr) return s.insideConfig;
        return scope instanceof NameExpr sn && s.configVars.contains(sn.getNameAsString());
    }

    static Expression unwrap(Expression e) {
        while (e instanceof EnclosedExpr en) e = en.getInner();
        return e;
    }

    static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
