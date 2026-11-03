package com.wastesim.readiness;

import java.util.Locale;
import java.util.regex.Pattern;

/** Unit-aware numeric evidence; count fields never inherit time conversions. */
public final class NumericEvidence {
    private NumericEvidence() {}

    public static boolean explains(double number, double value, String quote, String key, String unit) {
        String u = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        boolean minutes = u.equals("분") || u.equals("min") || u.equals("minute") || u.equals("minutes")
                || key.endsWith("Minutes");
        if (minutes) {
            var time = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(시간|시|hours?)(?:\\s*(\\d+)\\s*분)?",
                    Pattern.CASE_INSENSITIVE).matcher(quote);
            boolean explicit = false;
            boolean match = false;
            while (time.find()) {
                explicit = true;
                double hours = Double.parseDouble(time.group(1));
                boolean clock = time.group(2).equals("시");
                if (clock && quote.contains("오후") && hours >= 1 && hours <= 12) hours = hours % 12 + 12;
                if (clock && quote.contains("오전") && hours == 12) hours = 0;
                double extraMinutes = time.group(3) == null ? 0 : Double.parseDouble(time.group(3));
                match |= equal(hours * 60 + extraMinutes, value);
            }
            if (explicit) return match;
        }
        boolean days = u.equals("일") || u.equals("days") || key.equals("days");
        if (days && quote.contains("주")) return equal(number * 7, value);
        boolean ratio = u.equals("비율") || u.equals("ratio") || u.equals("fraction")
                // Legacy templates without a unit: only known ratio fields, never arbitrary *Rate counts.
                || java.util.Set.of("complaintThreshold", "peakSurchargeRate", "residentPriorityShare", "reservationShare").contains(key);
        if (ratio && (quote.contains("%") || quote.contains("퍼센트"))) return equal(number / 100, value);
        return equal(number, value);
    }

    private static boolean equal(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) < 1e-9;
    }
}
