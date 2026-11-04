package com.wastesim.evidence;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국어 수 표현을 숫자로 바꾼다 — 근거 검사가 "이틀", "두 대", "한 시간 반", "1만 5천원" 을 숫자와 대조할 수 있게.
 *
 * <p>바꾸는 것: 날수 낱말(하루 · 이틀 … 열흘 · 보름, 일주일), 고유어 수 + 단위(두 대 · 세 번 · 열두 곳 · 스무 명),
 * "N시간 반", 만 · 천이 섞인 금액, 정오 · 자정. 낱말 일부가 수처럼 보이는 경우(세대 · 한동안 · 두부)는 바꾸지 않는다 —
 * 단위 뒤에 한글이 이어지면 조사일 때만 바꾸고, "세대" 는 띄어 쓴 "세 대" 만 차량 세 대로 본다.
 * 같은 결과를 다시 넣어도 그대로다(멱등).
 */
public final class KoreanNumbers {
    private KoreanNumbers() {}

    private static final Map<String, Integer> DAYS = new LinkedHashMap<>();
    static {
        DAYS.put("여드레", 8); DAYS.put("아흐레", 9); DAYS.put("열흘", 10); DAYS.put("보름", 15);
        DAYS.put("하루", 1); DAYS.put("이틀", 2); DAYS.put("사흘", 3); DAYS.put("나흘", 4);
        DAYS.put("닷새", 5); DAYS.put("엿새", 6); DAYS.put("이레", 7);
    }
    private static final Map<String, Integer> UNITS = Map.ofEntries(
            Map.entry("한", 1), Map.entry("두", 2), Map.entry("세", 3), Map.entry("석", 3), Map.entry("네", 4), Map.entry("넉", 4),
            Map.entry("다섯", 5), Map.entry("여섯", 6), Map.entry("일곱", 7), Map.entry("여덟", 8), Map.entry("아홉", 9));
    private static final String COUNTERS = "시간|세대|가구|구역|창구|대|동|명|번|회|곳|개|시|채|주|달|칸|면|살";
    private static final String JOSA = "은|는|이|가|을|를|에|의|로|으로|와|과|도|만|씩|째|간|짜리|부터|까지|마다|이나|나|동안";
    /** (열|스물|스무)? (한…아홉)? 단위 — 수 낱말 앞은 한글이 아니어야 하고, 단위 뒤는 한글이 아니거나 조사여야 한다. */
    private static final Pattern NATIVE = Pattern.compile(
            "(?<![가-힣])(열|스물|스무)?\\s?(한|두|세|석|네|넉|다섯|여섯|일곱|여덟|아홉)?(\\s?)(" + COUNTERS + ")(?=$|[^가-힣]|(?:" + JOSA + "))");

    public static String normalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (var e : DAYS.entrySet())
            out = out.replaceAll("(?<![가-힣])" + e.getKey() + "(?=$|[^가-힣]|(?:" + JOSA + "))", e.getValue() + "일");
        out = out.replaceAll("(?<![가-힣])일주일", "1주").replaceAll("(?<![가-힣])한\\s?달", "1달");
        out = out.replace("정오", "낮 12시").replace("자정", "밤 12시");
        out = nativeCounts(out);
        // 1만 5천(원) → 15000(원), 2만(원) 은 그대로 둔다 — 단위 바꿈(won)이 만원을 처리한다.
        Matcher m = Pattern.compile("(\\d+)\\s*만\\s*(\\d+)\\s*천").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, String.valueOf(Long.parseLong(m.group(1)) * 10000 + Long.parseLong(m.group(2)) * 1000));
        m.appendTail(sb);
        out = sb.toString();
        // N시간 반 → N.5시간
        m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*시간\\s*반").matcher(out);
        sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, fmt(Double.parseDouble(m.group(1)) + 0.5) + "시간");
        m.appendTail(sb);
        return sb.toString();
    }

    private static String nativeCounts(String s) {
        Matcher m = NATIVE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tens = m.group(1), unit = m.group(2), space = m.group(3), counter = m.group(4);
            if (tens == null && unit == null) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            // "세대"(가구) · "세시"(?) 처럼 붙여 쓴 3+대 는 낱말일 수 있다 — 띄어 쓴 경우만 수로 본다.
            if (tens == null && "세".equals(unit) && space.isEmpty() && (counter.equals("대") || counter.equals("시")) ) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue;
            }
            int n = (tens == null ? 0 : tens.equals("열") ? 10 : 20) + (unit == null ? 0 : UNITS.get(unit));
            m.appendReplacement(sb, n + counter);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
