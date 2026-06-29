package com.wastesim.model;

import java.util.Arrays;
import java.util.List;

public enum OccupationType {
    //          라벨            외출(배출) 시각        귀가(2차 배출) 시각
    BlueCollar ("생산직(일용직)", 7 * 60 + 22,        18 * 60),
    Student    ("학생",          8 * 60 + 58,        17 * 60),
    Housewife  ("전업주부",       14 * 60,            16 * 60),
    // ── 확장 거주민 유형 ──────────────────────────────────────────────
    NightShift ("야간 교대근무자", 21 * 60,            7 * 60),   // 밤에 출근, 아침 귀가
    OfficeWorker("1인 직장인",    7 * 60 + 50,        19 * 60);  // 1인 가구 직장인

    public final String labelKo;
    public final int leaveMeanMinutes;
    public final int returnMeanMinutes;

    OccupationType(String labelKo, int leaveMeanMinutes, int returnMeanMinutes) {
        this.labelKo = labelKo;
        this.leaveMeanMinutes = leaveMeanMinutes;
        this.returnMeanMinutes = returnMeanMinutes;
    }

    /** 기본 장량동 구성(논문 Table 1) — 생산직·학생·주부 3종. 확장 유형 추가와 무관하게 유지. */
    public static List<OccupationType> baseMix() {
        return Arrays.asList(BlueCollar, Student, Housewife);
    }

    public static OccupationType fromIndex(int i) {
        OccupationType[] vals = values();
        return vals[i % vals.length];
    }

    /** 직업명 문자열 → enum (대소문자 무관, 별칭 허용) */
    public static OccupationType fromName(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        switch (n) {
            case "bluecollar": case "blue": case "생산직": case "일용직": return BlueCollar;
            case "student":    case "학생":                           return Student;
            case "housewife":  case "주부": case "전업주부":            return Housewife;
            case "nightshift": case "night": case "야간": case "야간근무": case "야간근무자": return NightShift;
            case "officeworker": case "office": case "직장인": case "1인직장인": return OfficeWorker;
            default:
                for (OccupationType t : values())
                    if (t.name().equalsIgnoreCase(name)) return t;
                throw new IllegalArgumentException("Unknown occupation: " + name);
        }
    }
}
