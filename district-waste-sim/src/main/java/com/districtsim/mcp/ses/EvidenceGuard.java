package com.districtsim.mcp.ses;

import com.districtsim.template.GenerateCondition;
import com.districtsim.template.SubtaskTemplate;
import com.districtsim.template.TemplateCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근거 검사(GUARD). 출처 표시를 사용자의 실제 발화와 맞춰 본다.
 *
 * <p>출처는 LLM 이 스스로 적는다. 2026-10-30 본 실험에서 "3회 반복" 을 seeds=30 USER 로 낸 경우가
 * 31번, 묻지 않은 기본값을 낸 경우가 47번 나왔고 모두 형식·범위 검사를 통과했다. 그래서 값마다
 * 근거가 된 발화 구절을 받아, 구절이 실제 발화에 있는지와 숫자가 맞는지를 서버가 본다.
 *
 * <ul>
 *   <li>USER 값: 구절이 발화에 있어야 하고, 수치형이면 구절의 숫자로 값을 설명할 수 있어야 한다.
 *       구절에 숫자가 없거나 열거형이면 판단하지 못한 것으로 남기고 막지 않는다.</li>
 *   <li>조건부 결정의 기본값(생성 조건이 ALWAYS 가 아니고 기본값이 있는 결정): MODEL_DEFAULT 로 낼 때도
 *       사용자가 답하거나 동의한 구절이 발화에 있어야 한다. 조건이 생기면서 새로 필요해진 결정이라
 *       사용자가 존재를 모른 채 기본값이 들어가기 쉽다.</li>
 * </ul>
 *
 * <p>발화 목록은 클라이언트가 넘긴다. 없으면 검사하지 않는다.
 */
final class EvidenceGuard {

    record Result(List<String> blocks, List<String> unverified) {}

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private EvidenceGuard() {}

    /** @param submitted 답변키 → 제출한 값들(실험 변수는 비교할 값 전부) */
    static Result check(Map<String, List<Object>> submitted, Map<String, String> origins,
                        Map<String, String> evidence, List<String> utterances, TemplateCatalog catalog) {
        List<String> blocks = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        submitted.forEach((key, vals) -> {
            String origin = origins.getOrDefault(key, "MODEL_DEFAULT");
            Optional<SubtaskTemplate> t = catalog.byAnswerKey(key);
            String quote = evidence.getOrDefault(key, "").trim();
            boolean quoted = !quote.isEmpty() && found(quote, utterances);
            if ("USER".equals(origin)) {
                if (quote.isEmpty()) {
                    blocks.add(key + ": USER 값의 근거 구절(evidence)이 없습니다 — 사용자가 이 값을 말한 적이 없으면 제출을 멈추고 일반 메시지로 사용자에게 물으십시오. 같은 값을 다시 내도 같은 이유로 막힙니다");
                } else if (!quoted) {
                    blocks.add(key + ": 근거 구절 '" + quote + "' 이(가) 사용자 발화에 없습니다 — 사용자가 이 값을 말한 적이 없으면 제출을 멈추고 일반 메시지로 사용자에게 물으십시오. 같은 값을 다시 내도 같은 이유로 막힙니다");
                } else if (t.isPresent() && isNumeric(t.get())) {
                    List<Double> nums = numbers(quote);
                    if (nums.isEmpty()) unverified.add(key);
                    for (Object v : flat(vals)) {
                        if (!nums.isEmpty() && v instanceof Number n && !explains(nums, n.doubleValue(), quote, t.get()))
                            blocks.add(key + ": 사용자 발화 '" + quote + "' 와 값 " + v + " 이(가) 다릅니다 — 사용자가 말한 값으로 고치거나 다시 물으십시오");
                    }
                } else {
                    unverified.add(key);
                }
            } else if ("MODEL_DEFAULT".equals(origin) && t.isPresent() && asksBeforeDefault(t.get()) && !quoted) {
                blocks.add(key + ": 사용자에게 묻거나 동의받지 않은 기본값입니다 — 사용자에게 묻고, 받은 답이나 '기본값으로' 같은 동의 구절을 evidence 로 넣으십시오");
            }
        });
        return new Result(blocks, unverified);
    }

    static boolean asksBeforeDefault(SubtaskTemplate t) {
        return t.defaultValue() != null && t.generateWhen() != GenerateCondition.ALWAYS;
    }

    private static boolean isNumeric(SubtaskTemplate t) {
        return List.of("INTEGER", "NUMBER", "INTEGER_LIST").contains(t.valueType());
    }

    /** 구절의 숫자 n 으로 값을 설명할 수 있는가. 단위 바꿈은 시각·시간(분), 퍼센트, 주(일)만 본다. */
    private static boolean explains(List<Double> nums, double v, String quote, SubtaskTemplate t) {
        return nums.stream().anyMatch(n -> com.wastesim.readiness.NumericEvidence.explains(
                n, v, quote, t.answerKey(), t.unit()));
    }

    private static List<Double> numbers(String s) {
        List<Double> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) out.add(Double.parseDouble(m.group().replace(",", "")));
        return out;
    }

    private static List<Object> flat(List<Object> vals) {
        List<Object> out = new ArrayList<>();
        for (Object v : vals) {
            if (v instanceof List<?> l) out.addAll(l);
            else out.add(v);
        }
        return out;
    }

    /**
     * 구절이 사용자 발화에 있는가. 글자 그대로 있거나, 구절의 숫자가 한 발화에 모두 있고 구절이 여섯 글자 이상이고 글자쌍 절반 이상이
     * 그 발화에 있으면 있다고 본다. 예비 실험(2026-10-30)에서 막은 사유의 63% 가 맞는 값이었고, 대부분은 모델이
     * 구절을 "1대와 3대를 비교해 주세요" 처럼 고쳐 옮긴 경우였다. 숫자는 그대로 요구하므로 지어낸 숫자는 계속 막힌다.
     */
    // ponytail: 글자쌍 겹침 휴리스틱. 사람 확인에서 잘못 통과가 늘면 정렬(alignment) 기반 대조로 바꾼다.
    static boolean found(String quote, List<String> utterances) {
        String q = squash(quote);
        List<Double> nums = numbers(quote);
        for (String u : utterances) {
            String s = squash(u);
            if (s.contains(q)) return true;
            // 짧은 구절은 글자 그대로만 — "30회" 가 "30분당 500원" 의 글자쌍 "30" 하나로 통과했다(예비 재실행 P-T4-01).
            if (q.length() >= 6 && numbers(u).containsAll(nums) && coverage(q, s) >= 0.5) return true;
        }
        return false;
    }

    /** q 의 글자쌍 가운데 s 에 있는 비율. q 가 한 글자면 포함 여부. */
    private static double coverage(String q, String s) {
        if (q.length() < 2) return s.contains(q) ? 1 : 0;
        int hit = 0;
        for (int i = 0; i + 2 <= q.length(); i++) if (s.contains(q.substring(i, i + 2))) hit++;
        return (double) hit / (q.length() - 1);
    }

    /** 공백과 문장부호를 뺀다. 모델이 따옴표 · 마침표를 붙이거나 빼도 같은 구절로 본다. */
    private static String squash(String s) {
        return s.replaceAll("[\\s\\p{P}]+", "");
    }
}
