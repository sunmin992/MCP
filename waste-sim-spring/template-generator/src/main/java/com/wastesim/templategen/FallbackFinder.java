package com.wastesim.templategen;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 필드 초기값이 {@code null}일 때, 설정 클래스가 그 자리를 <b>무엇으로 대신하는지</b> 찾는다.
 *
 * <p>찾는 꼴은 셋이다(설정 클래스의 메서드 안에서만).
 * <pre>
 * if (f == null || f.isEmpty()) return X;        // 비었으면 X
 * f == null ? X : …   /   f != null ? … : X       // 삼항식
 * if (f != null &amp;&amp; !f.isEmpty()) return f;  return X;   // 있으면 f, 아니면 X
 * </pre>
 * X 가 상수(리터럴 · enum 상수 · static final · 상수의 목록 · 인자 없는 static 메서드가 돌려주는 상수)로
 * 풀리면 기본값이 된다. 다른 필드나 매개변수로 대신하면 기본값으로 채우지 않고 그 사실만 알린다 —
 * "건물 수만큼" 같은 값은 고정된 기본값이 아니다.
 */
final class FallbackFinder {

    /**
     * @param value       풀린 기본값. 상수로 풀리지 않으면 {@code null}
     * @param description 무엇으로 대신하는지(사람이 읽는 말)
     * @param where       대신하는 자리 {@code 파일:줄 메서드()}
     */
    record Fallback(Object value, String description, String where) {
    }

    private final SourceIndex index;
    private final ConfigModel config;

    FallbackFinder(SourceIndex index, ConfigModel config) {
        this.index = index;
        this.config = config;
    }

    Optional<Fallback> find(String field) {
        for (MethodDeclaration m : config.decl.getMethods()) {
            ConfigModel.Scope s = config.scope(m);
            for (Node n : m.findAll(Node.class)) {
                Expression replacement = replacementAt(n, field, s);
                if (replacement == null) continue;
                String at = SourceIndex.where(n) + " " + m.getNameAsString() + "()";
                Object v = evaluate(replacement, config.decl, 0);
                String what = describe(replacement, s);
                return Optional.of(new Fallback(v, what, at));
            }
        }
        return Optional.empty();
    }

    /** 이 노드가 "f 가 없으면 X" 의 자리면 X. */
    private Expression replacementAt(Node n, String field, ConfigModel.Scope s) {
        if (n instanceof IfStmt ifs && ifs.getElseStmt().isEmpty()) {
            Expression ret = singleReturn(ifs.getThenStmt());
            if (ret == null) return null;
            if (isAbsent(ifs.getCondition(), field, s)) return ret;
            // if (f 가 있으면) return f;  다음 문장이 return X;
            if (isPresent(ifs.getCondition(), field, s) && refersTo(ret, field, s)
                    && ifs.getParentNode().orElse(null) instanceof BlockStmt block) {
                int i = block.getStatements().indexOf(ifs);
                if (i >= 0 && i + 1 < block.getStatements().size()) {
                    return singleReturn(block.getStatements().get(i + 1));
                }
            }
        }
        if (n instanceof ConditionalExpr ce) {
            if (isAbsent(ce.getCondition(), field, s) && refersTo(ce.getElseExpr(), field, s)) return ce.getThenExpr();
            if (isPresent(ce.getCondition(), field, s) && refersTo(ce.getThenExpr(), field, s)) return ce.getElseExpr();
        }
        return null;
    }

    private static Expression singleReturn(Statement st) {
        Statement only = st instanceof BlockStmt b && b.getStatements().size() == 1 ? b.getStatements().get(0) : st;
        return only instanceof ReturnStmt r ? r.getExpression().orElse(null) : null;
    }

    /** {@code f == null}, 또는 그것과 "비었다" 검사({@code isEmpty} · {@code isBlank} · {@code length == 0})의 "또는". */
    private boolean isAbsent(Expression cond, String field, ConfigModel.Scope s) {
        cond = ConfigModel.unwrap(cond);
        if (cond instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.OR) {
            return isAbsent(b.getLeft(), field, s) && isAbsentOrEmpty(b.getRight(), field, s)
                    || isAbsentOrEmpty(b.getLeft(), field, s) && isAbsent(b.getRight(), field, s);
        }
        return isNullCheck(cond, field, s, BinaryExpr.Operator.EQUALS);
    }

    private boolean isAbsentOrEmpty(Expression e, String field, ConfigModel.Scope s) {
        e = ConfigModel.unwrap(e);
        if (isNullCheck(e, field, s, BinaryExpr.Operator.EQUALS)) return true;
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.OR) {
            return isAbsentOrEmpty(b.getLeft(), field, s) && isAbsentOrEmpty(b.getRight(), field, s);
        }
        if (e instanceof MethodCallExpr call && call.getArguments().isEmpty()
                && (call.getNameAsString().equals("isEmpty") || call.getNameAsString().equals("isBlank"))) {
            return call.getScope().map(sc -> refersTo(sc, field, s)).orElse(false);
        }
        // f.length == 0
        return e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.EQUALS
                && b.getLeft() instanceof FieldAccessExpr fa && fa.getNameAsString().equals("length")
                && refersTo(fa.getScope(), field, s) && b.getRight().toString().equals("0");
    }

    /** {@code f != null}, 또는 그것과 "비지 않았다" 검사의 "그리고". */
    private boolean isPresent(Expression cond, String field, ConfigModel.Scope s) {
        cond = ConfigModel.unwrap(cond);
        if (cond instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.AND) {
            return isPresent(b.getLeft(), field, s) && notEmpty(b.getRight(), field, s)
                    || notEmpty(b.getLeft(), field, s) && isPresent(b.getRight(), field, s);
        }
        return isNullCheck(cond, field, s, BinaryExpr.Operator.NOT_EQUALS);
    }

    private boolean notEmpty(Expression e, String field, ConfigModel.Scope s) {
        e = ConfigModel.unwrap(e);
        return e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && isAbsentOrEmpty(u.getExpression(), field, s);
    }

    private boolean isNullCheck(Expression e, String field, ConfigModel.Scope s, BinaryExpr.Operator op) {
        if (!(ConfigModel.unwrap(e) instanceof BinaryExpr b) || b.getOperator() != op) return false;
        return b.getRight() instanceof NullLiteralExpr && refersTo(b.getLeft(), field, s)
                || b.getLeft() instanceof NullLiteralExpr && refersTo(b.getRight(), field, s);
    }

    private boolean refersTo(Expression e, String field, ConfigModel.Scope s) {
        ConfigModel.FieldRef ref = config.fieldOf(e, s);
        return ref != null && !ref.element() && ref.field().equals(field);
    }

    // ── 대체값 풀기 ────────────────────────────────────────────────────────────

    /**
     * 상수로 풀면 그 값, 아니면 {@code null}. enum 상수는 이름 문자열로, 목록은 원소를 푼 목록으로.
     * 인자 없는 static 메서드는 한 줄 {@code return} 이면 따라간다(최대 3단).
     */
    private Object evaluate(Expression e, TypeDeclaration<?> ctx, int depth) {
        e = ConfigModel.unwrap(e);
        if (e instanceof StringLiteralExpr str) return str.asString();
        if (e instanceof BooleanLiteralExpr b) return b.getValue();
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof NameExpr owner && index.isEnum(owner.getNameAsString())
                && index.enumConstants(owner.getNameAsString()).contains(fa.getNameAsString())) {
            return fa.getNameAsString();
        }
        if (e instanceof NameExpr n && ctx instanceof EnumDeclaration en
                && en.getEntries().stream().anyMatch(x -> x.getNameAsString().equals(n.getNameAsString()))) {
            return n.getNameAsString();
        }
        if (e instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            // Enum.X.name()
            if (name.equals("name") && call.getArguments().isEmpty() && call.getScope().isPresent()) {
                Object inner = evaluate(call.getScope().get(), ctx, depth);
                if (inner instanceof String) return inner;
            }
            // List.of(…) · Arrays.asList(…) · Collections.singletonList(…)
            if ((name.equals("of") || name.equals("asList") || name.equals("singletonList"))
                    && call.getScope().isPresent()) {
                List<Object> out = new ArrayList<>();
                for (Expression a : call.getArguments()) {
                    Object v = evaluate(a, ctx, depth);
                    if (v == null) return null;
                    out.add(v);
                }
                return List.copyOf(out);
            }
            // Owner.method() — 인자 없는 static 메서드의 한 줄 return
            if (call.getArguments().isEmpty() && depth < 3 && call.getScope().orElse(null) instanceof NameExpr owner) {
                TypeDeclaration<?> type = typeOf(owner.getNameAsString());
                if (type != null) {
                    for (MethodDeclaration md : type.getMethodsByName(name)) {
                        if (!md.isStatic() || !md.getParameters().isEmpty() || md.getBody().isEmpty()) continue;
                        Expression ret = singleReturn(md.getBody().get());
                        if (ret != null) return evaluate(ret, type, depth + 1);
                    }
                }
            }
            return null;
        }
        ClassOrInterfaceDeclaration cls = ctx instanceof ClassOrInterfaceDeclaration c ? c : null;
        Double n = index.evalNumber(e, cls);
        return n;
    }

    private TypeDeclaration<?> typeOf(String simpleName) {
        ClassOrInterfaceDeclaration cls = index.findClass(simpleName);
        if (cls != null) return cls;
        return index.findEnum(simpleName);
    }

    /** 무엇으로 대신하는지. 다른 필드 · 매개변수면 그렇다고 적는다. */
    private String describe(Expression e, ConfigModel.Scope s) {
        Expression bare = ConfigModel.unwrap(e);
        for (Expression x : bare.findAll(Expression.class)) {
            ConfigModel.FieldRef ref = config.fieldOf(x, s);
            if (ref != null) return "다른 필드 " + ref.field() + " 의 값으로 대신함 — " + bare;
        }
        Optional<MethodDeclaration> m = e.findAncestor(MethodDeclaration.class);
        if (bare instanceof NameExpr n && m.isPresent()
                && m.get().getParameters().stream().anyMatch(p -> p.getNameAsString().equals(n.getNameAsString()))) {
            return "호출하는 쪽이 주는 매개변수 " + n + " 로 대신함";
        }
        return bare.toString() + " 로 대신함";
    }
}
