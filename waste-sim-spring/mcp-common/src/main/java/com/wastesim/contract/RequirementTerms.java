package com.wastesim.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.evidence.UnitEvidence;
import java.util.*;

/**
 * 공통 요구의 사용자 표현을 한 제공자의 이름으로 대응시킨다. LLM 이 아니라 카드의 {@code capabilities.terms} 로 하는 규칙이다.
 *
 * <p>LLM 은 시뮬레이터마다 다른 지표 · 설정 이름을 몰라도 된다. 결과는 사용자 말 그대로("평균 민원 수"), 수치 조건은
 * {@code {"name": "배차 간격", "value": 1, "unit": "시간"}} 처럼 받는다. 이 클래스가 카드의 표현 목록에서 가장 길게 맞는
 * 이름을 고르고, 단위를 카드가 선언한 단위로 바꾼다. 같은 입력과 같은 카드면 늘 같은 결과다 — 그래서 제공자가 낸 예문으로
 * 등록할 때 검사할 수 있다({@link #selfCheck}).
 *
 * <pre>
 * "terms": {
 *   "outputs":     { "meanComplaints": ["평균 민원 수", "민원 수"] },
 *   "constraints": { "dispatchIntervalMinutes": {"terms": ["배차 간격"], "unit": "minute", "unitWords": []} },
 *   "examples":    [ {"output": "민원 건수", "expect": "meanComplaints"},
 *                    {"constraint": {"name": "배차 간격", "value": 1, "unit": "시간"}, "expect": {"dispatchIntervalMinutes": 60}},
 *                    {"output": "예산", "expect": null} ]
 * }
 * </pre>
 *
 * <p>대응되지 않는 표현은 버리지 않는다. 카드의 미지원 표현에 맞으면 UNSATISFIED, 아무 데도 맞지 않으면 UNKNOWN 으로 남겨
 * 브로커가 사용자에게 묻거나 다른 후보를 보게 한다. 제공자 이름을 그대로 보낸 옛 요구도 그대로 받는다.
 */
public final class RequirementTerms {
    private RequirementTerms() {}

    public record Resolution(JsonNode requirements, List<RequirementContract.Check> issues) {}

    public static Resolution resolve(JsonNode request, JsonNode card) {
        ObjectNode out = (ObjectNode) RequirementContract.validate(request);
        List<RequirementContract.Check> issues = new ArrayList<>();
        JsonNode caps = card.path("capabilities");
        JsonNode terms = caps.path("terms");
        if (out.has("requiredOutputs")) {
            ArrayNode names = JsonNodeFactory.instance.arrayNode();
            Set<String> declared = strings(caps.path("outputs"));
            for (JsonNode p : out.get("requiredOutputs")) {
                String said = p.asText();
                if (declared.contains(said)) { if (!contains(names, said)) names.add(said); continue; }
                Match m = match(said, outputTerms(terms));
                if (m.key != null) { if (!contains(names, m.key)) names.add(m.key); }
                else issues.add(unmatched("requiredOutputs." + said, said, card, m.ambiguous, true));
            }
            out.set("requiredOutputs", names);
        }
        for (String group : List.of("constraints", "preferences")) {
            if (!out.path(group).isArray()) continue;
            boolean required = group.equals("constraints");
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            for (JsonNode c : out.get(group)) {
                String name = c.path("name").asText();
                String unit = c.path("unit").asText(null);
                String label = group + "." + name;
                Match m = match(name, constraintTerms(terms));
                if (m.key == null) { issues.add(unmatched(label, name, card, m.ambiguous, required)); continue; }
                JsonNode spec = terms.path("constraints").path(m.key);
                Set<String> accepted = new HashSet<>(strings(spec.path("unitWords")));
                accepted.addAll(UnitEvidence.words(spec.path("unit").asText(null)));
                if (unit != null && !unit.isBlank() && !accepted.isEmpty() && !accepted.contains(unit)) {
                    issues.add(new RequirementContract.Check(label, c, spec, "UNSATISFIED", "UNIT_MISMATCH",
                            "ASK_USER", required));
                    continue;
                }
                fields.put(m.key, convert(c.path("value").asDouble(), unit, spec.path("unit").asText(null)));
            }
            out.set(group, fields);
        }
        return new Resolution(out, List.copyOf(issues));
    }

    /** 사용자 단위말을 카드가 선언한 단위로 바꾼다. 바꿀 수 없는 조합이면 그대로 둔다. */
    static double convert(double value, String said, String unit) {
        if (said == null) return value;
        return switch (UnitEvidence.canonical(unit)) {
            case "minute" -> List.of("시간", "시", "hour", "hours").contains(said) ? value * 60 : value;
            case "day" -> List.of("주", "week", "weeks").contains(said) ? value * 7 : value;
            case "ratio" -> List.of("%", "퍼센트", "프로").contains(said) ? value / 100 : value;
            case "kg" -> List.of("톤", "t").contains(said) ? value * 1000 : value;
            case "won" -> said.equals("만원") ? value * 10000 : said.equals("천원") ? value * 1000 : value;
            default -> value;
        };
    }

    /** 카드의 예문을 돌려 본다. 어긋난 예문마다 사유 한 줄. 예문이 없으면 빈 목록. */
    public static List<String> selfCheck(JsonNode card) {
        List<String> failures = new ArrayList<>();
        for (JsonNode ex : card.path("capabilities").path("terms").path("examples")) {
            ObjectNode req = JsonNodeFactory.instance.objectNode();
            if (ex.has("output")) req.putArray("requiredOutputs").add(ex.get("output").asText());
            else if (ex.has("constraint")) req.putArray("constraints").add(ex.get("constraint"));
            else { failures.add("예문에 output 이나 constraint 가 없습니다: " + ex); continue; }
            Resolution r = resolve(req, card);
            JsonNode expect = ex.get("expect");
            boolean ok;
            if (expect == null || expect.isNull()) ok = !r.issues().isEmpty();
            else if (ex.has("output")) ok = r.issues().isEmpty() && r.requirements().path("requiredOutputs").size() == 1
                    && r.requirements().path("requiredOutputs").get(0).asText().equals(expect.asText());
            else {
                JsonNode got = r.requirements().path("constraints");
                ok = r.issues().isEmpty() && got.size() == expect.size();
                for (var it = expect.fields(); ok && it.hasNext(); ) {
                    var e = it.next();
                    ok = got.has(e.getKey()) && Math.abs(got.get(e.getKey()).asDouble() - e.getValue().asDouble()) < 1e-9;
                }
            }
            if (!ok) failures.add("예문 " + ex + " → " + r.requirements().path(ex.has("output") ? "requiredOutputs" : "constraints")
                    + (r.issues().isEmpty() ? "" : " " + r.issues().stream().map(RequirementContract.Check::code).toList()));
        }
        return failures;
    }

    /** 카드의 terms 형식 검사. 이름은 capabilities.outputs · constraints 에 있어야 한다. */
    static void validate(JsonNode caps) {
        JsonNode terms = caps.path("terms");
        if (terms.isMissingNode()) return;
        Set<String> outputs = strings(caps.path("outputs"));
        terms.path("outputs").fields().forEachRemaining(e -> {
            if (!outputs.contains(e.getKey())) throw new IllegalArgumentException("terms.outputs 에 선언되지 않은 지표: " + e.getKey());
            if (!e.getValue().isArray()) throw new IllegalArgumentException("terms.outputs." + e.getKey() + " 는 표현 목록이어야 합니다");
        });
        terms.path("constraints").fields().forEachRemaining(e -> {
            if (!caps.path("constraints").has(e.getKey()))
                throw new IllegalArgumentException("terms.constraints 에 선언되지 않은 조건: " + e.getKey());
            if (!e.getValue().path("terms").isArray())
                throw new IllegalArgumentException("terms.constraints." + e.getKey() + ".terms 가 없습니다");
        });
        List<String> failures = selfCheck(JsonNodeFactory.instance.objectNode().set("capabilities", caps));
        if (!failures.isEmpty()) throw new IllegalArgumentException("능력 카드 예문 검사 실패: " + failures);
    }

    private record Match(String key, boolean ambiguous) {}

    /** 가장 길게 맞는 표현의 이름. 표현 길이가 같은데 이름이 다르면 모호하다. 이름 자체도 표현으로 본다. */
    private static Match match(String said, Map<String, List<String>> terms) {
        String q = squash(said);
        String best = null;
        int len = 0;
        boolean tie = false;
        for (var e : terms.entrySet()) {
            List<String> all = new ArrayList<>(e.getValue());
            all.add(e.getKey());
            for (String t : all) {
                String s = squash(t);
                if (s.isEmpty() || !q.contains(s)) continue;
                if (s.length() > len) { best = e.getKey(); len = s.length(); tie = false; }
                else if (s.length() == len && !e.getKey().equals(best)) tie = true;
            }
        }
        return tie ? new Match(null, true) : new Match(best, false);
    }

    private static Map<String, List<String>> outputTerms(JsonNode terms) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        terms.path("outputs").fields().forEachRemaining(e -> out.put(e.getKey(), new ArrayList<>(strings(e.getValue()))));
        return out;
    }

    private static Map<String, List<String>> constraintTerms(JsonNode terms) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        terms.path("constraints").fields().forEachRemaining(e -> out.put(e.getKey(), new ArrayList<>(strings(e.getValue().path("terms")))));
        return out;
    }

    private static RequirementContract.Check unmatched(String field, String said, JsonNode card, boolean ambiguous, boolean required) {
        JsonNode text = JsonNodeFactory.instance.textNode(said);
        for (JsonNode u : card.path("unsupported"))
            for (JsonNode k : u.path("matchKeys"))
                if (!k.asText().contains(":") && squash(said).contains(squash(k.asText())))
                    return new RequirementContract.Check(field, text, u.path("capability"), "UNSATISFIED", "TERM_UNSUPPORTED",
                            "CHANGE_REQUEST_OR_PROVIDER", required);
        return new RequirementContract.Check(field, text, card.path("capabilities").path("terms"), "UNKNOWN",
                ambiguous ? "TERM_AMBIGUOUS" : "TERM_UNMATCHED", "ASK_USER_OR_PROVIDER", required);
    }

    private static boolean contains(ArrayNode a, String s) {
        for (JsonNode n : a) if (n.asText().equals(s)) return true;
        return false;
    }

    private static Set<String> strings(JsonNode a) {
        Set<String> out = new LinkedHashSet<>();
        if (a.isArray()) a.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\p{P}]+", "").toLowerCase(Locale.ROOT);
    }
}
