package com.wastesim.edge;

import java.util.List;

/**
 * 시뮬레이션 한 회 실행 결과 — 실험 설계 §4.5 "주요 측정 지표"를 그대로 담는다.
 * MCP 응답으로 그대로 직렬화되므로 필드명이 곧 학생이 보는 결과지 항목이다.
 *
 * @param board            보드 라벨
 * @param params           사용된 열 파라미터(재현성 — 어떤 값으로 돌렸는지 결과에 남긴다)
 * @param softLimitEntrySec 소프트 온도 제한(80℃, 0x8) 최초 진입 시각(초) — 성능 저하가 시작되는 시점.
 *                          하드 스로틀링이 없는 조건에서도 이 값은 관측된다
 * @param tttSec           Time To Throttling — 부하 시작부터 스로틀링(0x4) 최초 "지속" 감지까지(초). 미발생 시 null
 * @param tttFirstDetectSec 히스테리시스 확인 없이 최초로 비트가 켜진 시각(초). 미발생 시 null
 * @param episodes         부하 유지 구간에서 관찰된 스로틀링 에피소드(각각의 지속시간이 TED)
 * @param medianTedSec     TED 중앙값(초). 에피소드가 없으면 null
 * @param trtStateSec      TRT_state — 회복 정책 적용 후 스로틀링 비트가 풀릴 때까지(초)
 * @param trtServiceSec    TRT_service — 달성 가능 FPS가 기준선의 90% 이상으로 복원될 때까지(초)
 * @param trtFullSec       TRT_full — 유휴 정상상태 온도 +2℃ 이내로 완전 냉각될 때까지(초)
 * @param peakTempC        관측 최고 온도
 * @param loadEndTempC     부하 종료(=회복 시작) 시점 온도
 * @param tempAmplitudeC   <b>온도 진폭</b> — 부하 후반(정착 구간)의 peak-to-peak 온도 폭(℃).
 *                         부품 수명·안정성의 평가 축이다. 워밍업 램프를 진폭으로 오인하지 않도록
 *                         부하 후반 절반만 본다. 상수 부하면 ≈0, burst/mixed 시변 부하면 주기적
 *                         출렁임의 폭이 잡히고, 질량 큰 방열판(2노드 열용량)이 이 값을 눌러 준다
 * @param loadSettledMeanTempC 정착 구간에서 온도가 진동하는 중심(평균) 온도(℃)
 * @param steadyStateTempC 이번 부하 조건에서 스로틀링이 없다고 가정한 이론 정상상태 온도
 *                         (목표 FPS 모드면 그 FPS를 유지하는 데 필요한 사용률 기준) —
 *                         hardLimit 초과면 "언젠가 반드시 스로틀링"
 * @param throttlingExpected 이론상 스로틀링 발생 여부
 * @param throttledFraction 부하 구간 중 스로틀링 상태였던 시간 비율(0~1)
 * @param meanFpsLoad      부하 구간 평균 FPS
 * @param fpsDropPercent   기준 FPS 대비 부하 구간 후반 FPS 하락률(%) — <b>최악 순간</b>의 낙폭이다
 * @param throughputLossPercent 스로틀링이 없었다면 낼 수 있었을 처리량 대비 <b>지속</b> 손실률(%).
 *                         {@code (1 − 평균FPS / 무스로틀 달성가능FPS) × 100}.
 *                         {@code fpsDropPercent}와 다른 질문에 답한다 — 저쪽은 "가장 깎였을 때 얼마나
 *                         깎였나", 이쪽은 "실행 내내 일을 얼마나 못 했나"다. TTT가 발생하지 않아도
 *                         소프트 제한만으로 이 값은 0이 아니므로, "TTT 없음 = 문제 없음"이라는
 *                         오독을 막는 지표다
 * @param processedFrames  부하 구간에서 실제로 처리한 추론 프레임 수 = {@code ∫ FPS dt}.
 *                         <b>RPM 스윕에서 "일한 양"의 기준</b>이다 — 최대 처리량 모드는 냉각을
 *                         강화할수록 더 많이 처리하므로 총에너지만 비교하면 적게 일한 저RPM
 *                         지점이 이긴다. 프레임당 에너지({@code totalEnergyJ / processedFrames})로
 *                         나눠야 같은 질문에 답한다(FAN_RPM_SWEEP_DESIGN.md §6.2)
 * @param tauHeatingSec    가열 시정수(초)
 * @param energyJ          SoC가 소비한 에너지(J) — 온도를 만든 몫
 * @param fanEnergyJ       냉각팬이 소비한 에너지(J). 팬이 없으면 0.
 *                         팬 전력은 모터에서 소비되어 SoC를 데우지 않으므로 {@code energyJ}와
 *                         분리해 집계한다 — 온도의 원인과 비용의 원인이 다르기 때문이다
 * @param totalEnergyJ     SoC + 팬(+측정 가능한 경우 기동) = 시스템 전체 소비 에너지(J).
 *                         <b>가성비 판정의 분모</b>가 이 값이다 — 팬을 세게 돌려 온도를 낮춰도
 *                         여기가 커지면 손해다
 * @param fanReport        팬 배열 출력(개수·PWM·RPM 출처·전력·불확실성). 팬이 없으면 null
 * @param series           시계열 샘플(다운샘플링됨)
 * @param notes            해석에 필요한 경고·주석
 */
public record ThermalRun(
        String board,
        ThermalParams params,
        Double softLimitEntrySec,
        Double tttSec,
        Double tttFirstDetectSec,
        List<Episode> episodes,
        Double medianTedSec,
        Double trtStateSec,
        Double trtServiceSec,
        Double trtFullSec,
        double peakTempC,
        double loadEndTempC,
        double tempAmplitudeC,
        double loadSettledMeanTempC,
        double steadyStateTempC,
        boolean throttlingExpected,
        double throttledFraction,
        double meanFpsLoad,
        double fpsDropPercent,
        double throughputLossPercent,
        double processedFrames,
        double tauHeatingSec,
        double energyJ,
        double fanEnergyJ,
        double totalEnergyJ,
        FanReport fanReport,
        List<Sample> series,
        List<String> notes) {

    /**
     * 팬 배열 출력(실험 설계 §11). 값이 없는 것이 곧 결과인 필드는 null로 둔다 —
     * 예: TACH 미측정이면 {@code measuredRpm}은 null이고 {@code effectiveRpm}은 PWM 추정치다.
     * 검증 전 임시 사양임을 {@code fanSpecVerified=false}로 항상 함께 표시한다.
     */
    public record FanReport(int fanCount, double commandedPwmPercent, Double measuredRpm,
                            double effectiveRpm, String rpmSource, Double fanCurrentA,
                            double fanPowerW, double fanEnergyJ, Double fanStartupEnergyJ,
                            Double startupPeakCurrentA, String fanSpecSource, boolean fanSpecVerified,
                            String measurementScope) {}

    /**
     * 시계열 한 점.
     *
     * @param throttleBits vcgencmd get_throttled와 같은 의미의 현재 비트
     *                     (0x1 저전압, 0x2 클럭 제한, 0x4 스로틀링, 0x8 소프트 온도 제한)
     */
    public record Sample(double tSec, String phase, double socTempC, int clockMhz,
                         double fps, double powerW, boolean throttled, String throttleBits) {}

    /** 스로틀링 에피소드 — 지속시간이 곧 TED(Throttling Episode Duration). */
    public record Episode(double startSec, Double endSec, Double durationSec, double peakTempC) {}
}
