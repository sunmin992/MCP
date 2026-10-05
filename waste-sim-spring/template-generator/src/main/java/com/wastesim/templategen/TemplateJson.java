package com.wastesim.templategen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 생성 결과를 손으로 쓴 템플릿 파일과 같은 모양의 JSON 으로 만든다.
 *
 * <p>같은 모양이어야 제공자가 초안을 고쳐 그대로 시뮬레이터 리소스로 쓸 수 있다. 근거와 검토
 * 항목은 {@code _extraction} 에 둔다 — 시뮬레이터의 {@code TemplateCatalog} 는 모르는 칸을
 * 무시하므로 지우지 않아도 읽힌다.
 */
final class TemplateJson {

    private TemplateJson() {
    }

    static Map<String, Object> toJson(TemplateGenerator.Result result, Path source, String configClass) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sesId", "generated");
        root.put("sesVersion", "0.0.0");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", source.toString().replace('\\', '/'));
        meta.put("configClass", configClass);
        meta.put("skipped", result.skipped());
        root.put("_generation", meta);

        List<Map<String, Object>> list = new ArrayList<>();
        for (GeneratedTemplate t : result.templates()) {
            boolean integral = "INTEGER".equals(t.valueType()) || "INTEGER_LIST".equals(t.valueType());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", t.templateId());
            m.put("answerKey", t.answerKey());
            m.put("valueType", t.valueType());
            m.put("generateWhen", t.generateWhen());
            if (!t.allowed().isEmpty()) m.put("allowed", t.allowed());
            if (t.min() != null) m.put("min", integral ? (Object) t.min().longValue() : t.min());
            if (t.max() != null) m.put("max", integral ? (Object) t.max().longValue() : t.max());
            if (t.unit() != null) m.put("unit", t.unit());
            m.put("defaultValue", t.defaultValue());
            m.put("defaultBasis", t.defaultBasis());
            m.put("question", t.question());
            m.put("configField", t.configField());
            m.put("sesPath", null);
            m.put("nodeKind", null);
            Map<String, Object> ext = new LinkedHashMap<>();
            if (t.minExclusive()) ext.put("minExclusive", true);
            if (t.maxExclusive()) ext.put("maxExclusive", true);
            ext.put("evidence", t.evidence());
            ext.put("needsReview", t.needsReview());
            m.put("_extraction", ext);
            list.add(m);
        }
        root.put("templates", list);
        return root;
    }
}
