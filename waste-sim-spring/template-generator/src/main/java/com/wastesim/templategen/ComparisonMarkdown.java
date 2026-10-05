package com.wastesim.templategen;

import java.util.List;
import java.util.Map;

/** 대조 결과를 사람이 읽는 Markdown 으로. */
final class ComparisonMarkdown {

    private ComparisonMarkdown() {
    }

    static String render(TemplateComparator.Report r, String handPath, String generatedPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 템플릿 자동 생성 대조 결과\n\n");
        sb.append("- 손으로 쓴 템플릿: `").append(handPath.replace('\\', '/')).append("`\n");
        sb.append("- 생성한 초안: `").append(generatedPath.replace('\\', '/')).append("`\n\n");

        sb.append("## 요약\n\n");
        sb.append("| 항목 | 값 |\n|---|---|\n");
        sb.append("| 손으로 쓴 템플릿 | ").append(r.rows().size()).append("개 |\n");
        sb.append("| 짝을 찾은 템플릿 | ").append(r.rows().stream().filter(TemplateComparator.Row::found).count())
                .append("개 |\n");
        sb.append(String.format("| 채점 칸 일치 | %d / %d (%s) |%n", r.matched(), r.total(), pct(r.matched(), r.total())));
        sb.append(String.format("| 손으로 쓴 값이 있는 칸만 | %d / %d (%s) |%n",
                r.matchedWithValue(), r.totalWithValue(), pct(r.matchedWithValue(), r.totalWithValue())));
        sb.append("| 생성 조건이 ALWAYS 가 아닌 템플릿 (뽑지 않음) | ").append(r.conditional()).append("개 |\n");
        sb.append("| 코드에는 있으나 노출하지 않은 후보 | ").append(r.notExposed().size()).append("개 |\n");
        sb.append("| 템플릿으로 옮기지 못한 필드 | ").append(r.skipped().size()).append("개 |\n\n");
        sb.append("채점 칸: ").append(String.join(" · ", TemplateComparator.SCORED))
                .append(". 질문 문장 · SES 경로 · 노드 종류는 자유 서술이라 채점하지 않는다.\n\n");

        sb.append("## 칸별 일치\n\n| 칸 | 일치 | 값이 있는 칸 중 일치 |\n|---|---|---|\n");
        for (String f : TemplateComparator.SCORED) {
            long all = r.rows().size();
            long ok = r.rows().stream().flatMap(x -> x.cells().stream())
                    .filter(c -> c.field().equals(f) && c.match()).count();
            long withV = r.rows().stream().flatMap(x -> x.cells().stream())
                    .filter(c -> c.field().equals(f) && c.handHasValue()).count();
            long okV = r.rows().stream().flatMap(x -> x.cells().stream())
                    .filter(c -> c.field().equals(f) && c.handHasValue() && c.match()).count();
            sb.append("| ").append(f).append(" | ").append(ok).append("/").append(all)
                    .append(" | ").append(okV).append("/").append(withV).append(" |\n");
        }

        sb.append("\n## 템플릿별 어긋난 칸\n\n| 템플릿 | 설정 필드 | 칸 | 손으로 쓴 값 | 생성한 값 |\n|---|---|---|---|---|\n");
        for (TemplateComparator.Row row : r.rows()) {
            if (!row.found()) {
                sb.append("| ").append(row.templateId()).append(" | `").append(row.configField())
                        .append("` | (전체) | — | 짝 없음 |\n");
                continue;
            }
            for (TemplateComparator.Cell c : row.cells()) {
                if (c.match()) continue;
                sb.append("| ").append(row.templateId()).append(" | `").append(row.configField())
                        .append("` | ").append(c.field()).append(" | ").append(show(c.hand()))
                        .append(" | ").append(show(c.generated())).append(" |\n");
            }
        }

        sb.append("\n## 노출하지 않은 후보\n\n");
        sb.append("코드에서는 세터가 있어 정할 수 있지만 손으로 쓴 템플릿에 없는 필드다. ")
                .append("어느 것을 사용자에게 물을지는 제공자가 고른다.\n\n");
        sb.append(r.notExposed().isEmpty() ? "없음\n" : "`" + String.join("` · `", r.notExposed()) + "`\n");

        sb.append("\n## 옮기지 못한 필드\n\n");
        if (r.skipped().isEmpty()) {
            sb.append("없음\n");
        } else {
            for (Map.Entry<String, String> e : r.skipped().entrySet()) {
                sb.append("- `").append(e.getKey()).append("` — ").append(e.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    private static String pct(int a, int b) {
        return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b);
    }

    private static String show(Object v) {
        if (v == null) return "null";
        if (v instanceof List<?> l && l.isEmpty()) return "[]";
        if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return v.toString().replace("|", "\\|");
    }
}
