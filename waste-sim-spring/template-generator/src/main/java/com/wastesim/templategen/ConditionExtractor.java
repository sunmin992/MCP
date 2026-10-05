package com.wastesim.templategen;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 생성 조건을 엔진 코드에서 뽑는다 — "이 설정 필드는 <b>어떤 설정일 때</b> 결과에 쓰이는가".
 *
 * <p>방법: 진입점(첫 모델 클래스의 public 메서드)에서 닿는 메서드만 훑고, 필드를 읽는 자리마다
 * 그 자리를 감싸는 조건을 모은다.
 * <ul>
 *   <li>메서드 안: 감싸는 {@code if}/{@code else}, 삼항식, {@code &&}·{@code ||}의 앞쪽, 앞선 문장의
 *       조기 탈출({@code if (c) return/throw/continue/break;})</li>
 *   <li>메서드 사이: 그 메서드를 부르는 자리들의 조건을 "또는" 으로 잇는다</li>
 * </ul>
 * 필드의 생성 조건은 모든 읽는 자리의 조건을 "또는" 으로 이은 것이다.
 *
 * <p>설정 필드로 옮길 수 없는 조건(경로가 비었는가, 같은 구역인가 …)은 {@link Cond.Runtime}으로
 * 남긴다. 생성 조건은 "그 필드가 쓰일 수 있는가" 이므로 실행 조건은 참일 수 있다고 읽는다 —
 * 그래서 이 추출은 <b>필요조건</b>이다. 물을 필요가 없을 때 물을 수는 있어도, 물어야 할 때 빠뜨리지는
 * 않는 쪽으로 기운다.
 *
 * <p>결과 객체에 값을 기록하기만 하는 읽기(진입점이 돌려주는 타입의 생성자 · 세터 인자)는 조건에서
 * 뺀다 — 동작이 아니라 보고다. 읽는 자리 목록에는 "(결과 기록)" 으로 남긴다.
 *
 * <p>읽지 않는 것: {@code switch}, 반복문의 조건, 예외 흐름, 리플렉션. 대화 규칙(예: "사용자가 말했을
 * 때만 묻는다")은 코드에 없으므로 나오지 않는다.
 */
final class ConditionExtractor {

    /** 필드 하나의 결과. */
    record Extracted(Cond condition, List<String> readSites, List<String> runtimeHints) {
    }

    private final SourceIndex index;
    private final ConfigModel config;
    private final List<ClassOrInterfaceDeclaration> modelClasses = new ArrayList<>();
    private final Map<MethodDeclaration, ConfigModel.Scope> scopes = new IdentityHashMap<>();
    /** 피호출 메서드 → 그 메서드를 부르는 자리들. */
    private final Map<MethodDeclaration, List<MethodCallExpr>> callers = new IdentityHashMap<>();
    private final Set<MethodDeclaration> entries = new HashSet<>();
    private final Set<MethodDeclaration> reachable = new HashSet<>();
    private final Map<MethodDeclaration, Cond> methodGuards = new IdentityHashMap<>();
    private final Set<MethodDeclaration> inProgress = new HashSet<>();
    /** 진입점 쪽 public 메서드가 돌려주는 소스 안의 타입 — 여기에 값을 쓰기만 하는 읽기는 영향이 아니다. */
    private final Set<String> resultTypes = new HashSet<>();

    /** @param modelClassNames 엔진 쪽 클래스들. 첫 클래스의 public 메서드가 진입점이다 */
    ConditionExtractor(SourceIndex index, ConfigModel config, List<String> modelClassNames) {
        this.index = index;
        this.config = config;
        for (String n : modelClassNames) modelClasses.add(index.classOf(n));
        if (!modelClasses.contains(config.decl)) modelClasses.add(config.decl);
        for (MethodDeclaration m : modelClasses.get(0).getMethods()) {
            if (m.isPublic()) entries.add(m);
        }
        for (ClassOrInterfaceDeclaration cls : modelClasses) {
            for (MethodDeclaration m : cls.getMethods()) {
                String t = m.getType().asString();
                if (m.isPublic() && index.findClass(t) != null && !t.equals(config.simpleName())) resultTypes.add(t);
            }
        }
        buildCallGraph();
    }

    Map<String, Extracted> extract() {
        Map<String, List<Cond>> guards = new LinkedHashMap<>();
        Map<String, List<String>> sites = new LinkedHashMap<>();
        for (MethodDeclaration m : reachable) {
            if (isAccessor(m)) continue;
            ConfigModel.Scope s = scope(m);
            for (Expression e : m.findAll(Expression.class)) {
                if (!(e instanceof MethodCallExpr || e instanceof NameExpr || e instanceof FieldAccessExpr)) continue;
                if (s.localDefinitions.contains(e)) continue;
                ConfigModel.FieldRef ref = config.fieldOf(e, s);
                String field = ref != null ? ref.field()
                        : e instanceof MethodCallExpr call ? config.presenceField(call, s) : null;
                if (field == null) continue;
                Cond mg = methodGuard(m);
                if (records(e, s) || mg.equals(Cond.FALSE)) {
                    sites.computeIfAbsent(field, k -> new ArrayList<>()).add(SourceIndex.where(e) + " (결과 기록) " + e);
                    continue;
                }
                Cond g = Cond.and(List.of(mg, localGuard(e, m)));
                guards.computeIfAbsent(field, k -> new ArrayList<>()).add(Cond.selfAsRuntime(g, field));
                sites.computeIfAbsent(field, k -> new ArrayList<>()).add(SourceIndex.where(e) + " " + e);
            }
        }
        Map<String, Extracted> out = new LinkedHashMap<>();
        for (String f : config.fields.keySet()) {
            if (!guards.containsKey(f)) {
                out.put(f, new Extracted(Cond.FALSE, List.copyOf(sites.getOrDefault(f, List.of())), List.of()));
                continue;
            }
            Cond c = Cond.or(guards.get(f));
            Set<String> runtimes = new LinkedHashSet<>();
            Cond.collect(c, new LinkedHashSet<>(), runtimes, new LinkedHashMap<>());
            out.put(f, new Extracted(c, List.copyOf(new LinkedHashSet<>(sites.get(f))),
                    runtimes.stream().filter(r -> !r.startsWith("자기 자신")).toList()));
        }
        return out;
    }

    // ── 호출 그래프 ────────────────────────────────────────────────────────────

    private void buildCallGraph() {
        for (ClassOrInterfaceDeclaration cls : modelClasses) {
            for (MethodDeclaration m : cls.getMethods()) {
                for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                    for (MethodDeclaration t : targets(call, m)) {
                        callers.computeIfAbsent(t, k -> new ArrayList<>()).add(call);
                    }
                }
            }
        }
        Deque<MethodDeclaration> todo = new ArrayDeque<>(entries);
        while (!todo.isEmpty()) {
            MethodDeclaration m = todo.pop();
            if (!reachable.add(m)) continue;
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) todo.addAll(targets(call, m));
        }
    }

    private List<MethodDeclaration> targets(MethodCallExpr call, MethodDeclaration from) {
        ClassOrInterfaceDeclaration owner = from.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (owner == null) return List.of();
        ConfigModel.Scope s = scope(from);
        Expression scope = call.getScope().orElse(null);
        ClassOrInterfaceDeclaration target = null;
        if (scope == null || scope instanceof ThisExpr) {
            target = owner;
        } else if (scope instanceof NameExpr n) {
            String name = n.getNameAsString();
            if (s.configVars.contains(name)) {
                target = config.decl;
            } else if (index.findClass(name) != null && modelClasses.contains(index.findClass(name))) {
                target = index.findClass(name);
            } else {
                target = fieldType(owner, name);
            }
        } else if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            target = fieldType(owner, fa.getNameAsString());
        }
        if (target == null || !modelClasses.contains(target)) return List.of();
        int argc = call.getArguments().size();
        return target.getMethodsByName(call.getNameAsString()).stream()
                .filter(m -> m.getParameters().size() == argc).toList();
    }

    /** 이 클래스의 필드 {@code name}의 타입이 모델 클래스면 그 클래스. */
    private ClassOrInterfaceDeclaration fieldType(ClassOrInterfaceDeclaration owner, String name) {
        for (FieldDeclaration fd : owner.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                if (v.getNameAsString().equals(name)) {
                    ClassOrInterfaceDeclaration c = index.findClass(v.getType().asString());
                    return c != null && modelClasses.contains(c) ? c : null;
                }
            }
        }
        return null;
    }

    private boolean isAccessor(MethodDeclaration m) {
        return m.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c == config.decl).orElse(false)
                && config.accessors.containsKey(m.getNameAsString()) && m.getParameters().isEmpty();
    }

    private ConfigModel.Scope scope(MethodDeclaration m) {
        return scopes.computeIfAbsent(m, config::scope);
    }

    // ── 조건 ──────────────────────────────────────────────────────────────────

    /** 이 메서드가 불릴 수 있는 조건. 진입점이면 참, 아니면 부르는 자리들의 "또는". */
    private Cond methodGuard(MethodDeclaration m) {
        if (entries.contains(m)) return Cond.TRUE;
        if (methodGuards.containsKey(m)) return methodGuards.get(m);
        if (!inProgress.add(m)) return Cond.TRUE;   // 순환: 조건을 좁히지 않는다
        List<Cond> parts = new ArrayList<>();
        boolean onlyRecording = false;
        for (MethodCallExpr call : callers.getOrDefault(m, List.of())) {
            MethodDeclaration caller = call.findAncestor(MethodDeclaration.class).orElse(null);
            if (caller == null || !reachable.contains(caller)) continue;
            // 설정 클래스의 메서드(라벨 등)를 결과에 그대로 싣는 호출만 기록으로 본다. 엔진 메서드의
            // 계산값을 결과에 넣는 것은 기록이 아니라 시뮬레이션 그 자체다.
            if (isConfigMethod(m) && records(call, scope(caller))) {
                onlyRecording = true;
                continue;
            }
            parts.add(Cond.and(List.of(methodGuard(caller), localGuard(call, caller))));
        }
        inProgress.remove(m);
        // 결과에 기록하려고만 불리는 메서드는 시뮬레이션에 영향이 없다.
        Cond g = parts.isEmpty() ? (onlyRecording ? Cond.FALSE : Cond.TRUE) : Cond.or(parts);
        methodGuards.put(m, g);
        return g;
    }

    /**
     * 이 읽기가 설정값을 결과 객체에 <b>그대로</b> 기록하기만 하는가.
     * <ul>
     *   <li>결과 타입 생성자의 인자, 또는 결과 타입 변수에 대한 호출의 인자 — 단 읽은 값 자체이거나
     *       {@code .name()} 같은 인자 없는 변환만 거친 것. 계산에 섞인 값은 기록이 아니다</li>
     *   <li>then/else 가 결과 기록뿐인 {@code if}의 조건</li>
     * </ul>
     * 수거 시각 라벨을 결과에 싣는 것처럼 시뮬레이션 동작을 바꾸지 않는 읽기다.
     */
    private boolean records(Node read, ConfigModel.Scope s) {
        Node cur = read;
        while (cur.getParentNode().orElse(null) instanceof MethodCallExpr up
                && up.getScope().orElse(null) == cur && up.getArguments().isEmpty()) {
            cur = up;
        }
        Node p = cur.getParentNode().orElse(null);
        if (p instanceof ObjectCreationExpr oc && resultTypes.contains(oc.getType().getNameAsString())
                && oc.getArguments().contains(cur)) {
            return true;
        }
        if (p instanceof MethodCallExpr call && call.getArguments().contains(cur) && isResultVar(call, s)) {
            return true;
        }
        // 조건식 안을 문장 경계까지 거슬러 올라가, 그 문장이 if 이고 읽기가 조건 쪽에 있는지 본다.
        Node child = read;
        Node owner = read.getParentNode().orElse(null);
        while (owner != null && !(owner instanceof Statement)) {
            child = owner;
            owner = owner.getParentNode().orElse(null);
        }
        if (owner instanceof IfStmt ifs && ifs.getCondition() == child) {
            return onlyRecords(ifs.getThenStmt(), s) && ifs.getElseStmt().map(e -> onlyRecords(e, s)).orElse(true);
        }
        return false;
    }

    private boolean isResultVar(MethodCallExpr call, ConfigModel.Scope s) {
        return call.getScope().orElse(null) instanceof NameExpr n
                && resultTypes.contains(s.varTypes.get(n.getNameAsString()));
    }

    private boolean isConfigMethod(MethodDeclaration m) {
        return m.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c == config.decl).orElse(false);
    }

    private boolean onlyRecords(Statement st, ConfigModel.Scope s) {
        List<Statement> list = st instanceof BlockStmt b ? b.getStatements() : List.of(st);
        if (list.isEmpty()) return false;
        for (Statement x : list) {
            if (!(x instanceof ExpressionStmt es && es.getExpression() instanceof MethodCallExpr call
                    && isResultVar(call, s) && call.getArguments().stream().allMatch(a -> plain(a, s)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 계산이 섞이지 않은 인자인가: 리터럴 · 이름 · 설정 읽기 · 인자 없는 변환({@code .name()})뿐.
     * {@code result.add(fast(cfg))} 의 {@code fast(cfg)} 는 엔진의 계산이라 아니다.
     */
    private boolean plain(Expression a, ConfigModel.Scope s) {
        if (!a.findAll(ObjectCreationExpr.class).isEmpty()) return false;
        for (MethodCallExpr call : a.findAll(MethodCallExpr.class)) {
            boolean projection = call.getArguments().isEmpty() && call.getScope().isPresent();
            if (!config.onConfig(call, s) && !projection) return false;
        }
        return true;
    }

    /** 메서드 안에서 이 노드를 감싸는 조건. */
    private Cond localGuard(Node node, MethodDeclaration m) {
        ConfigModel.Scope s = scope(m);
        List<Cond> parts = new ArrayList<>();
        loopIndexFactor(node).ifPresent(parts::add);
        Node child = node;
        Node parent = child.getParentNode().orElse(null);
        while (parent != null && parent != m) {
            if (parent instanceof IfStmt ifs) {
                if (ifs.getThenStmt() == child) parts.add(toCond(ifs.getCondition(), s));
                else if (ifs.getElseStmt().orElse(null) == child) parts.add(Cond.not(toCond(ifs.getCondition(), s)));
            } else if (parent instanceof ConditionalExpr ce) {
                if (ce.getThenExpr() == child) parts.add(toCond(ce.getCondition(), s));
                else if (ce.getElseExpr() == child) parts.add(Cond.not(toCond(ce.getCondition(), s)));
            } else if (parent instanceof BinaryExpr b && b.getRight() == child) {
                if (b.getOperator() == BinaryExpr.Operator.AND) parts.add(toCond(b.getLeft(), s));
                if (b.getOperator() == BinaryExpr.Operator.OR) parts.add(Cond.not(toCond(b.getLeft(), s)));
            } else if (parent instanceof BlockStmt block && child instanceof Statement st) {
                for (Statement prev : block.getStatements()) {
                    if (prev == st) break;
                    if (prev instanceof IfStmt p && p.getElseStmt().isEmpty() && exits(p.getThenStmt())) {
                        parts.add(Cond.not(toCond(p.getCondition(), s)));
                    }
                }
            }
            child = parent;
            parent = child.getParentNode().orElse(null);
        }
        return Cond.and(parts);
    }

    private static boolean exits(Statement s) {
        if (s instanceof ReturnStmt || s instanceof ThrowStmt || s instanceof ContinueStmt || s instanceof BreakStmt) {
            return true;
        }
        return s instanceof BlockStmt b && !b.getStatements().isEmpty() && exits(b.getStatements().get(b.getStatements().size() - 1));
    }

    /**
     * {@code k * 필드} 처럼 0 부터 도는 반복 변수에 곱해지면, 반복이 두 번째에 들어서야 값이 쓰인다.
     * 배차 간격({@code slot + k * dispatchInterval})이 그렇다. 실행 조건으로 남겨 제공자에게 알린다.
     */
    private Optional<Cond> loopIndexFactor(Node read) {
        if (!(read.getParentNode().orElse(null) instanceof BinaryExpr b)
                || b.getOperator() != BinaryExpr.Operator.MULTIPLY) {
            return Optional.empty();
        }
        Expression other = b.getLeft() == read ? b.getRight() : b.getLeft();
        if (!(other instanceof NameExpr k)) return Optional.empty();
        for (ForStmt f = read.findAncestor(ForStmt.class).orElse(null); f != null;
             f = f.findAncestor(ForStmt.class).orElse(null)) {
            boolean startsAtZero = f.getInitialization().stream().anyMatch(i -> i.findAll(VariableDeclarator.class)
                    .stream().anyMatch(v -> v.getNameAsString().equals(k.getNameAsString())
                            && v.getInitializer().map(x -> x.toString().equals("0")).orElse(false)));
            if (startsAtZero) {
                String bound = f.getCompare().map(Object::toString).orElse("?");
                return Optional.of(new Cond.Runtime("반복 변수 " + k + " ≥ 1 일 때만 값이 쓰인다 — 반복이 두 번 이상 돌아야 함 ("
                        + bound + ")"));
            }
        }
        return Optional.empty();
    }

    /** 코드의 조건식을 설정 필드에 대한 조건으로. 옮길 수 없으면 실행 조건. */
    private Cond toCond(Expression e, ConfigModel.Scope s) {
        e = ConfigModel.unwrap(e);
        if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return Cond.not(toCond(u.getExpression(), s));
        }
        if (e instanceof BooleanLiteralExpr b) return b.getValue() ? Cond.TRUE : Cond.FALSE;
        if (e instanceof BinaryExpr b) {
            switch (b.getOperator()) {
                case AND -> {
                    return Cond.and(List.of(toCond(b.getLeft(), s), toCond(b.getRight(), s)));
                }
                case OR -> {
                    return Cond.or(List.of(toCond(b.getLeft(), s), toCond(b.getRight(), s)));
                }
                case EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> {
                    Cond c = comparison(b, s);
                    if (c != null) return c;
                }
                default -> { }
            }
        }
        if (e instanceof MethodCallExpr call) {
            String present = config.presenceField(call, s);
            if (present != null) return new Cond.Atom(present, "present", null);
            ConfigModel.FieldRef ref = config.fieldOf(call, s);
            if (ref != null && isBoolean(ref.field())) return new Cond.Atom(ref.field(), "==", true);
            if (call.getNameAsString().equals("isEmpty") && call.getArguments().isEmpty()
                    && call.getScope().isPresent()) {
                ConfigModel.FieldRef target = config.fieldOf(call.getScope().get(), s);
                if (target != null && !target.element()) return new Cond.Atom(target.field(), "empty", null);
            }
        }
        if (e instanceof NameExpr n) {
            ConfigModel.FieldRef ref = config.fieldOf(n, s);
            if (ref != null && isBoolean(ref.field())) return new Cond.Atom(ref.field(), "==", true);
        }
        return new Cond.Runtime(e.toString());
    }

    private Cond comparison(BinaryExpr b, ConfigModel.Scope s) {
        BinaryExpr.Operator op = b.getOperator();
        // x != null 에서 x 가 "조건 ? 값 : null" 로 받은 지역 변수면 그 조건이다.
        Cond viaTernary = nullTernary(b.getLeft(), b.getRight(), op, s);
        if (viaTernary == null) viaTernary = nullTernary(b.getRight(), b.getLeft(), op, s);
        if (viaTernary != null) return viaTernary;

        ConfigModel.FieldRef left = config.fieldOf(b.getLeft(), s);
        ConfigModel.FieldRef right = config.fieldOf(b.getRight(), s);
        if ((left == null) == (right == null)) return null;
        ConfigModel.FieldRef ref = left != null ? left : right;
        if (ref.element()) return null;
        Expression other = ConfigModel.unwrap(left != null ? b.getRight() : b.getLeft());
        String sym = symbol(left != null ? op : flip(op));
        if (other instanceof NullLiteralExpr) {
            Cond present = new Cond.Atom(ref.field(), "present", null);
            return sym.equals("!=") ? present : sym.equals("==") ? Cond.not(present) : null;
        }
        Object value = constant(other, s);
        if (value == null) return null;
        return new Cond.Atom(ref.field(), sym, value);
    }

    private Cond nullTernary(Expression var, Expression other, BinaryExpr.Operator op, ConfigModel.Scope s) {
        if (!(ConfigModel.unwrap(var) instanceof NameExpr n) || !(ConfigModel.unwrap(other) instanceof NullLiteralExpr)) {
            return null;
        }
        ConditionalExpr ce = s.nullTernaries.get(n.getNameAsString());
        if (ce == null || (op != BinaryExpr.Operator.NOT_EQUALS && op != BinaryExpr.Operator.EQUALS)) return null;
        Cond nonNull = ce.getElseExpr().isNullLiteralExpr() ? toCond(ce.getCondition(), s)
                : Cond.not(toCond(ce.getCondition(), s));
        return op == BinaryExpr.Operator.NOT_EQUALS ? nonNull : Cond.not(nonNull);
    }

    private Object constant(Expression e, ConfigModel.Scope s) {
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof NameExpr owner
                && index.isEnum(owner.getNameAsString())) {
            return fa.getNameAsString();
        }
        if (e instanceof BooleanLiteralExpr b) return b.getValue();
        if (e instanceof StringLiteralExpr str) return str.asString();
        if (e instanceof IntegerLiteralExpr || e instanceof DoubleLiteralExpr
                || e instanceof BinaryExpr || e instanceof NameExpr || e instanceof FieldAccessExpr
                || e instanceof UnaryExpr) {
            ClassOrInterfaceDeclaration ctx = e.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
            return index.evalNumber(e, ctx);
        }
        return null;
    }

    private boolean isBoolean(String field) {
        String t = config.fields.get(field).typeName();
        return t.equals("boolean") || t.equals("Boolean");
    }

    private static String symbol(BinaryExpr.Operator op) {
        return switch (op) {
            case EQUALS -> "==";
            case NOT_EQUALS -> "!=";
            case LESS -> "<";
            case LESS_EQUALS -> "<=";
            case GREATER -> ">";
            case GREATER_EQUALS -> ">=";
            default -> op.asString();
        };
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
}
