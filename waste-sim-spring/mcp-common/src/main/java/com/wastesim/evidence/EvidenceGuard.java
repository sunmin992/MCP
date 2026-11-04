package com.wastesim.evidence;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근거 검사(GUARD). 출처 표시를 사용자의 실제 발화와 맞춰 본다. 모든 시뮬레이터 제공자와 브로커가 같은 규칙을 쓴다.
 *
 * <p>출처는 LLM 이 스스로 적는다. 2026-10-30 본 실험에서 "3회 반복" 을 seeds=30 USER 로 낸 경우가
 * 31번, 묻지 않은 기본값을 낸 경우가 47번 나왔고 모두 형식·범위 검사를 통과했다. 그래서 값마다
 * 근거가 된 발화 구절을 받아, 구절이 실제 발화에 있는지와 값이 구절로 설명되는지를 서버가 본다.
 *
 * <ul>
 *   <li>USER 값: 구절이 발화에 있어야 한다.
 *     <ul>
 *       <li>수치형: 구절의 숫자로 값을 설명할 수 있어야 하고(선언된 단위로만 바꿔 본다), 그 숫자 뒤에 다른 필드의 단위말이
 *           붙어 있으면 막는다("차량 2대" 를 건물 수 2 의 근거로 쓰는 경우).</li>
 *       <li>선택지: 필드가 선택지 표현을 주면, 구절에서 가장 길게 맞는 표현이 다른 값의 것일 때 막는다
 *           ("반영하지 말고" 는 "반영" 보다 길게 맞아 IGNORE 로 읽힌다). 맞는 표현이 없으면 판단하지 못한 것으로 남긴다.</li>
 *       <li>구절에 숫자가 없거나 표현이 없는 필드면 판단하지 못한 것으로 남기고 막지 않는다.</li>
 *     </ul></li>
 *   <li>조건부 결정의 기본값(MODEL_DEFAULT): 사용자가 답하거나 동의한 구절이 발화에 있어야 한다. 조건이 생기면서 새로
 *       필요해진 결정이라 사용자가 존재를 모른 채 기본값이 들어가기 쉽다.</li>
 * </ul>
 *
 * <p>시뮬레이터 모델을 모른다 — {@link EvidenceField} 로 받은 답변키 · 값 형식 · 단위 · 표현만 본다. 발화 목록은 클라이언트가 넘긴다.
 */
public final class EvidenceGuard {

    public record Result(List<String> blocks, List<String> unverified) {}

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");
    private static final Pattern NUMBER_WORD = Pattern.compile("(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*([%가-힣A-Za-z]+)?");

    private EvidenceGuard() {}

    /**
     * @param submitted 답변키 → 제출한 값들(실험 변수는 비교할 값 전부)
     * @param fields    답변키 → 검사에 쓸 결정 정보. 모르는 키면 비어 있다
     */
    public static Result check(Map<String, List<Object>> submitted, Map<String, String> origins,
                               Map<String, String> evidence, List<String> utterances,
                               Function<String, Optional<EvidenceField>> fields) {
        List<String> blocks = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        // "이틀" · "두 대" · "한 시간 반" 을 숫자로 바꾼 뒤 대조한다(KoreanNumbers). 발화와 구절을 같은 규칙으로 바꾼다.
        List<String> said = utterances.stream().map(KoreanNumbers::normalize).toList();
        submitted.forEach((key, vals) -> {
            String origin = origins.getOrDefault(key, "MODEL_DEFAULT");
            Optional<EvidenceField> f = fields.apply(key);
            String quote = KoreanNumbers.normalize(evidence.getOrDefault(key, "").trim());
            boolean quoted = !quote.isEmpty() && found(quote, said);
            if ("USER".equals(origin)) {
                if (quote.isEmpty()) {
                    blocks.add(key + ": USER 값의 근거 구절(evidence)이 없습니다 — 사용자가 이 값을 말한 적이 없으면 제출을 멈추고 일반 메시지로 사용자에게 물으십시오. 같은 값을 다시 내도 같은 이유로 막힙니다");
                } else if (!quoted) {
                    blocks.add(key + ": 근거 구절 '" + quote + "' 이(가) 사용자 발화에 없습니다 — 사용자가 이 값을 말한 적이 없으면 제출을 멈추고 일반 메시지로 사용자에게 물으십시오. 같은 값을 다시 내도 같은 이유로 막힙니다");
                } else if (f.isPresent() && f.get().numeric()) {
                    if (numbers(quote).isEmpty()) unverified.add(key);
                    else for (Object v : flat(vals))
                        if (v instanceof Number n) numericBlock(f.get(), quote, n.doubleValue()).ifPresent(blocks::add);
                } else if (f.isPresent() && f.get().choice() && !f.get().valueTerms().isEmpty()) {
                    for (Object v : flat(vals)) {
                        String meant = meant(f.get(), quote);
                        if (meant == null) unverified.add(key);
                        else if (!meant.equals(String.valueOf(v)))
                            blocks.add(key + ": 사용자 발화 '" + quote + "' 은(는) " + meant + " 을(를) 뜻합니다. 제출한 값은 " + v
                                    + " 입니다 — 사용자가 말한 값으로 고치거나 다시 물으십시오");
                    }
                } else {
                    unverified.add(key);
                }
            } else if ("MODEL_DEFAULT".equals(origin) && f.isPresent() && f.get().conditionalDefault() && !quoted) {
                blocks.add(key + ": 사용자에게 묻거나 동의받지 않은 기본값입니다 — 사용자에게 묻고, 받은 답이나 '기본값으로' 같은 동의 구절을 evidence 로 넣으십시오");
            }
        });
        return new Result(blocks, unverified);
    }

    /** 이 값을 guard 가 실제로 따져 보는가 — USER 값, 그리고 조건부 결정의 MODEL_DEFAULT(출처가 없으면 MODEL_DEFAULT 로 본다). */
    public static boolean examined(String origin, EvidenceField f) {
        String o = origin == null ? "MODEL_DEFAULT" : origin;
        return "USER".equals(o) || ("MODEL_DEFAULT".equals(o) && f.conditionalDefault());
    }

    /**
     * 구절의 숫자가 값을 설명하면 빈 값, 아니면 막는 사유. 설명하는 숫자 뒤에 필드가 받지 않는 단위말이 붙어 있으면 그 숫자는
     * 다른 필드의 값으로 본다. 필드가 단위나 단위말을 선언하지 않으면 단위말은 보지 않는다.
     */
    static Optional<String> numericBlock(EvidenceField f, String quote, double value) {
        quote = KoreanNumbers.normalize(quote);
        Set<String> accepted = new HashSet<>(f.unitWords());
        accepted.addAll(UnitEvidence.words(f.unit()));
        boolean explained = false, explainedWithForeignWord = false;
        Matcher m = NUMBER_WORD.matcher(quote);
        while (m.find()) {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            if (!UnitEvidence.explains(n, value, quote, f.unit())) continue;
            String word = counter(m.group(2));
            if (!accepted.isEmpty() && word != null && !accepted.contains(word)) explainedWithForeignWord = true;
            else explained = true;
        }
        if (explained) return Optional.empty();
        if (explainedWithForeignWord)
            return Optional.of(f.key() + ": 사용자 발화 '" + quote + "' 의 숫자는 이 항목의 단위(" + String.join("·", accepted)
                    + ")가 아닌 다른 단위로 말해졌습니다 — 이 값이 어느 항목의 것인지 다시 확인하십시오");
        return Optional.of(f.key() + ": 사용자 발화 '" + quote + "' 와 값 " + fmt(value) + " 이(가) 다릅니다 — 사용자가 말한 값으로 고치거나 다시 물으십시오");
    }

    /** 숫자 뒤에 붙은 말에서 알려진 단위말을 가장 길게 떼어 낸다("대와" → "대"). 알려진 말이 아니면 null. */
    static String counter(String following) {
        if (following == null) return null;
        String best = null;
        for (String w : known())
            if (following.toLowerCase(Locale.ROOT).startsWith(w.toLowerCase(Locale.ROOT)) && (best == null || w.length() > best.length())) best = w;
        return best;
    }

    private static List<String> known() {
        List<String> out = new ArrayList<>(UnitEvidence.COMMON_COUNTERS);
        UnitEvidence.WORDS.values().forEach(out::addAll);
        return out;
    }

    /** 구절에서 가장 길게 맞는 선택지 표현의 값. 값 이름 자체도 표현으로 본다. 맞는 것이 없으면 null. */
    static String meant(EvidenceField f, String quote) {
        String q = squash(quote).toLowerCase(Locale.ROOT);
        String best = null;
        int bestLen = 0;
        for (var e : f.valueTerms().entrySet()) {
            List<String> terms = new ArrayList<>(e.getValue());
            terms.add(e.getKey());
            for (String t : terms) {
                String s = squash(t).toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && q.contains(s) && s.length() > bestLen) { best = e.getKey(); bestLen = s.length(); }
            }
        }
        return best;
    }

    public static List<Double> numbers(String s) {
        List<Double> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(KoreanNumbers.normalize(s));
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

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * 구절이 사용자 발화에 있는가. 글자 그대로 있거나, 구절의 숫자가 한 발화에 모두 있고 구절이 여섯 글자 이상이고 글자쌍 절반 이상이
     * 그 발화에 있으면 있다고 본다. 예비 실험(2026-10-30)에서 막은 사유의 63% 가 맞는 값이었고, 대부분은 모델이
     * 구절을 "1대와 3대를 비교해 주세요" 처럼 고쳐 옮긴 경우였다. 숫자는 그대로 요구하므로 지어낸 숫자는 계속 막힌다.
     */
    // ponytail: 글자쌍 겹침 휴리스틱. 사람 확인에서 잘못 통과가 늘면 정렬(alignment) 기반 대조로 바꾼다.
    public static boolean found(String quote, List<String> utterances) {
        quote = KoreanNumbers.normalize(quote);
        String q = squash(quote);
        List<Double> nums = numbers(quote);
        for (String raw : utterances) {
            String u = KoreanNumbers.normalize(raw);
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
    static String squash(String s) {
        return s.replaceAll("[\\s\\p{P}]+", "");
    }
}
