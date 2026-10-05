package com.wastesim.templategen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 명령행 진입점.
 *
 * <pre>
 * --source    시뮬레이터 소스 루트            (필수)
 * --config    설정 클래스                     (필수)
 * --validator 검증 클래스, 쉼표로 여럿        (필수)
 * --engine    엔진 클래스, 쉼표로 여럿 — 첫 클래스의 public 메서드가 진입점 (선택, 주면 생성 조건을 뽑는다)
 * --out       생성한 템플릿 JSON 경로         (필수)
 * --compare   대조할 손으로 쓴 템플릿 JSON    (선택)
 * --report    대조 결과 Markdown 경로         (선택, --compare 와 함께)
 * --conditions 손으로 쓴 생성 조건 이름 → 조건식 사전 JSON (선택, --compare 와 함께)
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        Path source = Path.of(require(a, "source"));
        TemplateGenerator gen = new TemplateGenerator(source);
        List<String> engine = a.containsKey("engine") ? Arrays.asList(a.get("engine").split(",")) : List.of();
        TemplateGenerator.Result result = gen.generate(require(a, "config"),
                Arrays.asList(require(a, "validator").split(",")), engine);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path out = Path.of(require(a, "out"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        mapper.writeValue(out.toFile(), TemplateJson.toJson(result, source, a.get("config")));

        PrintStream console = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        console.println("템플릿 초안 " + result.templates().size() + "개 → " + out
                + " (옮기지 못한 필드 " + result.skipped().size() + "개)");

        if (a.containsKey("compare")) {
            JsonNode hand = mapper.readTree(Path.of(a.get("compare")).toFile());
            Map<String, String> dictionary = null;
            if (a.containsKey("conditions")) {
                dictionary = new LinkedHashMap<>();
                JsonNode d = mapper.readTree(Path.of(a.get("conditions")).toFile()).path("conditions");
                for (var it = d.fields(); it.hasNext(); ) {
                    var e = it.next();
                    dictionary.put(e.getKey(), e.getValue().asText());
                }
            }
            TemplateComparator.Report report = new TemplateComparator().compare(hand, result, dictionary);
            String md = ComparisonMarkdown.render(report, a.get("compare"), out.toString());
            if (a.containsKey("report")) {
                Path rp = Path.of(a.get("report"));
                if (rp.getParent() != null) Files.createDirectories(rp.getParent());
                Files.writeString(rp, md, StandardCharsets.UTF_8);
                console.println("대조 결과 → " + rp);
            }
            console.printf("채점 칸 %d/%d 일치 · 손으로 쓴 값이 있는 칸만 %d/%d%n",
                    report.matched(), report.total(), report.matchedWithValue(), report.totalWithValue());
            if (!report.conditions().isEmpty()) {
                console.printf("생성 조건 %d개 중 같음 %d · 더 넓음 %d · 더 좁음 %d · 다름 %d%n",
                        report.conditions().size(),
                        report.conditionsMatching(Truth.Relation.EQUIVALENT),
                        report.conditionsMatching(Truth.Relation.WEAKER),
                        report.conditionsMatching(Truth.Relation.STRONGER),
                        report.conditionsMatching(Truth.Relation.DIFFERENT));
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> list = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < list.size(); i++) {
            String k = list.get(i);
            if (!k.startsWith("--") || i + 1 >= list.size()) {
                throw new IllegalArgumentException("인자는 --이름 값 쌍이어야 합니다: " + k);
            }
            m.put(k.substring(2), list.get(++i));
        }
        return m;
    }

    private static String require(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("--" + k + " 가 필요합니다");
        return v;
    }
}
