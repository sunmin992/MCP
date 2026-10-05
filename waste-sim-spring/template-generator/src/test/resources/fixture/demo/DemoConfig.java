package demo;

import java.util.List;

/** 생성기 시험용 가상 시뮬레이터 설정. 컴파일하지 않고 소스로만 읽는다. */
public class DemoConfig {

    static final int LIMIT = 10 * 6;

    /** 투입할 로봇 수. 한 대 이상이어야 한다. */
    private int robots = 2;

    private double speed = 1.5;

    private String mode = Mode.FAST.name();

    private String color = "red";

    private Double budgetKg = null;

    private List<Integer> slotsMinutes = null;

    private List<String> crew = null;

    private boolean verbose = false;

    private double[] curve = null;

    /** verbose 일 때만 쓰는 프로파일. */
    private String profileId = null;

    /** slotsMinutes 가 비었을 때만 쓰는 시작 시각. */
    private int startMinutes = 600;

    /** 로봇마다 출발을 미루는 간격. 두 번째 로봇부터 효과가 있다. */
    private int stepMinutes = 0;

    /** 세터가 없으면 바깥에서 정할 수 없는 내부 상태다. */
    private int internalCounter = 0;

    public int getRobots() { return robots; }
    public void setRobots(int v) { this.robots = v; }
    public double getSpeed() { return speed; }
    public void setSpeed(double v) { this.speed = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
    public Double getBudgetKg() { return budgetKg; }
    public void setBudgetKg(Double v) { this.budgetKg = v; }
    public List<Integer> getSlotsMinutes() { return slotsMinutes; }
    public void setSlotsMinutes(List<Integer> v) { this.slotsMinutes = v; }
    public List<String> getCrew() { return crew; }
    public void setCrew(List<String> v) { this.crew = v; }
    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean v) { this.verbose = v; }
    public double[] getCurve() { return curve; }
    public void setCurve(double[] v) { this.curve = v; }
    public int getInternalCounter() { return internalCounter; }

    /** 별칭 게터 — 이름은 다르지만 robots 를 돌려준다. */
    public int getFleetSize() { return getRobots(); }

    public String getProfileId() { return profileId; }
    public void setProfileId(String v) { this.profileId = v; }
    public int getStartMinutes() { return startMinutes; }
    public void setStartMinutes(int v) { this.startMinutes = v; }
    public int getStepMinutes() { return stepMinutes; }
    public void setStepMinutes(int v) { this.stepMinutes = v; }

    public Mode resolveMode() {
        return Mode.fromName(mode);
    }

    /** 목록이 있으면 목록, 없으면 시작 시각 하나 — 둘 중 하나만 쓰인다. */
    public List<Integer> resolveSlots() {
        if (slotsMinutes != null && !slotsMinutes.isEmpty()) {
            return slotsMinutes;
        }
        return List.of(startMinutes);
    }
}
