package com.wastesim.templategen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 시뮬레이터 소스 전체의 색인. 클래스 · enum 상수 · static final 상수를 찾고, 노드의 자리를
 * {@code 파일:줄}로 적는다. 소스만 읽는다 — 시뮬레이터를 컴파일하거나 실행하지 않는다.
 */
final class SourceIndex {

    private final Map<String, CompilationUnit> unitsBySimpleName = new HashMap<>();
    private final Map<String, List<String>> enumConstants = new HashMap<>();
    private final Map<String, EnumDeclaration> enums = new HashMap<>();

    SourceIndex(Path sourceRoot) throws IOException {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(cfg);
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                ParseResult<CompilationUnit> r = parser.parse(p);
                if (!r.isSuccessful() || r.getResult().isEmpty()) {
                    throw new IllegalStateException("파싱 실패: " + p + " " + r.getProblems());
                }
                CompilationUnit cu = r.getResult().get();
                for (TypeDeclaration<?> t : cu.getTypes()) unitsBySimpleName.put(t.getNameAsString(), cu);
                for (EnumDeclaration e : cu.findAll(EnumDeclaration.class)) {
                    List<String> names = new ArrayList<>();
                    e.getEntries().forEach(en -> names.add(en.getNameAsString()));
                    enumConstants.put(e.getNameAsString(), names);
                    enums.put(e.getNameAsString(), e);
                }
            }
        }
    }

    boolean isEnum(String simpleName) {
        return enumConstants.containsKey(simpleName);
    }

    List<String> enumConstants(String simpleName) {
        return enumConstants.get(simpleName);
    }

    EnumDeclaration findEnum(String simpleName) {
        return enums.get(simpleName);
    }

    ClassOrInterfaceDeclaration classOf(String name) {
        ClassOrInterfaceDeclaration cls = findClass(name);
        if (cls == null) throw new IllegalArgumentException("소스에서 클래스를 찾지 못했습니다: " + name);
        return cls;
    }

    /** 단순 이름 또는 FQCN 으로 클래스를 찾는다. enum · 인터페이스 · 없는 이름이면 {@code null}. */
    ClassOrInterfaceDeclaration findClass(String name) {
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        CompilationUnit cu = unitsBySimpleName.get(simple);
        return cu == null ? null : cu.getClassByName(simple).orElse(null);
    }

    /** {@code 파일:줄}. */
    static String where(Node n) {
        String file = n.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(s -> s.getFileName())
                .orElse("?");
        return file + ":" + n.getBegin().map(pos -> pos.line).orElse(0);
    }

    /** 리터럴 · 사칙연산 · 같은 클래스(또는 {@code 클래스.상수})의 static final 상수만 푼다. */
    Double evalNumber(Expression e, ClassOrInterfaceDeclaration context) {
        if (e instanceof EnclosedExpr en) return evalNumber(en.getInner(), context);
        if (e instanceof CastExpr c) return evalNumber(c.getExpression(), context);
        if (e instanceof IntegerLiteralExpr i) return i.asNumber().doubleValue();
        if (e instanceof LongLiteralExpr l) return l.asNumber().doubleValue();
        if (e instanceof DoubleLiteralExpr d) return d.asDouble();
        if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
            Double v = evalNumber(u.getExpression(), context);
            return v == null ? null : -v;
        }
        if (e instanceof BinaryExpr b) {
            Double l = evalNumber(b.getLeft(), context);
            Double r = evalNumber(b.getRight(), context);
            if (l == null || r == null) return null;
            return switch (b.getOperator()) {
                case PLUS -> l + r;
                case MINUS -> l - r;
                case MULTIPLY -> l * r;
                case DIVIDE -> r == 0 ? null : l / r;
                default -> null;
            };
        }
        if (e instanceof NameExpr n) return constant(context, n.getNameAsString());
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof NameExpr owner) {
            ClassOrInterfaceDeclaration cls = findClass(owner.getNameAsString());
            if (cls != null) return constant(cls, fa.getNameAsString());
        }
        return null;
    }

    private Double constant(ClassOrInterfaceDeclaration cls, String name) {
        if (cls == null) return null;
        for (FieldDeclaration fd : cls.getFields()) {
            if (!fd.isStatic() || !fd.isFinal()) continue;
            for (VariableDeclarator v : fd.getVariables()) {
                if (v.getNameAsString().equals(name) && v.getInitializer().isPresent()) {
                    return evalNumber(v.getInitializer().get(), cls);
                }
            }
        }
        return null;
    }
}
