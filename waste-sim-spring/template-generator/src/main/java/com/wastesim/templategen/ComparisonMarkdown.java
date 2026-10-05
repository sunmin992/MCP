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
        if (r.conditions().isEmpty()) {
            sb.append("| 생성 조건이 ALWAYS 가 아닌 템플릿 (뽑지 않음) | ").append(r.conditional()).append("개 |\n");
        } else {
            long same = r.conditionsMatching(Truth.Relation.EQUIVALENT);
            sb.append(String.format("| 생성 조건 같음 | %d / %d (%s) |%n", same, r.conditions().size(),
                    pct((int) same, r.conditions().size())));
        }
        sb.append("| 코드에는 있으나 노출하지 않은 후보 | ").append(r.notExposed().size()).append("개 |\n");
        sb.append("| 템플릿으로 옮기지 못한 필드 | ").append(r.skipped().size()).append("개 |\n\n");
        sb.append("채점 칸: ").append(String.join(" · ", TemplateComparator.SCORED))
                .append(". 질문 문장 · SES 경로 · 노드 종류는 자유 서술이라 채점하지 않는다. ")
                .append("생성 조건은 칸 점수에 넣지 않고 아래에서 따로 센다.\n\n");

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

        if (!r.conditions().isEmpty()) renderConditions(sb, r);

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

    private static void renderConditions(StringBuilder sb, TemplateComparator.Report r) {
        sb.append("\n## 생성 조건\n\n");
        sb.append("손으로 쓴 조건 이름을 조건 사전으로 식으로 바꾸고, 엔진에서 뽑은 식과 진리표로 맞댄다. ")
                .append("`?{…}` 는 설정만으로 정해지지 않는 실행 조건이며 \"참일 수 있다\" 로 읽는다.\n\n");
        sb.append("| 관계 | 개수 | 뜻 |\n|---|---|---|\n");
        sb.append("| 같음 | ").append(r.conditionsMatching(Truth.Relation.EQUIVALENT))
                .append(" | 손으로 쓴 조건과 같다 |\n");
        sb.append("| 더 넓음 | ").append(r.conditionsMatching(Truth.Relation.WEAKER))
                .append(" | 물어야 할 때는 빠짐없이 묻지만, 안 물어도 될 때도 묻는다 |\n");
        sb.append("| 더 좁음 | ").append(r.conditionsMatching(Truth.Relation.STRONGER))
                .append(" | 물어야 할 때 빠뜨릴 수 있다 |\n");
        sb.append("| 다름 | ").append(r.conditionsMatching(Truth.Relation.DIFFERENT))
                .append(" | 어느 쪽도 포함하지 않는다 |\n\n");

        sb.append("| 템플릿 | 손으로 쓴 조건 | 손으로 쓴 식 | 뽑은 식 | 관계 |\n|---|---|---|---|---|\n");
        for (TemplateComparator.ConditionRow c : r.conditions()) {
            sb.append("| ").append(c.templateId()).append(" | ").append(c.handName())
                    .append(" | `").append(c.handExpr()).append("` | `").append(cell(c.generatedExpr()))
                    .append("` | ").append(label(c.relation())).append(" |\n");
        }

        boolean anyDetail = r.conditions().stream().anyMatch(c -> c.relation() != Truth.Relation.EQUIVALENT);
        if (!anyDetail) return;
        sb.append("\n### 같지 않은 것\n\n");
        for (TemplateComparator.ConditionRow c : r.conditions()) {
            if (c.relation() == Truth.Relation.EQUIVALENT) continue;
            sb.append("- **").append(c.templateId()).append("** (").append(label(c.relation())).append(")");
            if (c.counterexample() != null) sb.append(" — 반례 `").append(cell(c.counterexample())).append("`");
            sb.append("\n");
            for (String h : c.hints()) sb.append("  - 실행 조건: `").append(cell(h)).append("`\n");
        }
    }

    private static String label(Truth.Relation r) {
        return switch (r) {
            case EQUIVALENT -> "같음";
            case WEAKER -> "더 넓음";
            case STRONGER -> "더 좁음";
            case DIFFERENT -> "다름";
        };
    }

    /** 표 칸에 넣을 수 있게 — 세로줄과 백틱을 피하고 너무 길면 자른다. */
    private static String cell(String s) {
        String t = s.replace("|", "\\|").replace("`", "'");
        return t.length() > 220 ? t.substring(0, 217) + "…" : t;
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
