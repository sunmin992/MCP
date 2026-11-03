package com.wastesim.template;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.wastesim.model.SimulationConfig;
import com.wastesim.pes.*;
import com.wastesim.ses.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * R3: 같은 변경 요청을 SES 명세와 Java 수기 구성({@link ManualConfigurator})에 적용하고 비용과 일관성을 잰다.
 *
 * <p>비용 = 고친 위치 수, 바뀐 줄 수, Java 재컴파일 필요 여부. 일관성 = 변경 의도(오라클)와 일곱 소비자 —
 * 구조 표시, 준비 판정, 질문, 잘못 남은 입력 거절, 범위 거절, 능력 카드, Task 묶음, 설정 반영 — 의 불일치 수.
 * primary(규칙만 고침)와 complete(함께 고칠 곳까지 고침)를 따로 잰다. 기본 *Test 실행에서 빠진다.
 * <pre>.\mvnw.cmd -B -pl simulator -am test '-Dtest=SesChangeCostExperiment' '-Dsurefire.failIfNoSpecifiedTests=false'</pre>
 */
class SesChangeCostExperiment {
    static final List<String> KEYS = List.of("dispatchIntervalMinutes", "trafficProfileId", "zoneAssignmentRule", "intraZoneTravelMinutes");
    static final Path OUT = Path.of("../docs/reports/ses-change-cost-2026-11-03");
    private final ObjectMapper json = new ObjectMapper();

    // ── 변경 의도(오라클). 사례마다 바뀌는 규칙만 다르다. ──
    static Boolean oracleActive(String c, String key, Map<String, Object> v) {
        int nB = ((Number) v.get("numBuildings")).intValue(), trucks = ((Number) v.get("truckCount")).intValue();
        boolean zoneTravel = "ZONE_PROXY_HYBRID".equals(v.get("travelTimeMode"));
        boolean zone = zoneTravel && (c.equals("C3") || nB > 4);
        return switch (key) {
            case "dispatchIntervalMinutes" -> Math.min(nB, trucks) >= (c.equals("C1") ? 3 : 2);
            case "trafficProfileId" -> "APPLY".equals(v.get("trafficMode"));
            case "zoneAssignmentRule" -> zone;
            case "intraZoneTravelMinutes" -> zone && !v.containsKey("zoneAssignmentRule") ? null
                    : zone && "CONTIGUOUS".equals(v.get("zoneAssignmentRule"));
            case "serviceMinutesPerSite" -> zoneTravel;
            default -> throw new IllegalArgumentException(key);
        };
    }
    static int oracleDispatchMax(String c) { return c.equals("C4") ? 120 : 1439; }
    /** 사례가 바꾼 결정의 카드 선언 기대값. 카드가 원래 조건을 적지 않는 결정(구역 배정)은 기대값이 없다. */
    static Map<String, Map<String, Object>> oracleCard(String c) {
        return switch (c) {
            case "C1" -> Map.of("dispatchIntervalMinutes", Map.of("generateWhen", "numBuildings >= 3 && truckCount >= 3"));
            case "C2" -> Map.of("serviceMinutesPerSite", Map.of("generateWhen", "travelTimeMode == ZONE_PROXY_HYBRID", "max", 60));
            case "C4" -> Map.of("dispatchIntervalMinutes", Map.of("max", 120));
            default -> Map.of();
        };
    }

    // ── 두 방식이 같은 질문에 답하는 공통 창구 ──
    interface View {
        Boolean active(String key, Map<String, Object> v);          // 구조 표시 · 판정 불가면 null
        boolean asksFor(String key, Map<String, Object> v);         // 준비 판정 + 질문
        boolean rejectsStale(String key, Map<String, Object> v);    // 잘못 남은 입력
        boolean rejectsRange(String key, Map<String, Object> v);    // 범위 위반
        Map<String, Object> card(String key);                       // 능력 카드 선언
        boolean inTask(String key);                                 // Task 묶음
        Object flattened(String key, Map<String, Object> v);        // 설정 반영값
    }

    class SesView implements View {
        final SubtaskPlanner planner; final PesFlattener flattener; final TemplateCatalog catalog;
        final JsonNode digest; final JsonNode manifest;
        SesView(ObjectNode ses, JsonNode manifest, String tag) throws Exception {
            var tree = SesTree.parse(ses);
            Path dir = Path.of(TemplateCatalog.class.getResource("/ses/jangnyang-templates.json").toURI()).getParent();
            Files.writeString(dir.resolve("r3-" + tag + ".json"), json.writeValueAsString(SesDerivation.templates(tree)));
            catalog = new TemplateCatalog("/ses/r3-" + tag + ".json");
            planner = new SubtaskPlanner(catalog, new AnswerNormalizer());
            flattener = new PesFlattener(catalog);
            digest = SesDerivation.digest(tree);
            this.manifest = manifest;
        }
        SubtaskStatus status(String key, Map<String, Object> v) {
            return planner.plan(v).instances().stream().filter(i -> i.answerKey().equals(key)).findFirst()
                    .map(SubtaskInstance::status).orElse(SubtaskStatus.NOT_GENERATED);
        }
        public Boolean active(String key, Map<String, Object> v) {
            var s = status(key, v);
            return s == SubtaskStatus.DEFERRED ? null : s != SubtaskStatus.NOT_GENERATED;
        }
        public boolean asksFor(String key, Map<String, Object> v) {
            var i = planner.plan(v).instances().stream().filter(x -> x.answerKey().equals(key)).findFirst();
            return i.isPresent() && i.get().status() == SubtaskStatus.UNFILLED && i.get().reason() == null
                    && !catalog.byAnswerKey(key).orElseThrow().question().isBlank();
        }
        public boolean rejectsStale(String key, Map<String, Object> v) { return v.containsKey(key) && status(key, v) == SubtaskStatus.NOT_GENERATED; }
        public boolean rejectsRange(String key, Map<String, Object> v) {
            return planner.plan(v).instances().stream().anyMatch(i -> i.answerKey().equals(key)
                    && i.status() == SubtaskStatus.UNFILLED && i.reason() != null);
        }
        public Map<String, Object> card(String key) {
            for (JsonNode p : digest.path("decisionPoints"))
                if (p.path("field").asText().equals(key)) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    if (p.has("generateWhen")) c.put("generateWhen", p.get("generateWhen").asText());
                    if (p.path("range").has("max")) c.put("max", p.path("range").get("max").asInt());
                    return c;
                }
            return Map.of();
        }
        public boolean inTask(String key) {
            for (JsonNode t : manifest.path("tasks")) for (JsonNode s : t.path("subtasks")) if (s.asText().equals(key)) return true;
            return false;
        }
        public Object flattened(String key, Map<String, Object> v) {
            Map<String, String> origins = new LinkedHashMap<>();
            v.keySet().forEach(k -> origins.put(k, "USER"));
            try { return getter(flattener.flatten(new Pes(catalog.sesId(), catalog.sesVersion(), v, origins)), key); }
            catch (RuntimeException e) { return "ERROR: " + e.getMessage(); }
        }
    }

    static class ManualView implements View {
        final ManualConfigurator m;
        ManualView(ManualConfigurator m) { this.m = m; }
        public Boolean active(String key, Map<String, Object> v) { return m.decisions().containsKey(key) ? m.active(key, v) : Boolean.FALSE; }
        public boolean asksFor(String key, Map<String, Object> v) {
            return Boolean.TRUE.equals(active(key, v)) && !v.containsKey(key) && m.questions().getOrDefault(key, "").length() > 0;
        }
        public boolean rejectsStale(String key, Map<String, Object> v) { return v.containsKey(key) && Boolean.FALSE.equals(active(key, v)); }
        public boolean rejectsRange(String key, Map<String, Object> v) { return v.containsKey(key) && m.outOfRange(key, v.get(key)); }
        public Map<String, Object> card(String key) { return m.card().getOrDefault(key, Map.of()); }
        public boolean inTask(String key) { return m.taskGroups().values().stream().anyMatch(l -> l.contains(key)); }
        public Object flattened(String key, Map<String, Object> v) {
            var cfg = new SimulationConfig();
            m.flatten(cfg, v);
            return getter(cfg, key);
        }
    }

    static Object getter(SimulationConfig cfg, String key) {
        try { return SimulationConfig.class.getMethod("get" + Character.toUpperCase(key.charAt(0)) + key.substring(1)).invoke(cfg); }
        catch (ReflectiveOperationException e) { return "ERROR: " + e.getMessage(); }
    }

    // ── 탐침 입력 ──
    static Map<String, Object> preset() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("truckType", "SMALL_1TON"); v.put("truckCount", 1); v.put("numBuildings", 4); v.put("residentsPerBuilding", 25);
        v.put("days", 2); v.put("seeds", 1); v.put("trafficMode", "IGNORE"); v.put("travelTimeMode", "LEGACY_CONSTANT");
        v.put("occupationMix", List.of("Student")); v.put("routeAvailableCapacityKg", 150); v.put("binCapacityKg", 30.0);
        v.put("complaintThreshold", 0.8); v.put("dischargeTimeMode", "PAPER_BASELINE"); v.put("collectionTimeMinutes", 720);
        return v;
    }
    static List<Map<String, Object>> probes(boolean withService) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int nB : List.of(1, 2, 3, 4, 5, 8)) for (int trucks : List.of(1, 2, 3))
            for (String traffic : List.of("IGNORE", "APPLY")) for (String travel : List.of("LEGACY_CONSTANT", "ZONE_PROXY_HYBRID"))
                for (String zone : Arrays.asList(null, "CONTIGUOUS", "ROUND_ROBIN"))
                    for (Integer dispatch : Arrays.asList(null, 10, 200)) {
                        var v = preset();
                        v.put("numBuildings", nB); v.put("truckCount", trucks); v.put("trafficMode", traffic); v.put("travelTimeMode", travel);
                        if (zone != null) v.put("zoneAssignmentRule", zone);
                        if (dispatch != null) {          // 나머지 조건부 입력도 함께 준다
                            v.put("dispatchIntervalMinutes", dispatch);
                            v.put("trafficProfileId", "jangryang-weekday");
                            v.put("intraZoneTravelMinutes", 3);
                            if (withService) v.put("serviceMinutesPerSite", 5);
                        }
                        out.add(v);
                    }
        return out;
    }

    /** 오라클과 어긋난 항목을 소비자별로 센다. 사례가 건드리지 않은 결정도 함께 본다(부작용 확인). */
    Map<String, Integer> inconsistencies(String c, View view) {
        List<String> keys = new ArrayList<>(KEYS);
        if (c.equals("C2")) keys.add("serviceMinutesPerSite");
        Map<String, Integer> err = new TreeMap<>();
        for (var v : probes(c.equals("C2"))) for (String key : keys) {
            Boolean want = oracleActive(c, key, v);
            if (want == null) continue;
            if (!want.equals(view.active(key, v))) err.merge("structure", 1, Integer::sum);
            if ((want && !v.containsKey(key)) != view.asksFor(key, v)) err.merge("readinessQuestion", 1, Integer::sum);
            if ((!want && v.containsKey(key)) != view.rejectsStale(key, v)) err.merge("staleInput", 1, Integer::sum);
            if (key.equals("dispatchIntervalMinutes") && want && v.containsKey(key)) {
                boolean out = ((Number) v.get(key)).intValue() > oracleDispatchMax(c);
                if (out != view.rejectsRange(key, v)) err.merge("range", 1, Integer::sum);
                if (!out && !Objects.equals(v.get(key), view.flattened(key, v))) err.merge("flatten", 1, Integer::sum);
            }
            if (key.equals("serviceMinutesPerSite") && want && v.containsKey(key)
                    && !Objects.equals(v.get(key), view.flattened(key, v))) err.merge("flatten", 1, Integer::sum);
        }
        oracleCard(c).forEach((key, want) -> want.forEach((k, value) -> {
            if (!Objects.equals(value, view.card(key).get(k))) err.merge("card", 1, Integer::sum);
        }));
        for (String key : keys) if (!view.inTask(key)) err.merge("taskGroup", 1, Integer::sum);
        return err;
    }

    // ── 비용 측정 ──
    static int lineDiff(List<String> a, List<String> b) {
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) for (int j = b.size() - 1; j >= 0; j--)
            lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        return a.size() + b.size() - 2 * lcs[0][0];
    }
    static List<String> codeLines(String s) {
        return s.lines().map(String::strip).map(l -> l.replace("@Override ", ""))
                .filter(l -> !l.isEmpty() && !l.startsWith("//") && !l.equals("@Override")).toList();
    }
    static Map<String, String> sites(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("// <site:(\\w+)>(.*?)// </site>", Pattern.DOTALL).matcher(source);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }
    static String variantBlock(String source, String id) {
        Matcher m = Pattern.compile("// <variant:" + Pattern.quote(id) + ">(.*?)// </variant>", Pattern.DOTALL).matcher(source);
        return m.find() ? m.group(1) : "";
    }
    Map<String, Object> manualCost(String c, String mode) throws Exception {
        String base = Files.readString(Path.of("src/test/java/com/wastesim/template/ManualConfigurator.java"));
        String variants = Files.readString(Path.of("src/test/java/com/wastesim/template/ManualVariants.java"));
        Map<String, String> baseSites = sites(base), changed = new LinkedHashMap<>();
        List<String> blocks = c.equals("C3") ? List.of("C3") : mode.equals("primary") ? List.of(c + "-primary")
                : List.of(c + "-primary", c + "-complete");
        for (String b : blocks) changed.putAll(sites(variantBlock(variants, b)));
        int lines = 0;
        for (var e : changed.entrySet())
            lines += lineDiff(codeLines(baseSites.getOrDefault(e.getKey(), "")), codeLines(e.getValue()));
        return Map.of("sites", changed.keySet(), "siteCount", changed.size(), "changedLines", lines,
                "files", List.of("ManualConfigurator(Java)"), "javaRecompile", true);
    }

    ObjectNode resource(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) { return (ObjectNode) json.readTree(in); }
    }
    static JsonNode resolveDecision(ObjectNode ses, String path, List<String> rest) {
        String key = path.substring("decision:".length()).split("/")[0];
        for (JsonNode d : ses.get("decisions")) if (d.path("answerKey").asText().equals(key)) return d;
        throw new IllegalArgumentException("결정 없음: " + key);
    }
    static void apply(ObjectNode root, JsonNode op) {
        String path = op.get("path").asText();
        JsonNode node = root;
        List<String> parts;
        if (path.startsWith("decision:")) {
            node = resolveDecision(root, path, null);
            parts = new ArrayList<>(List.of(path.substring(path.indexOf('/') + 1).split("/")));
        } else parts = new ArrayList<>(List.of(path.substring(1).split("/")));
        String last = parts.remove(parts.size() - 1);
        for (String p : parts) node = node.isArray() ? node.get(Integer.parseInt(p)) : node.get(p);
        if (node instanceof ArrayNode a) {
            if (last.equals("-")) a.add(op.get("value"));
            else if (op.get("op").asText().equals("remove")) a.remove(Integer.parseInt(last));
            else a.set(Integer.parseInt(last), op.get("value"));
        } else if (op.get("op").asText().equals("remove")) ((ObjectNode) node).remove(last);
        else ((ObjectNode) node).set(last, op.get("value"));
    }
    List<String> pretty(JsonNode n) throws Exception { return json.writerWithDefaultPrettyPrinter().writeValueAsString(n).lines().toList(); }

    @Test void measureChangeCostAndConsistency() throws Exception {
        // 개선 전 사례 · 결과는 cases-before.json · results-before.json 에 보존했다(카드 칸 손작성, active 없음).
        var cases = json.readTree(getClass().getResourceAsStream("/evaluation/ses-change/cases.json"));
        ObjectNode baseSes = resource("/ses/jangnyang-ses.json"), baseManifest = resource("/ses/task-manifest.json");
        var rows = json.createArrayNode();
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            for (String mode : List.of("primary", "complete")) {
                ObjectNode ses = baseSes.deepCopy(), manifest = baseManifest.deepCopy();
                List<JsonNode> ops = new ArrayList<>();
                c.get("primary").forEach(ops::add);
                if (mode.equals("complete")) c.get("complete").forEach(ops::add);
                Set<String> files = new TreeSet<>();
                for (JsonNode op : ops) {
                    apply(op.get("file").asText().equals("ses") ? ses : manifest, op);
                    files.add(op.get("file").asText().equals("ses") ? "jangnyang-ses.json" : "task-manifest.json");
                }
                int sesLines = lineDiff(pretty(baseSes), pretty(ses)) + lineDiff(pretty(baseManifest), pretty(manifest));
                var row = rows.addObject().put("case", id).put("title", c.get("title").asText()).put("mode", mode);
                var sesRow = row.putObject("ses");
                sesRow.put("editOps", ops.size()).put("changedLines", sesLines).put("javaRecompile", false);
                sesRow.set("files", json.valueToTree(files));
                sesRow.set("inconsistencies", json.valueToTree(inconsistencies(id, new SesView(ses, manifest, id + "-" + mode))));
                var manualRow = (ObjectNode) json.valueToTree(manualCost(id, mode));
                manualRow.set("inconsistencies", json.valueToTree(inconsistencies(id, new ManualView(ManualVariants.of(id + "-" + mode)))));
                row.set("manual", manualRow);
                System.out.println(row);
            }
        }
        // 기준선: 바꾸지 않은 두 방식이 오라클(기준 규칙)과 맞는지 먼저 본다.
        var baseline = json.createObjectNode();
        baseline.set("ses", json.valueToTree(inconsistencies("BASE", new SesView(baseSes, baseManifest, "base"))));
        baseline.set("manual", json.valueToTree(inconsistencies("BASE", new ManualView(new ManualConfigurator()))));
        System.out.println("baseline " + baseline);
        Files.createDirectories(OUT);
        var report = json.createObjectNode();
        report.put("probesPerCase", probes(false).size());
        report.set("baseline", baseline);
        report.set("rows", rows);
        json.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("results.json").toFile(), report);
    }
}
