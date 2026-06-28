package com.wastesim.model;

public enum OccupationType {
    BlueCollar("생산직(일용직)", 7 * 60 + 22),
    Student("학생", 8 * 60 + 58),
    Housewife("전업주부", 14 * 60);

    public final String labelKo;
    public final int leaveMeanMinutes;

    OccupationType(String labelKo, int leaveMeanMinutes) {
        this.labelKo = labelKo;
        this.leaveMeanMinutes = leaveMeanMinutes;
    }

    public static OccupationType fromIndex(int i) {
        OccupationType[] vals = values();
        return vals[i % vals.length];
    }
}
