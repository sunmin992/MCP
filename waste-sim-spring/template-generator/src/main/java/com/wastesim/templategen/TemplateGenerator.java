package com.wastesim.templategen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 시뮬레이터 소스 코드에서 서브태스크 템플릿 초안을 뽑는다.
 *
 * <p>읽는 것은 셋이다.
 * <ul>
 *   <li><b>설정 클래스</b> — 세터가 있는 필드가 사용자가 정할 수 있는 후보다. 타입이 값 종류를,
 *       초기값이 기본값을, Javadoc 이 질문 초안을 준다.</li>
 *   <li><b>검증기</b> — 오류를 내는 {@code if}의 비교식이 범위를 준다. {@code days < 1 || days > 365}
 *       면 1~365 다. 검증기가 범위의 정본이므로 범위를 다른 데서 추측하지 않는다.</li>
 *   <li><b>enum</b> — 문자열 필드가 {@code X.fromName(...)}/{@code X.valueOf(...)}로 해석되면
 *       그 enum 의 상수가 선택지다.</li>
 * </ul>
 *
 * <p><b>추측하지 않는다.</b> 상수로 풀리지 않는 상한(예: 차종에 달린 용량)은 비워 두고
 * {@code needsReview}에 식을 적는다. 그럴듯한 값을 채우면 제공자가 틀린 초안을 믿게 된다.
 *
 * <p>생성 조건은 뽑지 않는다 — 항상 {@code ALWAYS}로 두고 검토 대상으로 표시한다. 어떤 결정이
 * 언제 결과에 영향을 주는지는 엔진의 제어 흐름을 따라가야 알 수 있어 이 판의 범위 밖이다.
 */
public final class TemplateGenerator {

    private final Map<String, CompilationUnit> unitsBySimpleName = new HashMap<>();
    private final Map<String, Path> pathsBySimpleName = new HashMap<>();
    private final Map<String, List<String>> enumConstants = new HashMap<>();

    /** @param sourceRoot 시뮬레이터 소스 루트(예: {@code simulator/src/main/java}) */
    public TemplateGenerator(Path sourceRoot) throws IOException {
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
                for (TypeDeclaration<?> t : cu.getTypes()) {
                    unitsBySimpleName.put(t.getNameAsString(), cu);
                    pathsBySimpleName.put(t.getNameAsString(), p);
                }
                for (EnumDeclaration e : cu.findAll(EnumDeclaration.class)) {
                    List<String> names = new ArrayList<>();
                    e.getEntries().forEach(en -> names.add(en.getNameAsString()));
                    enumConstants.put(e.getNameAsString(), names);
                }
            }
        }
    }

    /** 생성 결과. 템플릿으로 만들지 못한 필드는 이유와 함께 따로 둔다. */
    public record Result(List<GeneratedTemplate> templates, Map<String, String> skipped) {
    }

    /**
     * @param configClass     설정 클래스의 단순 이름 또는 FQCN
     * @param validatorClasses 범위를 검증하는 클래스들
     */
    public Result generate(String configClass, List<String> validatorClasses) {
        ClassOrInterfaceDeclaration config = classOf(configClass);
        this.config = config;
        Map<String, FieldInfo> fields = configurableFields(config);
        this.getters = getters(config, fields);

        Map<String, Draft> drafts = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        for (FieldInfo f : fields.values()) {
            Draft d = new Draft(f);
            if (d.kind == null) {
                skipped.put(f.name, "템플릿 값 종류로 옮길 수 없는 타입: " + f.type);
            } else {
                drafts.put(f.name, d);
            }
        }

        // 설정 클래스 자신(resolveX 메서드)과 검증기에서 enum 해석과 범위를 찾는다.
        List<ClassOrInterfaceDeclaration> scanned = new ArrayList<>();
        scanned.add(config);
        for (String v : validatorClasses) scanned.add(classOf(v));
        for (ClassOrInterfaceDeclaration cls : scanned) {
            boolean isValidator = cls != config;
            for (MethodDeclaration m : cls.getMethods()) {
                Map<String, FieldRef> locals = locals(m, fields, cls == config);
                linkEnums(cls, m, fields, locals, drafts);
                if (isValidator) collectBounds(cls, m, fields, locals, drafts);
            }
        }

        List<GeneratedTemplate> out = new ArrayList<>();
        for (Draft d : drafts.values()) out.add(d.build());
        return new Result(out, skipped);
    }

    // ── 설정 필드 ──────────────────────────────────────────────────────────────

    private record FieldInfo(String name, Type type, Expression initializer, String javadoc,
                             String location) {
        String typeName() {
            return type.asString();
        }
    }

    /** 이 필드를 가리키는 식인가. {@code element}면 목록 필드의 원소(foreach 변수)다. */
    private record FieldRef(String field, boolean element) {
    }

    /** 세터가 있는 인스턴스 필드만 — 세터가 없으면 바깥에서 정할 수 없는 내부 상태다. */
    private Map<String, FieldInfo> configurableFields(ClassOrInterfaceDeclaration config) {
        Map<String, FieldInfo> out = new LinkedHashMap<>();
        for (FieldDeclaration fd : config.getFields()) {
            if (fd.isStatic()) continue;
            String javadoc = fd.getJavadoc()
                    .map(j -> j.getDescription().toText().replaceAll("\\s+", " ").trim())
                    .orElse(null);
            for (VariableDeclarator v : fd.getVariables()) {
                String name = v.getNameAsString();
                String setter = "set" + capitalize(name);
                boolean hasSetter = config.getMethodsByName(setter).stream()
                        .anyMatch(m -> m.getParameters().size() == 1);
                if (!hasSetter) continue;
                out.put(name, new FieldInfo(name, v.getType(), v.getInitializer().orElse(null),
                        javadoc, where(config, v)));
            }
        }
        return out;
    }

    /**
     * 게터 이름 → 그 게터가 돌려주는 필드. 이름이 아니라 <b>본문</b>을 따른다 —
     * {@code getTruckCount() { return numTrucks; }} 같은 별칭 게터로 검사하는 검증기가 있다.
     * 본문이 {@code return 필드;} 이거나 다른 게터를 그대로 부르는 경우만 따른다.
     */
    private Map<String, String> getters(ClassOrInterfaceDeclaration config, Map<String, FieldInfo> fields) {
        Map<String, Expression> returns = new HashMap<>();
        for (MethodDeclaration m : config.getMethods()) {
            if (m.isStatic() || !m.getParameters().isEmpty() || m.getBody().isEmpty()) continue;
            List<Statement> body = m.getBody().get().getStatements();
            if (body.size() == 1 && body.get(0) instanceof com.github.javaparser.ast.stmt.ReturnStmt r
                    && r.getExpression().isPresent()) {
                returns.put(m.getNameAsString(), r.getExpression().get());
            }
        }
        Map<String, String> out = new HashMap<>();
        for (String name : returns.keySet()) {
            Expression e = returns.get(name);
            for (int hop = 0; hop < 5 && e != null; hop++) {
                if (e instanceof NameExpr ne && fields.containsKey(ne.getNameAsString())) {
                    out.put(name, ne.getNameAsString());
                    break;
                }
                if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr
                        && fields.containsKey(fa.getNameAsString())) {
                    out.put(name, fa.getNameAsString());
                    break;
                }
                e = e instanceof MethodCallExpr c && c.getArguments().isEmpty()
                        && (c.getScope().isEmpty() || c.getScope().get() instanceof ThisExpr)
                        ? returns.get(c.getNameAsString()) : null;
            }
        }
        return out;
    }

    /**
     * 메서드 안의 지역 변수 중 필드 값을 받은 것. {@code int t = c.getCollectionTimeMinutes();}
     * 처럼 한 번 받아 두고 비교하는 검증이 흔하다.
     */
    private Map<String, FieldRef> locals(MethodDeclaration m, Map<String, FieldInfo> fields,
                                         boolean insideConfig) {
        Map<String, FieldRef> locals = new HashMap<>();
        // 설정 객체를 담은 변수. 게터는 이 변수에서 불렀을 때만 설정 필드로 본다 —
        // 분리배출 유형의 w.getThreshold() 같은 이름만 같은 게터를 걸러 낸다.
        configVars = new java.util.HashSet<>();
        String configName = config.getNameAsString();
        m.getParameters().forEach(p -> {
            if (p.getType().asString().equals(configName)) configVars.add(p.getNameAsString());
        });
        m.findAll(VariableDeclarator.class).forEach(v -> {
            if (v.getType().asString().equals(configName)) configVars.add(v.getNameAsString());
        });
        m.findAll(VariableDeclarator.class).forEach(v -> v.getInitializer().ifPresent(init -> {
            FieldRef ref = fieldOf(init, fields, locals, insideConfig);
            if (ref != null) locals.put(v.getNameAsString(), ref);
        }));
        m.findAll(ForEachStmt.class).forEach(fe -> {
            FieldRef ref = fieldOf(fe.getIterable(), fields, locals, insideConfig);
            if (ref != null && !ref.element()) {
                locals.put(fe.getVariableDeclarator().getNameAsString(), new FieldRef(ref.field(), true));
            }
        });
        return locals;
    }

    private FieldRef fieldOf(Expression e, Map<String, FieldInfo> fields, Map<String, FieldRef> locals,
                             boolean insideConfig) {
        if (e instanceof EnclosedExpr en) return fieldOf(en.getInner(), fields, locals, insideConfig);
        if (e instanceof CastExpr c) return fieldOf(c.getExpression(), fields, locals, insideConfig);
        if (e instanceof MethodCallExpr call && call.getArguments().isEmpty()) {
            String n = call.getNameAsString();
            String bare = n.startsWith("get") ? n.substring(3) : n.startsWith("is") ? n.substring(2) : null;
            Expression scope = call.getScope().orElse(null);
            String viaBody = getters.get(n);
            boolean onConfig = scope == null ? insideConfig
                    : scope instanceof ThisExpr
                    || (scope instanceof NameExpr sn && configVars.contains(sn.getNameAsString()));
            if (onConfig && viaBody != null) return new FieldRef(viaBody, false);
            if (onConfig && bare != null && !bare.isEmpty()) {
                String field = decapitalize(bare);
                if (fields.containsKey(field)) return new FieldRef(field, false);
            }
            return null;
        }
        if (e instanceof NameExpr ne) {
            String n = ne.getNameAsString();
            if (locals.containsKey(n)) return locals.get(n);
            if (insideConfig && fields.containsKey(n)) return new FieldRef(n, false);
            return null;
        }
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr
                && fields.containsKey(fa.getNameAsString())) {
            return new FieldRef(fa.getNameAsString(), false);
        }
        return null;
    }

    // ── enum 선택지 ────────────────────────────────────────────────────────────

    /** {@code Enum.fromName(필드)} / {@code Enum.valueOf(필드)} 를 찾아 필드에 선택지를 단다. */
    private void linkEnums(ClassOrInterfaceDeclaration cls, MethodDeclaration m, Map<String, FieldInfo> fields,
                           Map<String, FieldRef> locals, Map<String, Draft> drafts) {
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            String n = call.getNameAsString();
            if (!(n.equals("fromName") || n.equals("valueOf")) || call.getArguments().size() != 1) continue;
            Optional<Expression> scope = call.getScope();
            if (scope.isEmpty() || !(scope.get() instanceof NameExpr se)) continue;
            String enumName = se.getNameAsString();
            if (!enumConstants.containsKey(enumName)) continue;
            FieldRef ref = fieldOf(call.getArgument(0), fields, locals, cls == config);
            if (ref == null) continue;
            Draft d = drafts.get(ref.field());
            if (d == null) continue;
            d.linkEnum(enumName, enumConstants.get(enumName), where(cls, call) + " " + call);
        }
    }

    // ── 범위 ───────────────────────────────────────────────────────────────────

    /**
     * 오류를 내는 {@code if}의 조건에서 "필드 비교 상수"를 찾는다. 조건이 참이면 거절이므로
     * {@code x < 1}은 하한 1, {@code x > 365}는 상한 365, {@code x <= 0}은 "0 초과"다.
     */
    private void collectBounds(ClassOrInterfaceDeclaration cls, MethodDeclaration m, Map<String, FieldInfo> fields,
                               Map<String, FieldRef> locals, Map<String, Draft> drafts) {
        for (IfStmt ifs : m.findAll(IfStmt.class)) {
            if (!rejects(ifs.getThenStmt())) continue;
            List<BinaryExpr> comparisons = new ArrayList<>();
            comparisonsIn(ifs.getCondition(), comparisons);
            for (BinaryExpr b : comparisons) {
                FieldRef left = fieldOf(b.getLeft(), fields, locals, false);
                FieldRef right = fieldOf(b.getRight(), fields, locals, false);
                if ((left == null) == (right == null)) continue;
                FieldRef ref = left != null ? left : right;
                Expression other = left != null ? b.getRight() : b.getLeft();
                BinaryExpr.Operator op = left != null ? b.getOperator() : flip(b.getOperator());
                Draft d = drafts.get(ref.field());
                if (d == null) continue;
                String at = where(cls, b) + " " + b;
                Double k = evalNumber(other, cls);
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

    // ── 상수 계산 ──────────────────────────────────────────────────────────────

    /** 리터럴 · 사칙연산 · 같은 클래스(또는 지정 클래스)의 static final 상수만 푼다. */
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
        if (e instanceof NameExpr n) {
            return constant(context, n.getNameAsString());
        }
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof NameExpr owner
                && findClass(owner.getNameAsString()) != null) {
            return constant(findClass(owner.getNameAsString()), fa.getNameAsString());
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

    /**
     * 필드 초기값을 템플릿 기본값으로. 풀리지 않으면 {@link #UNRESOLVED}.
     * {@code Enum.X.name()}은 {@code "X"}로 푼다 — 문자열 필드에 enum 기본값을 두는 흔한 꼴이다.
     */
    private Object evalDefault(FieldInfo f, ClassOrInterfaceDeclaration config) {
        Expression e = f.initializer();
        if (e == null) return defaultOfPrimitive(f.typeName());
        if (e instanceof NullLiteralExpr) return null;
        if (e instanceof BooleanLiteralExpr b) return b.getValue();
        if (e instanceof StringLiteralExpr s) return s.asString();
        if (e instanceof MethodCallExpr call && call.getNameAsString().equals("name")
                && call.getArguments().isEmpty()
                && call.getScope().orElse(null) instanceof FieldAccessExpr fa
                && fa.getScope() instanceof NameExpr owner
                && enumConstants.containsKey(owner.getNameAsString())) {
            return fa.getNameAsString();
        }
        Double n = evalNumber(e, config);
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
        final FieldInfo f;
        final String kind;
        String enumName;
        List<String> allowed = List.of();
        Double min;
        Double max;
        boolean minExclusive;
        boolean maxExclusive;
        final List<String> evidence = new ArrayList<>();
        final List<String> review = new ArrayList<>();

        Draft(FieldInfo f) {
            this.f = f;
            this.kind = baseKind(f.type());
            evidence.add("필드 " + f.location() + " " + f.typeName() + " " + f.name()
                    + (f.initializer() == null ? "" : " = " + f.initializer()));
            // 필드 타입이 enum 이면 해석 호출이 없어도 선택지가 정해진다.
            String t = f.typeName();
            if (enumConstants.containsKey(t)) linkEnum(t, enumConstants.get(t), "필드 타입이 enum");
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

        GeneratedTemplate build() {
            String valueType = switch (kind) {
                case "STRING" -> enumName != null ? "ENUM" : "STRING";
                case "STRING_LIST" -> enumName != null ? "ENUM_LIST" : "STRING_LIST";
                default -> kind;
            };
            Object def = evalDefault(f, config);
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
            review("생성 조건: 뽑지 않음 — ALWAYS 로 둠");
            review("노출 여부: 사용자에게 물을 결정인지 내부 매개변수인지 판단 필요");
            return new GeneratedTemplate(
                    "gen." + f.name(), f.name(), valueType, "ALWAYS", allowed,
                    min, max, minExclusive, maxExclusive, unitOf(f.name()), def, basis,
                    questionOf(f), f.name(), List.copyOf(evidence), List.copyOf(review));
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
        if (enumConstants.containsKey(t)) return "STRING";
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
    private static String questionOf(FieldInfo f) {
        if (f.javadoc() == null || f.javadoc().isBlank()) return null;
        String text = f.javadoc().replaceAll("\\{@\\w+ ([^}]*)}", "$1");
        int end = text.indexOf(". ");
        int endKo = text.indexOf("다. ");
        if (endKo >= 0 && (end < 0 || endKo + 1 < end)) end = endKo + 1;
        return end > 0 ? text.substring(0, end + 1).trim() : text.trim();
    }

    // ── 공용 ──────────────────────────────────────────────────────────────────

    /** 게터 이름 → 돌려주는 필드. {@link #generate} 가 정한다. */
    private Map<String, String> getters = Map.of();

    /** 지금 훑는 메서드에서 설정 객체를 담은 변수 이름. {@link #locals} 가 정한다. */
    private java.util.Set<String> configVars = java.util.Set.of();

    /** 지금 생성 중인 설정 클래스. {@link #generate} 가 정한다. */
    private ClassOrInterfaceDeclaration config;

    private ClassOrInterfaceDeclaration classOf(String name) {
        ClassOrInterfaceDeclaration cls = findClass(name);
        if (cls == null) throw new IllegalArgumentException("소스에서 클래스를 찾지 못했습니다: " + name);
        return cls;
    }

    /** 단순 이름 또는 FQCN 으로 클래스를 찾는다. enum · 인터페이스 · 없는 이름이면 {@code null}. */
    private ClassOrInterfaceDeclaration findClass(String name) {
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        CompilationUnit cu = unitsBySimpleName.get(simple);
        return cu == null ? null : cu.getClassByName(simple).orElse(null);
    }

    private String where(ClassOrInterfaceDeclaration cls, Node n) {
        Path p = pathsBySimpleName.get(cls.getNameAsString());
        String file = p == null ? cls.getNameAsString() : p.getFileName().toString();
        return file + ":" + n.getBegin().map(pos -> pos.line).orElse(0);
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
