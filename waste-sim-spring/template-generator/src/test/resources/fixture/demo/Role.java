package demo;

import java.util.Arrays;
import java.util.List;

public enum Role {
    PILOT, MEDIC;

    /** 지정하지 않았을 때의 구성. */
    public static List<Role> defaults() {
        return Arrays.asList(PILOT);
    }
}
