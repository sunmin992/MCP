package com.wastesim.edge;

import java.util.List;

/**
 * RPM 스윕의 <b>한 지점</b> — 같은 열·부하 조건에서 팬 속도만 바꿔 돌린 결과 하나
 * (FAN_RPM_SWEEP_DESIGN.md §3).
 *
 * <h3>왜 탈락한 지점까지 남기는가</h3>
 * 최적점만 돌려주면 사용자는 그 값을 <b>검증할 수 없다</b>. "왜 30%가 아니라 50%인가"는
 * 30% 지점이 처리량 조건에서 떨어졌다는 사실을 봐야 답이 되고, 발표용 곡선도 탈락 구간을
 * 함께 그려야 "여기서부터 스로틀링"이 보인다(§9). 그래서 모든 지점이 탈락 사유와 함께 남는다.
 *
 * @param commandedPwmPercent 명령 PWM(%)
 * @param effectiveRpm        유효 회전수 — TACH 실측이 있으면 그 값, 없으면 PWM 추정
 * @param rpmSource           회전수 출처({@link FanArraySpec.RpmSource}) — 결과 신뢰도를 읽는 근거
 * @param rJaKPerW            이 회전수에서의 전체 열저항(K/W)
 * @param fanPowerW           팬 배열 소비전력(W)
 * @param fanEnergyJ          팬이 소비한 에너지(J)
 * @param socEnergyJ          SoC가 소비한 에너지(J) — 온도를 만든 몫
 * @param totalEnergyJ        SoC + 팬(+측정 가능한 경우 기동) 총에너지(J)
 * @param peakTempC           최고 온도(℃)
 * @param softLimitEntrySec   소프트 제한 진입 시각(초). 미도달이면 null
 * @param tttSec              스로틀링 진입 시각(초). 미발생이면 null — <b>null이 곧 정상은 아니다</b>(§5.1)
 * @param medianTedSec        TED 중앙값(초)
 * @param throttledFraction   부하 구간 중 스로틀링 상태였던 시간 비율(0~1)
 * @param meanFpsLoad         부하 구간 평균 FPS
 * @param throughputLossPercent 지속 처리량 손실률(%)
 * @param processedFrames     처리한 프레임 수 = ∫FPS dt
 * @param energyPerFrameJ     프레임당 에너지(J). 처리 프레임이 0이면 null
 * @param feasible            모든 제약을 만족했는가
 * @param rejectionReasons    탈락 사유 코드. 여러 개일 수 있다(§5.2)
 */
public record FanSweepPoint(
        double commandedPwmPercent,
        double effectiveRpm,
        String rpmSource,
        double rJaKPerW,
        double fanPowerW,
        double fanEnergyJ,
        double socEnergyJ,
        double totalEnergyJ,
        double peakTempC,
        Double softLimitEntrySec,
        Double tttSec,
        Double medianTedSec,
        double throttledFraction,
        double meanFpsLoad,
        double throughputLossPercent,
        double processedFrames,
        Double energyPerFrameJ,
        boolean feasible,
        List<String> rejectionReasons) {

    /** 탈락 사유 코드(§5.2). 한 지점에 여러 개가 붙을 수 있다. */
    public enum Rejection {
        /** TTT가 발생했거나 하드 스로틀링 시간 비율이 0보다 크다. */
        HARD_THROTTLED,
        /** 최고 온도가 한계를 초과했다. */
        TOO_HOT,
        /** 지속 처리량 손실이 허용 범위를 넘었다. */
        THROUGHPUT_LOSS,
        /** 목표 FPS를 유지하지 못했다(TARGET_FPS 모드 전용). */
        TARGET_FPS_MISSED
    }

    /**
     * 목적함수 값(작을수록 좋다). 값을 낼 수 없으면 null —
     * 예를 들어 한 프레임도 처리하지 못한 지점의 프레임당 에너지가 그렇다.
     */
    public Double objectiveValue(FanSweepResult.Objective objective) {
        return switch (objective) {
            case MIN_TOTAL_ENERGY -> totalEnergyJ;
            case MIN_ENERGY_PER_FRAME -> energyPerFrameJ;
            case MIN_FAN_ENERGY -> fanEnergyJ;
            case MIN_PWM -> commandedPwmPercent;
        };
    }
}
