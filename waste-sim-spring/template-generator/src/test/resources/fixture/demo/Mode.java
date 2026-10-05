package demo;

public enum Mode {
    FAST, SAFE, ECO;

    public static Mode fromName(String n) {
        return valueOf(n);
    }
}
