package com.wastesim.evidence;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 구절의 숫자가 값을 설명하는가 — 템플릿이 선언한 단위로만 바꿔 본다. 필드 이름으로 단위를 짐작하지 않는다.
 *
 * <p>지원 단위와 바꿈: minute(시·시간 → 분, 오전·오후), day(주 → 일), ratio(% → 비율), kg(톤 → kg), won(천원·만원 → 원).
 * 그 밖의 단위나 단위가 없으면 숫자를 그대로 비교한다. 새 단위는 {@link #ALIASES} 와 {@link #explains} 에 한 줄씩 더한다.
 */
public final class UnitEvidence {
    private UnitEvidence() {}

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("minute", "minute"), Map.entry("minutes", "minute"), Map.entry("min", "minute"), Map.entry("분", "minute"),
            Map.entry("day", "day"), Map.entry("days", "day"), Map.entry("일", "day"),
            Map.entry("ratio", "ratio"), Map.entry("fraction", "ratio"), Map.entry("비율", "ratio"),
            Map.entry("kg", "kg"), Map.entry("won", "won"), Map.entry("원", "won"));

    /** 단위마다 사용자가 숫자 뒤에 붙이는 말. 단위말 대조에서 "이 필드에 맞는 말" 로 본다. */
    static final Map<String, List<String>> WORDS = Map.of(
            "minute", List.of("분", "시간", "시", "min", "hour", "hours"),
            "day", List.of("일", "주", "날", "day", "days", "week", "weeks"),
            "ratio", List.of("%", "퍼센트", "프로"),
            "kg", List.of("kg", "킬로", "톤", "t"),
            "won", List.of("원", "천원", "만원"));

    /** 흔히 숫자 뒤에 붙는 말. 필드가 받지 않는 말이 붙어 있으면 다른 필드의 값으로 본다. */
    static final List<String> COMMON_COUNTERS = List.of("대", "동", "채", "명", "세대", "가구", "회", "번", "개", "곳", "구역",
            "칸", "면", "창구", "개소", "층");

    public static String canonical(String unit) {
        return unit == null ? "" : ALIASES.getOrDefault(unit.toLowerCase(Locale.ROOT), unit.toLowerCase(Locale.ROOT));
    }

    public static List<String> words(String unit) {
        return WORDS.getOrDefault(canonical(unit), List.of());
    }

    public static boolean explains(double number, double value, String quote, String unit) {
        switch (canonical(unit)) {
            case "minute" -> {
                Matcher time = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(시간|시|hours?)(?:\\s*(\\d+)\\s*분)?",
                        Pattern.CASE_INSENSITIVE).matcher(quote);
                boolean explicit = false, match = false;
                while (time.find()) {
                    explicit = true;
                    double hours = Double.parseDouble(time.group(1));
                    boolean clock = time.group(2).equals("시");
                    if (clock && quote.contains("오후") && hours >= 1 && hours <= 12) hours = hours % 12 + 12;
                    if (clock && quote.contains("오전") && hours == 12) hours = 0;
                    double extra = time.group(3) == null ? 0 : Double.parseDouble(time.group(3));
                    match |= equal(hours * 60 + extra, value);
                }
                if (explicit) return match;
            }
            case "day" -> {
                if (quote.contains("주") || quote.toLowerCase(Locale.ROOT).contains("week")) return equal(number * 7, value);
            }
            case "ratio" -> {
                if (quote.contains("%") || quote.contains("퍼센트") || quote.contains("프로")) return equal(number / 100, value);
            }
            case "kg" -> {
                if (quote.contains("톤") || Pattern.compile("\\d\\s*t\\b").matcher(quote).find()) return equal(number * 1000, value);
            }
            case "won" -> {
                if (quote.contains("만원")) return equal(number * 10000, value);
                if (quote.contains("천원")) return equal(number * 1000, value);
            }
            default -> { }
        }
        return equal(number, value);
    }

    private static boolean equal(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) < 1e-9;
    }
}
