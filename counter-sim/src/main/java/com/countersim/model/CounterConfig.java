package com.countersim.model;

/**
 * 민원 창구 시뮬레이션 실행 설정 한 벌.
 *
 * <p>세터가 있는 필드가 사용자가 정할 수 있는 값이다. 초기값이 {@code null} 인 필드는 기본값이 없다 —
 * 서브태스크 템플릿이 반드시 묻고, 검증기가 비어 있으면 막는다. 조용히 대신할 값을 두지 않는다.
 *
 * <p>게터는 확인 화면과 확인 토큰 해시가 그대로 읽는다. 계산용 메서드는 {@code get}/{@code is} 로
 * 시작하지 않게 짓는다 — 그러면 해시와 화면에 섞인다.
 */
public class CounterConfig {

    /** 하루 운영 시간(분). 09:00~18:00 고정 — 템플릿에 없다. */
    public static final int OPEN_MINUTES = 540;

    /** 열어 두는 창구 수. */
    private int counterCount = 3;
    /** 하루 방문 민원인 수(예약 포함). */
    private int dailyVisitors = 180;
    /** 한 건 평균 처리 시간(분). 처리 시간은 이 평균의 지수분포다. */
    private double serviceMinutes = 8.0;
    /** 줄 서는 방식. {@link QueueDiscipline} 이름. */
    private String queueDiscipline = QueueDiscipline.SINGLE_LINE.name();
    /** 방문 예약제를 쓰는가. */
    private boolean reservationEnabled = false;
    /** 방문객 가운데 예약한 사람의 비율. 예약제일 때만 쓴다. 기본값 없음. */
    private Double reservationShare;
    /** 이 시간 넘게 기다리면 돌아간다(분). 기본값 없음. */
    private Integer maxWaitMinutes;
    /** 기간(일). */
    private int days = 5;
    /** 반복 횟수. 시드는 1..seeds 로 고정이다. */
    private int seeds = 30;

    public int getCounterCount() { return counterCount; }
    public void setCounterCount(int counterCount) { this.counterCount = counterCount; }

    public int getDailyVisitors() { return dailyVisitors; }
    public void setDailyVisitors(int dailyVisitors) { this.dailyVisitors = dailyVisitors; }

    public double getServiceMinutes() { return serviceMinutes; }
    public void setServiceMinutes(double serviceMinutes) { this.serviceMinutes = serviceMinutes; }

    public String getQueueDiscipline() { return queueDiscipline; }
    public void setQueueDiscipline(String queueDiscipline) { this.queueDiscipline = queueDiscipline; }

    public boolean isReservationEnabled() { return reservationEnabled; }
    public void setReservationEnabled(boolean reservationEnabled) { this.reservationEnabled = reservationEnabled; }

    public Double getReservationShare() { return reservationShare; }
    public void setReservationShare(Double reservationShare) { this.reservationShare = reservationShare; }

    public Integer getMaxWaitMinutes() { return maxWaitMinutes; }
    public void setMaxWaitMinutes(Integer maxWaitMinutes) { this.maxWaitMinutes = maxWaitMinutes; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public int getSeeds() { return seeds; }
    public void setSeeds(int seeds) { this.seeds = seeds; }

    // ── 계산용 (게터가 아니다) ─────────────────────────────────────

    public QueueDiscipline resolveQueueDiscipline() { return QueueDiscipline.fromName(queueDiscipline); }

    /** 예약제가 아니면 0 — 예약 비율을 적었어도 쓰지 않는다(검증기가 막는다). */
    public double effectiveReservationShare() {
        return reservationEnabled && reservationShare != null ? reservationShare : 0.0;
    }
}
