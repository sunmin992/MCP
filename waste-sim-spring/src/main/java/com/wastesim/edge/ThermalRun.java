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
 * @param steadyStateTempC 이번 부하 조건에서 스로틀링이 없다고 가정한 이론 정상상태 온도
 *                         (목표 FPS 모드면 그 FPS를 유지하는 데 필요한 사용률 기준) —
 *                         hardLimit 초과면 "언젠가 반드시 스로틀링"
 * @param throttlingExpected 이론상 스로틀링 발생 여부
 * @param throttledFraction 부하 구간 중 스로틀링 상태였던 시간 비율(0~1)
 * @param meanFpsLoad      부하 구간 평균 FPS
 * @param fpsDropPercent   기준 FPS 대비 부하 구간 후반 FPS 하락률(%)
 * @param tauHeatingSec    가열 시정수(초)
 * @param energyJ          SoC가 소비한 에너지(J) — 온도를 만든 몫
 * @param fanEnergyJ       냉각팬이 소비한 에너지(J). 팬이 없으면 0.
 *                         팬 전력은 모터에서 소비되어 SoC를 데우지 않으므로 {@code energyJ}와
 *                         분리해 집계한다 — 온도의 원인과 비용의 원인이 다르기 때문이다
 * @param totalEnergyJ     SoC + 팬 = 시스템 전체 소비 에너지(J). <b>가성비 판정의 분모</b>가
 *                         이 값이다 — 팬을 세게 돌려 온도를 낮춰도 여기가 커지면 손해다
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
        double steadyStateTempC,
        boolean throttlingExpected,
        double throttledFraction,
        double meanFpsLoad,
        double fpsDropPercent,
        double tauHeatingSec,
        double energyJ,
        double fanEnergyJ,
        double totalEnergyJ,
        List<Sample> series,
        List<String> notes) {

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
