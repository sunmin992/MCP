package com.wastesim.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국어 시각 표현을 "하루 중 분"(0~1439)으로 변환하는 결정론적 파서.
 *
 * <p>{@link TimeExpressionDetector}는 실행 게이트(C2)를 위해 "시각 표현이
 * 몇 개인가"만 정규식으로 세고, 실제 값은 LLM({@code extractParamsStrict})이
 * 뽑았다 — 실행 여부 판정만 결정론이면 충분했기 때문이다. 이 클래스는 경로
 * 소요시간 질의처럼 LLM 호출 없이 시각 "값 자체"가 필요한 기능을 위해
 * 별도로 도입했다(같은 C2 철학을 값 추출까지 확장).
 *
 * <p>어휘·구조는 {@code TimeExpressionDetector.TIME_EXPR}과 동일하게 맞췄다
 * (숫자·순우리말 수사·오전오후 접두어·"시 반"/"시 N분"/":MM" 표기 지원).
 */
public final class KoreanTimeParser {

    private KoreanTimeParser() {}

    private static final Pattern PATTERN = Pattern.compile(
            "(오전|오후|아침|점심|저녁|밤|새벽|낮)?\\s*" +
            "([01]?\\d|2[0-3]|열한|열두|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*" +
            "(?:시\\s*(반)|시\\s*([0-5]?\\d)\\s*분|시(?!간|드)|:([0-5]\\d))");

    private static final Map<String, Integer> KOR_NUM = Map.ofEntries(
            Map.entry("한", 1), Map.entry("두", 2), Map.entry("세", 3), Map.entry("네", 4),
            Map.entry("다섯", 5), Map.entry("여섯", 6), Map.entry("일곱", 7), Map.entry("여덟", 8),
            Map.entry("아홉", 9), Map.entry("열한", 11), Map.entry("열두", 12), Map.entry("열", 10));

    /** 텍스트에서 처음 매칭되는 시각 표현 하나를 "하루 중 분"으로 변환. 없으면 null. */
    public static Integer parseFirst(String text) {
        if (text == null) return null;
        Matcher m = PATTERN.matcher(text);
        if (!m.find()) return null;

        String ampm = m.group(1);
        String hourTok = m.group(2);
        boolean half = m.group(3) != null;
        String minTok = m.group(4);
        String colonMinTok = m.group(5);

        Integer korHour = KOR_NUM.get(hourTok);
        int hour = korHour != null ? korHour : Integer.parseInt(hourTok);
        int minute = half ? 30
                : minTok != null ? Integer.parseInt(minTok)
                : colonMinTok != null ? Integer.parseInt(colonMinTok)
                : 0;

        if (ampm != null) {
            boolean pm = "오후".equals(ampm) || "저녁".equals(ampm) || "밤".equals(ampm) || "점심".equals(ampm) || "낮".equals(ampm);
            boolean am = "오전".equals(ampm) || "아침".equals(ampm) || "새벽".equals(ampm);
            if (pm && hour >= 1 && hour <= 11) hour += 12;
            if (am && hour == 12) hour = 0;
        }
        hour = ((hour % 24) + 24) % 24;
        minute = Math.max(0, Math.min(59, minute));
        return hour * 60 + minute;
    }

    /** 텍스트에 나온 서로 다른 시각을 등장 순서대로 모두 변환한다. */
    public static List<Integer> parseAllDistinct(String text) {
        if (text == null) return List.of();
        Matcher matcher = PATTERN.matcher(text);
        LinkedHashSet<Integer> minutes = new LinkedHashSet<>();
        while (matcher.find()) minutes.add(parseMatch(matcher));
        return new ArrayList<>(minutes);
    }

    private static int parseMatch(Matcher m) {
        String ampm = m.group(1);
        String hourTok = m.group(2);
        boolean half = m.group(3) != null;
        String minTok = m.group(4);
        String colonMinTok = m.group(5);

        Integer korHour = KOR_NUM.get(hourTok);
        int hour = korHour != null ? korHour : Integer.parseInt(hourTok);
        int minute = half ? 30
                : minTok != null ? Integer.parseInt(minTok)
                : colonMinTok != null ? Integer.parseInt(colonMinTok)
                : 0;

        if (ampm != null) {
            boolean pm = "오후".equals(ampm) || "저녁".equals(ampm) || "밤".equals(ampm) || "점심".equals(ampm) || "낮".equals(ampm);
            boolean am = "오전".equals(ampm) || "아침".equals(ampm) || "새벽".equals(ampm);
            if (pm && hour >= 1 && hour <= 11) hour += 12;
            if (am && hour == 12) hour = 0;
        }
        return (((hour % 24) + 24) % 24) * 60 + Math.max(0, Math.min(59, minute));
    }

    /** 하루 중 분 → "HH:MM" 표기. */
    public static String toHHMM(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
