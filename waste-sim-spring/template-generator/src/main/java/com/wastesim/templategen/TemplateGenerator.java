package com.wastesim.templategen;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시뮬레이터 소스 코드에서 서브태스크 템플릿 초안을 뽑는다.
 *
 * <p>읽는 것은 넷이다.
 * <ul>
 *   <li><b>설정 클래스</b> — 세터가 있는 필드가 사용자가 정할 수 있는 후보다. 타입이 값 종류를,
 *       초기값이 기본값을, Javadoc 이 질문 초안을 준다.</li>
 *   <li><b>검증기</b> — 오류를 내는 {@code if}의 비교식이 범위를 준다. {@code days < 1 || days > 365}
 *       면 1~365 다. 검증기가 범위의 정본이므로 범위를 다른 데서 추측하지 않는다.</li>
 *   <li><b>enum</b> — 문자열 필드가 {@code X.fromName(...)}/{@code X.valueOf(...)}로 해석되면
 *       그 enum 의 상수가 선택지다.</li>
 *   <li><b>엔진</b>(선택) — 필드를 읽는 자리를 감싸는 조건이 생성 조건을 준다({@link ConditionExtractor}).</li>
 * </ul>
 *
 * <p><b>추측하지 않는다.</b> 상수로 풀리지 않는 상한(예: 차종에 달린 용량)은 비워 두고
 * {@code needsReview}에 식을 적는다. 그럴듯한 값을 채우면 제공자가 틀린 초안을 믿게 된다.
 */
public final class TemplateGenerator {

    private final SourceIndex index;
    private ConfigModel config;

    /** @param sourceRoot 시뮬레이터 소스 루트(예: {@code simulator/src/main/java}) */
    public TemplateGenerator(Path sourceRoot) throws IOException {
        this.index = new SourceIndex(sourceRoot);
    }

    /** 생성 결과. 템플릿으로 만들지 못한 필드는 이유와 함께 따로 둔다. */
    public record Result(List<GeneratedTemplate> templates, Map<String, String> skipped) {

        /** 필드 → 값 종류 · 선택지. 조건식을 진리표로 볼 때 쓴다. */
        public Map<String, Truth.Kind> kinds() {
            Map<String, Truth.Kind> out = new LinkedHashMap<>();
            templates.forEach(t -> out.put(t.configField(), new Truth.Kind(t.valueType(), t.allowed(),
                    t.min(), t.max(), t.minExclusive(), t.maxExclusive())));
            return out;
        }
    }

    /** 생성 조건 없이 — 모든 템플릿을 ALWAYS 로 둔다. */
    public Result generate(String configClass, List<String> validatorClasses) {
        return generate(configClass, validatorClasses, List.of());
    }

    /**
     * @param configClass      설정 클래스의 단순 이름 또는 FQCN
     * @param validatorClasses 범위를 검증하는 클래스들
     * @param modelClasses     엔진 쪽 클래스들. 비어 있으면 생성 조건을 뽑지 않는다. 첫 클래스의
     *                         public 메서드가 진입점이다
     */
    public Result generate(String configClass, List<String> validatorClasses, List<String> modelClasses) {
        this.config = new ConfigModel(index, configClass);

        Map<String, Draft> drafts = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        for (ConfigModel.FieldInfo f : config.fields.values()) {
            Draft d = new Draft(f);
            if (d.kind == null) {
                skipped.put(f.name(), "템플릿 값 종류로 옮길 수 없는 타입: " + f.type());
            } else {
                drafts.put(f.name(), d);
            }
        }

        // 설정 클래스 자신(resolveX 메서드)과 검증기에서 enum 해석과 범위를 찾는다.
        List<ClassOrInterfaceDeclaration> scanned = new ArrayList<>();
        scanned.add(config.decl);
        for (String v : validatorClasses) scanned.add(index.classOf(v));
        for (ClassOrInterfaceDeclaration cls : scanned) {
            for (MethodDeclaration m : cls.getMethods()) {
                ConfigModel.Scope s = config.scope(m);
                linkEnums(m, s, drafts);
                if (cls != config.decl) collectBounds(cls, m, s, drafts);
            }
        }

        Map<String, ConditionExtractor.Extracted> conditions = modelClasses.isEmpty() ? Map.of()
                : new ConditionExtractor(index, config, modelClasses).extract();
        Map<String, Truth.Kind> kinds = new LinkedHashMap<>();
        drafts.forEach((name, d) -> kinds.put(name,
                new Truth.Kind(d.valueType(), d.allowed, d.min, d.max, d.minExclusive, d.maxExclusive)));

        List<GeneratedTemplate> out = new ArrayList<>();
        for (Draft d : drafts.values()) out.add(d.build(conditions.get(d.f.name()), kinds, !modelClasses.isEmpty()));
        return new Result(out, skipped);
    }

    // ── enum 선택지 ────────────────────────────────────────────────────────────

    /** {@code Enum.fromName(필드)} / {@code Enum.valueOf(필드)} 를 찾아 필드에 선택지를 단다. */
    private void linkEnums(MethodDeclaration m, ConfigModel.Scope s, Map<String, Draft> drafts) {
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            String n = call.getNameAsString();
            if (!(n.equals("fromName") || n.equals("valueOf")) || call.getArguments().size() != 1) continue;
            Optional<Expression> scope = call.getScope();
            if (scope.isEmpty() || !(scope.get() instanceof NameExpr se)) continue;
            String enumName = se.getNameAsString();
            if (!index.isEnum(enumName)) continue;
            ConfigModel.FieldRef ref = config.fieldOf(call.getArgument(0), s);
            if (ref == null) continue;
            Draft d = drafts.get(ref.field());
            if (d == null) continue;
            d.linkEnum(enumName, index.enumConstants(enumName), SourceIndex.where(call) + " " + call);
        }
    }

    // ── 범위 ───────────────────────────────────────────────────────────────────

    /**
     * 오류를 내는 {@code if}의 조건에서 "필드 비교 상수"를 찾는다. 조건이 참이면 거절이므로
     * {@code x < 1}은 하한 1, {@code x > 365}는 상한 365, {@code x <= 0}은 "0 초과"다.
     */
    private void collectBounds(ClassOrInterfaceDeclaration cls, MethodDeclaration m, ConfigModel.Scope s,
                               Map<String, Draft> drafts) {
        for (IfStmt ifs : m.findAll(IfStmt.class)) {
            if (!rejects(ifs.getThenStmt())) continue;
            List<BinaryExpr> comparisons = new ArrayList<>();
            comparisonsIn(ifs.getCondition(), comparisons);
            for (BinaryExpr b : comparisons) {
                ConfigModel.FieldRef left = config.fieldOf(b.getLeft(), s);
                ConfigModel.FieldRef right = config.fieldOf(b.getRight(), s);
                if ((left == null) == (right == null)) continue;
                ConfigModel.FieldRef ref = left != null ? left : right;
                Expression other = left != null ? b.getRight() : b.getLeft();
                BinaryExpr.Operator op = left != null ? b.getOperator() : flip(b.getOperator());
                Draft d = drafts.get(ref.field());
                if (d == null) continue;
                String at = SourceIndex.where(b) + " " + b;
                Double k = index.evalNumber(other, cls);
                if (k == null) {
                    d.review("범위: 상수로 풀리지 않는 경계 — " + at);
                    continue;
                }
                switch (op) {
                    case LESS -> d.setMin(k, false, at);
                    case LESS_EQUALS -> d.setMin(k, true, at);
                    case GREATER -> d.setMax(k, false, at);
                    case GREATER_EQUALS -> d.setMax(k, true, at);
                    default -> { }
                }
            }
        }
    }

    /**
     * then 쪽이 바로 오류를 만드는가. 안쪽의 다른 {@code if}가 만드는 오류는 세지 않는다 —
     * 바깥 조건이 범위가 아니라 "이 검사를 할지" 를 정하는 경우가 많다.
     */
    private boolean rejects(Statement then) {
        List<Statement> stmts = then instanceof BlockStmt b ? b.getStatements() : List.of(then);
        for (Statement s : stmts) {
            if (s instanceof IfStmt || s.isForEachStmt() || s.isForStmt() || s.isWhileStmt()) continue;
            if (s instanceof ThrowStmt) return true;
            boolean createsError = s.findFirst(ObjectCreationExpr.class,
                    oc -> oc.getType().getNameAsString().endsWith("Error")
                            || oc.getType().getNameAsString().endsWith("Exception")).isPresent();
            if (createsError) return true;
        }
        return false;
    }

    private void comparisonsIn(Expression e, List<BinaryExpr> out) {
        if (e instanceof EnclosedExpr en) {
            comparisonsIn(en.getInner(), out);
        } else if (e instanceof BinaryExpr b) {
            switch (b.getOperator()) {
                case OR, AND -> {
                    comparisonsIn(b.getLeft(), out);
                    comparisonsIn(b.getRight(), out);
                }
                case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> out.add(b);
                default -> { }
            }
        }
    }

    private static BinaryExpr.Operator flip(BinaryExpr.Operator op) {
        return switch (op) {
            case LESS -> BinaryExpr.Operator.GREATER;
            case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
            case GREATER -> BinaryExpr.Operator.LESS;
            case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
            default -> op;
        };
    }

    // ── 기본값 ────────────────────────────────────────────────────────────────

    /**
     * 필드 초기값을 템플릿 기본값으로. 풀리지 않으면 {@link #UNRESOLVED}.
     * {@code Enum.X.name()}은 {@code "X"}로 푼다 — 문자열 필드에 enum 기본값을 두는 흔한 꼴이다.
     */
    private Object evalDefault(ConfigModel.FieldInfo f) {
        Expression e = f.initializer();
        if (e == null) return defaultOfPrimitive(f.typeName());
        if (e instanceof NullLiteralExpr) return null;
        if (e instanceof BooleanLiteralExpr b) return b.getValue();
        if (e instanceof StringLiteralExpr s) return s.asString();
        if (e instanceof MethodCallExpr call && call.getNameAsString().equals("name")
                && call.getArguments().isEmpty()
                && call.getScope().orElse(null) instanceof FieldAccessExpr fa
                && fa.getScope() instanceof NameExpr owner
                && index.isEnum(owner.getNameAsString())) {
            return fa.getNameAsString();
        }
        Double n = index.evalNumber(e, config.decl);
        if (n != null) return isIntegral(f.typeName()) ? (Object) n.longValue() : n;
        return UNRESOLVED;
    }

    /** 초기값이 없는 기본형은 자바 기본값이다. 참조형은 {@code null}. */
    private static Object defaultOfPrimitive(String type) {
        return switch (type) {
            case "int", "long", "short", "byte" -> 0L;
            case "double", "float" -> 0.0;
            case "boolean" -> false;
            default -> null;
        };
    }

    static final Object UNRESOLVED = new Object() {
        @Override
        public String toString() {
            return "<UNRESOLVED>";
        }
    };

    // ── 초안 ──────────────────────────────────────────────────────────────────

    private final class Draft {
        final ConfigModel.FieldInfo f;
        final String kind;
        String enumName;
        List<String> allowed = List.of();
        Double min;
        Double max;
        boolean minExclusive;
        boolean maxExclusive;
        final List<String> evidence = new ArrayList<>();
        final List<String> review = new ArrayList<>();

        Draft(ConfigModel.FieldInfo f) {
            this.f = f;
            this.kind = baseKind(f.type());
            evidence.add("필드 " + f.location() + " " + f.typeName() + " " + f.name()
                    + (f.initializer() == null ? "" : " = " + f.initializer()));
            // 필드 타입이 enum 이면 해석 호출이 없어도 선택지가 정해진다.
            String t = f.typeName();
            if (index.isEnum(t)) linkEnum(t, index.enumConstants(t), "필드 타입이 enum");
        }

        void linkEnum(String name, List<String> constants, String at) {
            if (enumName != null && !enumName.equals(name)) {
                review("선택지: 서로 다른 enum 두 개로 해석됨 — " + enumName + ", " + name);
                return;
            }
            if (enumName == null) evidence.add("선택지 " + at);
            enumName = name;
            allowed = List.copyOf(constants);
        }

        void setMin(double k, boolean exclusive, String at) {
            if (min != null && (min != k || minExclusive != exclusive)) {
                review("범위: 하한이 두 곳에서 다르게 정의됨 — " + at);
                return;
            }
            min = k;
            minExclusive = exclusive;
            evidence.add("하한 " + at);
        }

        void setMax(double k, boolean exclusive, String at) {
            if (max != null && (max != k || maxExclusive != exclusive)) {
                review("범위: 상한이 두 곳에서 다르게 정의됨 — " + at);
                return;
            }
            max = k;
            maxExclusive = exclusive;
            evidence.add("상한 " + at);
        }

        void review(String why) {
            if (!review.contains(why)) review.add(why);
        }

        String valueType() {
            return switch (kind) {
                case "STRING" -> enumName != null ? "ENUM" : "STRING";
                case "STRING_LIST" -> enumName != null ? "ENUM_LIST" : "STRING_LIST";
                default -> kind;
            };
        }

        GeneratedTemplate build(ConditionExtractor.Extracted cond, Map<String, Truth.Kind> kinds,
                                boolean conditionsRequested) {
            String valueType = valueType();
            Object def = evalDefault(f);
            String basis = null;
            if (def == UNRESOLVED) {
                review("기본값: 초기값을 상수로 풀지 못함 — " + f.initializer());
                def = null;
            } else if (def != null) {
                basis = f.initializer() == null
                        ? "필드 " + f.name() + " 의 자바 기본값"
                        : "필드 초기값 " + f.name() + " = " + f.initializer();
            }
            if (def == null) {
                review("기본값: 초기값이 null 이다 — 반드시 물을지, 다른 곳(해석 메서드 등)에 기본값이 있는지 확인");
            }
            if ("BOOLEAN".equals(valueType)) {
                review("값 종류: 불리언 — 사용자에게 물을 선택지 이름(예: APPLY/IGNORE)을 정해야 함");
            }

            // 생성 조건. 엔진이 어떤 설정에서든 이 필드를 쓸 수 있으면 ALWAYS, 아니면 조건식을
            // 남기고 이름 붙은 조건을 사람이 고르게 한다 — 서버는 자유 식이 아니라 명명된 조건만 받는다.
            String generateWhen = "ALWAYS";
            String expr = null;
            Cond condition = null;
            List<String> hints = List.of();
            List<String> reads = List.of();
            if (!conditionsRequested) {
                review("생성 조건: 뽑지 않음 — ALWAYS 로 둠");
            } else if (cond == null || cond.condition().equals(Cond.FALSE)) {
                generateWhen = null;
                expr = "false";
                condition = Cond.FALSE;
                if (cond != null) reads = cond.readSites();
                review("생성 조건: 엔진이 이 필드를 읽지 않음 — 결과에 영향이 없는 값인지 확인");
            } else {
                condition = cond.condition();
                expr = condition.toString();
                hints = cond.runtimeHints();
                reads = cond.readSites();
                if (!Truth.alwaysMay(cond.condition(), kinds)) {
                    generateWhen = null;
                    review("생성 조건: 이름 붙은 조건이 필요 — " + expr);
                } else if (!hints.isEmpty()) {
                    review("생성 조건: 설정만으로는 ALWAYS 지만 실행 중에만 갈리는 조건이 있음 — " + String.join(" / ", hints));
                }
            }
            review("노출 여부: 사용자에게 물을 결정인지 내부 매개변수인지 판단 필요");
            return new GeneratedTemplate(
                    "gen." + f.name(), f.name(), valueType, generateWhen, expr, condition, allowed,
                    min, max, minExclusive, maxExclusive, unitOf(f.name()), def, basis,
                    questionOf(f), f.name(), List.copyOf(evidence), reads, hints, List.copyOf(review));
        }
    }

    /** 설정 필드 타입 → 템플릿 값 종류. 옮길 수 없으면 {@code null}. */
    private String baseKind(Type type) {
        String t = type.asString();
        switch (t) {
            case "int", "Integer", "long", "Long", "short", "Short" -> { return "INTEGER"; }
            case "double", "Double", "float", "Float" -> { return "NUMBER"; }
            case "boolean", "Boolean" -> { return "BOOLEAN"; }
            case "String" -> { return "STRING"; }
            default -> { }
        }
        if (index.isEnum(t)) return "STRING";
        if (type instanceof ClassOrInterfaceType c && c.getNameAsString().equals("List")
                && c.getTypeArguments().isPresent() && c.getTypeArguments().get().size() == 1) {
            String arg = c.getTypeArguments().get().get(0).asString();
            if (arg.equals("Integer") || arg.equals("Long")) return "INTEGER_LIST";
            if (arg.equals("String")) return "STRING_LIST";
        }
        return null;
    }

    private static boolean isIntegral(String type) {
        return switch (type) {
            case "int", "Integer", "long", "Long", "short", "Short" -> true;
            default -> false;
        };
    }

    /** 이름 끝말에서 단위를 읽는다. 장량동 템플릿이 쓰는 표기({@code minute}, {@code kg})를 따른다. */
    private static String unitOf(String name) {
        if (name.endsWith("Minutes")) return "minute";
        if (name.endsWith("Kg")) return "kg";
        if (name.endsWith("Days")) return "day";
        return null;
    }

    /** Javadoc 첫 문장을 질문 초안으로. 문장을 짓지 않는다 — 그건 LLM·제공자의 몫이다. */
    private static String questionOf(ConfigModel.FieldInfo f) {
        if (f.javadoc() == null || f.javadoc().isBlank()) return null;
        String text = f.javadoc().replaceAll("\\{@\\w+ ([^}]*)}", "$1");
        int end = text.indexOf(". ");
        int endKo = text.indexOf("다. ");
        if (endKo >= 0 && (end < 0 || endKo + 1 < end)) end = endKo + 1;
        return end > 0 ? text.substring(0, end + 1).trim() : text.trim();
    }
}
