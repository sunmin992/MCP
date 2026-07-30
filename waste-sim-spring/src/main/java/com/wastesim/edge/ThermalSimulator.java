package com.wastesim.edge;

import java.util.ArrayList;
import java.util.List;

/**
 * 임베디드 보드 발열·스로틀링 동역학 시뮬레이터 — 1차 열 RC 모델 + 펌웨어 클럭
 * 거버너 상태머신을 dt 간격으로 적분한다(실험 설계 §4 "물리 기반(열 RC 모델)").
 *
 * <h3>지배 방정식</h3>
 * <pre>
 *   C_th · dT/dt = P(t) − (T − T_ambient) / R_ja
 *   P(t) = P_idle + P_dyn · u(t) · r(t)      (u=사용률, r=클럭비 f/f_max)
 * </pre>
 * 부하가 일정하고 스로틀링이 없으면 해석해는 {@code T(t) = T_ss − (T_ss − T_0)·e^(−t/τ)},
 * {@code τ = R_ja·C_th} 로, 지수 접근 곡선이다. 스로틀링이 걸리면 r이 떨어져 P가
 * 줄고 곡선이 꺾이므로 해석해가 깨진다 — 그래서 수치 적분한다.
 *
 * <h3>펌웨어 거버너(라즈베리파이 근사)</h3>
 * <ul>
 *   <li>T &lt; soft(80℃): 최대 클럭</li>
 *   <li>T ≥ soft: 소프트 제한 상태 — 클럭을 softFloor(Pi4 1200MHz, Pi5 1900MHz)로 낮춘다(bit 0x8).
 *       실제 펌웨어도 여기서 곧바로 최저 클럭까지 떨어뜨리지는 않는다</li>
 *   <li>T ≥ hard(85℃): 하드 스로틀링 — 클럭 하한까지 낮춘다(bit 0x4). <b>TTT의 기준이 이 비트다.</b></li>
 *   <li>해제는 각각 임계 − 히스테리시스 아래로 내려가야 한다 — 이 히스테리시스가 "떨어졌다 다시
 *       올라가는" TED 진동을 만든다</li>
 * </ul>
 *
 * <p>중요한 귀결: 소프트 제한만으로 열이 잡히는 조건(예: Pi4 무냉각 25℃)에서는 온도가 80℃
 * 부근에서 안정되어 <b>0x4가 한 번도 뜨지 않는다</b> — 실제 라즈베리파이도 그렇다. 이때는
 * TTT 대신 {@code softLimitEntrySec}(80℃ 진입 시각)이 성능 저하가 시작되는 시점이다.
 * 하드 스로틀링을 관측하려면 주변 온도를 올리거나(케이스·여름), 더 강한 부하·더 나쁜 냉각
 * 조건이 필요하다 — 이 사실 자체가 실험 조건 설계의 근거가 된다.
 * <ul style="display:none">
 * </ul>
 *
 * <p><b>왜 이 모델로 충분한가</b>: 단일 노드 lumped 모델은 SoC 내부 hot spot 분포를
 * 표현하지 못한다. 하지만 이 실험이 재는 값(TTT·TED·TRT)은 전부 "SoC 온도 센서 한
 * 점"이 기준이라, 그 한 점의 과도 응답만 맞으면 된다. 공간 분포는 열화상(MLX90640)
 * 관측과 {@link HeatsinkThermalModel}의 배치 항으로 따로 다룬다.
 */
public class ThermalSimulator {

    /** 스로틀링 "지속 감지" 판정 시간(초) — 순간적으로 한 번 켜진 비트는 TTT로 세지 않는다. */
    public static final double THROTTLE_CONFIRM_SEC = 3.0;
    /**
     * 회복 판정 지속 시간(초). 스로틀링 구간은 히스테리시스 때문에 붙었다 떨어졌다를 반복하므로
     * (TED 진동), "비트가 잠깐 꺼진 순간"을 회복으로 세면 TRT가 터무니없이 짧게 나온다.
     * 이 시간만큼 <b>연속으로</b> 해제 상태가 유지돼야 회복으로 인정한다.
     */
    public static final double RECOVERY_CONFIRM_SEC = 30.0;
    /** TRT_service 판정 기준 — 기준 FPS의 90% 복원(실험 설계 §4.5). */
    public static final double SERVICE_RECOVERY_RATIO = 0.90;
    /** TRT_full 판정 기준 — 유휴 정상상태 온도 + 이 값(℃) 이내. */
    public static final double FULL_RECOVERY_MARGIN_C = 2.0;
    /** R2(저부하 유지) 정책의 목표 FPS 배율. */
    public static final double LOW_LOAD_FACTOR = 0.25;
    /** 클럭 변경 슬루 시정수(초) — 펌웨어가 즉시가 아니라 아주 짧은 시간에 걸쳐 클럭을 바꾼다. */
    private static final double CLOCK_SLEW_TAU_SEC = 1.0;

    /**
     * 실행 명세.
     *
     * @param params        열·전력 파라미터
     * @param mode          운용 모드
     * @param targetFps     목표 FPS(TARGET_FPS 모드에서만 의미)
     * @param loadSec       고부하 추론 유지 시간(초)
     * @param policy        회복 정책
     * @param recoverySec   회복 관찰 시간(초)
     * @param recoveryRJa   회복 구간에 적용할 열저항 K/W(R3=팬 가동 시 더 낮은 값). 하드웨어 조건이
     *                      안 바뀌는 R1/R2에서는 {@code params.rJaKPerW()}와 같은 값을 넣는다
     * @param dtSec         적분 간격(초)
     * @param sampleSec     시계열 출력 간격(초)
     * @param triggerOnThrottle true면 <b>스로틀링이 확정 감지된 순간</b> 회복 정책을 적용한다
     *                      (loadSec은 최대 대기 시간이 된다). 정책끼리 TRT를 비교하려면 모두
     *                      같은 상태(스로틀링 중, ~85℃)에서 출발해야 공정하므로 이 쪽이 기본이다.
     *                      false면 정확히 loadSec 시점에 적용한다
     * @param aiLoad        AI 데이터센터식 시변 부하 패턴. null이면 부하가 일정하다(기존 동작).
     *                      {@code mode}와는 <b>독립된 축</b>이다 — 패턴은 "목표를 시각에 따라 몇 %로
     *                      낼 것인가"라는 배율이라, 목표 FPS 모드에도 최대 처리량 모드에도 똑같이 곱해진다
     * @param heatsink      2노드 모델용 방열판 열 덩어리. null이면 1노드(기존 동작)로 적분한다.
     *                      정상상태는 두 모델이 완전히 같고 과도응답만 달라진다({@link HeatsinkMass})
     */
    public record Spec(ThermalParams params, WorkloadMode mode, double targetFps,
                       double loadSec, RecoveryPolicy policy, double recoverySec,
                       double recoveryRJa, double dtSec, double sampleSec, boolean triggerOnThrottle,
                       AiLoadProfile aiLoad, HeatsinkMass heatsink) {

        /** 부하 패턴 없이(상수 부하) 1노드로 실행하는 기존 형태 — 호출부·테스트 하위호환. */
        public Spec(ThermalParams params, WorkloadMode mode, double targetFps,
                    double loadSec, RecoveryPolicy policy, double recoverySec,
                    double recoveryRJa, double dtSec, double sampleSec, boolean triggerOnThrottle) {
            this(params, mode, targetFps, loadSec, policy, recoverySec, recoveryRJa,
                    dtSec, sampleSec, triggerOnThrottle, null, null);
        }

        /** 부하 패턴만 쓰고 1노드로 적분하는 형태. */
        public Spec(ThermalParams params, WorkloadMode mode, double targetFps,
                    double loadSec, RecoveryPolicy policy, double recoverySec,
                    double recoveryRJa, double dtSec, double sampleSec, boolean triggerOnThrottle,
                    AiLoadProfile aiLoad) {
            this(params, mode, targetFps, loadSec, policy, recoverySec, recoveryRJa,
                    dtSec, sampleSec, triggerOnThrottle, aiLoad, null);
        }

        /** 이 시각의 부하 배율(0~1). 패턴이 없으면 항상 1.0이라 기존 동작과 완전히 같다. */
        double loadLevelAt(double tSec) {
            return aiLoad == null ? 1.0 : aiLoad.levelAt(tSec);
        }
    }

    public ThermalRun run(BoardType board, Spec spec) {
        ThermalParams p = spec.params();
        double dt = spec.dtSec();
        double minRatio = p.minClockRatio();

        double t = 0.0;
        double temp = p.startTempC();
        // 2노드일 때 방열판 노드의 초기 온도. 유휴 상태에서 이미 열평형에 있었다고 보고
        // T_hs = T_soc − P_유휴·R_int 로 잡는다 — 방열판은 SoC보다 항상 차가우므로
        // 둘을 같은 값으로 두고 시작하면 첫 몇 초 동안 없던 열이 흘러 곡선이 왜곡된다.
        HeatsinkMass hs = spec.heatsink();
        double tempHs = hs == null ? temp
                : Math.max(p.ambientC(), temp - p.idlePowerW() * hs.rInternalKPerW());
        // 두 열저항 조건(부하 구간·회복 구간) 모두에서 분해가 성립하는지 루프 밖에서 먼저
        // 확인한다 — 적분 도중에 터지면 어느 조건이 문제인지 알기 어렵고 부분 결과만 남는다.
        int subSteps = 1;
        if (hs != null) {
            double rHsLoad = hs.heatsinkToAirKPerW(p.rJaKPerW());
            double rHsRecovery = hs.heatsinkToAirKPerW(spec.recoveryRJa());
            // 빠른 모드의 시정수 — 이보다 충분히 잘게 적분해야 명시적 오일러가 안정적이다.
            double tauFast = Math.min(p.cThJPerK() * hs.rInternalKPerW(),
                    hs.cThJPerK() * Math.min(rHsLoad, rHsRecovery));
            subSteps = (int) Math.max(1, Math.ceil(spec.dtSec() / (tauFast / 20.0)));
        }
        double clockRatio = 1.0;
        int govState = 0;               // 0=정상, 1=소프트 제한, 2=하드 스로틀링
        boolean throttled = false;

        List<ThermalRun.Sample> series = new ArrayList<>();
        List<ThermalRun.Episode> episodes = new ArrayList<>();
        Double episodeStart = null;
        double episodePeak = 0.0;

        Double softEntry = null;     // 소프트 제한(0x8) 최초 진입
        Double tttFirst = null;      // 최초 0x4 on
        Double tttCandidate = null;  // 지속 판정 대기 중
        Double ttt = null;           // 확정 TTT
        Double trtState = null, trtService = null, trtFull = null;
        Double stateClearCandidate = null, serviceCandidate = null;

        double peak = temp;
        double loadEndTemp = temp;
        double energyJ = 0.0;
        double fpsSum = 0.0, fpsSamples = 0.0, throttledTime = 0.0;
        double fpsBaseSum = 0.0, fpsBaseN = 0.0, fpsMin = Double.MAX_VALUE;
        boolean loadHadThrottling = false, serviceDegraded = false, recoveryGateComputed = false;

        // 서비스 회복 기준선 — 목표 FPS 모드면 목표치, 최대 처리량 모드면 무스로틀 최대치
        double serviceBaseline = spec.mode() == WorkloadMode.TARGET_FPS
                ? Math.min(spec.targetFps(), p.maxFps()) : p.maxFps();
        double baselineWindowSec = Math.min(10.0, spec.loadSec() * 0.1);

        boolean recovering = false;
        double recoveryStart = spec.loadSec();
        double endTime = spec.loadSec() + spec.recoverySec();
        double nextSampleAt = 0.0;

        while (t <= endTime + 1e-9) {
            // ── 회복 정책 적용 시점 판정 ────────────────────────────────────
            if (!recovering) {
                boolean trigger = spec.triggerOnThrottle() && spec.policy() != RecoveryPolicy.NONE
                        ? (ttt != null || t >= spec.loadSec())
                        : t >= spec.loadSec();
                if (trigger) {
                    recovering = true;
                    recoveryStart = t;
                    endTime = t + spec.recoverySec();
                }
            }
            String phase = recovering ? "RECOVERY" : "LOAD";

            double rJa = recovering ? spec.recoveryRJa() : p.rJaKPerW();
            boolean workloadOn = !(recovering && spec.policy() == RecoveryPolicy.R1_STOP);
            double effTarget = (recovering && spec.policy() == RecoveryPolicy.R2_LOW_LOAD)
                    ? spec.targetFps() * LOW_LOAD_FACTOR : spec.targetFps();

            // ── 클럭 거버너(히스테리시스 상태머신) ──────────────────────────
            if (temp >= p.hardLimitC()) govState = 2;
            else if (govState == 2 && temp < p.hardLimitC() - p.hysteresisC()) govState = 1;
            if (govState < 2) {
                if (temp >= p.softLimitC()) govState = 1;
                else if (temp < p.softLimitC() - p.hysteresisC()) govState = 0;
            }
            double ratioTarget = switch (govState) {
                case 2 -> minRatio;
                case 1 -> p.softFloorRatio();
                default -> 1.0;
            };
            clockRatio += (ratioTarget - clockRatio) * Math.min(1.0, dt / CLOCK_SLEW_TAU_SEC);
            clockRatio = Math.max(minRatio, Math.min(1.0, clockRatio));

            // ── 부하·전력 ─────────────────────────────────────────────────
            // AI 부하 패턴은 "요구하는 일의 양"에 곱해진다 — 회복 정책(R1 중지 / R2 25%)과는
            // 곱셈으로 합성되므로, 패턴을 켜도 정책의 의미가 그대로 유지된다.
            double level = spec.loadLevelAt(t);
            double achievableFps = p.maxFps() * clockRatio;
            double fps, util;
            if (!workloadOn) {
                fps = 0.0; util = 0.0;
            } else if (spec.mode() == WorkloadMode.TARGET_FPS) {
                double demand = effTarget * level;
                fps = Math.min(demand, achievableFps);
                util = Math.min(1.0, demand / Math.max(achievableFps, 1e-9));
            } else {
                // 최대 처리량 모드에서 배율은 "낼 수 있는 최대의 몇 %를 실제로 내는가"다.
                fps = achievableFps * level; util = level;
            }
            double powerW = p.idlePowerW() + p.dynamicPowerW() * util * clockRatio;

            // ── 스로틀링 비트(히스테리시스) ────────────────────────────────
            if (temp >= p.hardLimitC()) throttled = true;
            else if (temp < p.hardLimitC() - p.hysteresisC()) throttled = false;
            boolean softActive = govState >= 1;
            int bits = (throttled ? 0x4 : 0) | (softActive ? 0x8 : 0) | (clockRatio < 0.999 ? 0x2 : 0);

            // ── 지표 추출 ────────────────────────────────────────────────
            if (!recovering) {
                if (softActive && softEntry == null) softEntry = t;
                if (throttled) {
                    throttledTime += dt;
                    if (tttFirst == null) tttFirst = t;
                    if (tttCandidate == null) tttCandidate = t;
                    else if (ttt == null && t - tttCandidate >= THROTTLE_CONFIRM_SEC) ttt = tttCandidate;
                    if (episodeStart == null) { episodeStart = t; episodePeak = temp; }
                    else episodePeak = Math.max(episodePeak, temp);
                } else {
                    tttCandidate = null;
                    if (episodeStart != null) {
                        episodes.add(new ThermalRun.Episode(round(episodeStart, 1), round(t, 1),
                                round(t - episodeStart, 1), round(episodePeak, 2)));
                        episodeStart = null;
                    }
                }
                fpsSum += fps; fpsSamples++;
                if (t <= baselineWindowSec) { fpsBaseSum += fps; fpsBaseN++; }
                else fpsMin = Math.min(fpsMin, fps);
                loadEndTemp = temp;
            } else {
                if (!recoveryGateComputed) {
                    loadHadThrottling = throttled || !episodes.isEmpty();
                    double base = fpsBaseN > 0 ? fpsBaseSum / fpsBaseN : serviceBaseline;
                    double lowest = fpsMin == Double.MAX_VALUE ? base : fpsMin;
                    serviceDegraded = lowest < SERVICE_RECOVERY_RATIO * base;
                    recoveryGateComputed = true;
                }
                if (!throttled && loadHadThrottling) {
                    if (stateClearCandidate == null) stateClearCandidate = t;
                    else if (trtState == null && t - stateClearCandidate >= RECOVERY_CONFIRM_SEC) {
                        trtState = stateClearCandidate - recoveryStart;
                    }
                } else {
                    stateClearCandidate = null;
                }
                if (serviceDegraded && achievableFps >= SERVICE_RECOVERY_RATIO * serviceBaseline) {
                    if (serviceCandidate == null) serviceCandidate = t;
                    else if (trtService == null && t - serviceCandidate >= RECOVERY_CONFIRM_SEC) {
                        trtService = serviceCandidate - recoveryStart;
                    }
                } else {
                    serviceCandidate = null;
                }
                if (trtFull == null && temp <= p.idleSteadyTempC() + FULL_RECOVERY_MARGIN_C) {
                    trtFull = t - recoveryStart;
                }
            }

            peak = Math.max(peak, temp);
            energyJ += powerW * dt;

            if (t >= nextSampleAt - 1e-9) {
                series.add(new ThermalRun.Sample(round(t, 1), phase, round(temp, 2),
                        (int) Math.round(clockRatio * p.maxClockMhz()), round(fps, 2),
                        round(powerW, 3), throttled, "0x" + Integer.toHexString(bits)));
                nextSampleAt += spec.sampleSec();
            }

            // ── 적분 ─────────────────────────────────────────────────────
            if (hs == null) {
                temp += (powerW - (temp - p.ambientC()) / rJa) / p.cThJPerK() * dt;
            } else {
                // 2노드 — SoC가 내부 저항을 거쳐 방열판으로 열을 밀고, 방열판이 공기로 버린다.
                // rJa는 회복 구간에 달라질 수 있으므로(팬 가동) 방열판→공기 몫도 매 스텝 다시 나눈다.
                //
                // 부분 스텝으로 나누는 이유: 2노드는 시정수가 둘인데 SoC↔방열판 결합이
                // 만드는 빠른 쪽이 전체 시정수보다 훨씬 짧을 수 있다. 바깥 루프의 dt는
                // 전체 시정수 기준으로 정해지므로, 그대로 적분하면 빠른 모드에서 발산한다.
                double rHsToAir = hs.heatsinkToAirKPerW(rJa);
                double sub = dt / subSteps;
                for (int i = 0; i < subSteps; i++) {
                    double qToHeatsink = (temp - tempHs) / hs.rInternalKPerW();
                    double dSoc = (powerW - qToHeatsink) / p.cThJPerK();
                    double dHs = (qToHeatsink - (tempHs - p.ambientC()) / rHsToAir) / hs.cThJPerK();
                    temp += dSoc * sub;
                    tempHs += dHs * sub;
                }
            }
            t += dt;
        }
        if (episodeStart != null) {
            episodes.add(new ThermalRun.Episode(round(episodeStart, 1), null, null, round(episodePeak, 2)));
        }

        List<String> notes = new ArrayList<>();
        // 정상상태 온도는 반드시 "이번 실행의 부하"로 계산해야 한다. 목표 FPS 모드에서는
        // CPU가 목표치를 채우고 남는 시간을 쉬므로 사용률이 100%가 아니고(예: 목표 10FPS,
        // 최대 28FPS면 36%), 최대 처리량 기준으로 계산하면 실제로는 72℃에서 안정될 조건을
        // "110℃까지 오른다"고 보고하게 된다 — 시계열과 요약이 서로 모순되는 결과다.
        // 부하 패턴이 있으면 정상상태가 하나가 아니다. 보고하는 값은 진동의 중심(평균
        // 배율)으로 하고, "스로틀링이 일어날 수 있는가"는 상한(최대 배율)으로 판정한다 —
        // 평균으로 판정하면 피크에서 걸리는 스로틀링을 "안 걸린다"고 잘못 말하게 된다.
        double meanLevel = spec.aiLoad() == null ? 1.0 : spec.aiLoad().meanLevel();
        double peakLevel = spec.aiLoad() == null ? 1.0 : spec.aiLoad().peakLevel();
        double steady = steadyTempAt(p, spec, 1.0, meanLevel);
        double steadyAtPeak = steadyTempAt(p, spec, 1.0, peakLevel);
        boolean expected = steadyAtPeak > p.hardLimitC();
        double softSteady = steadyTempAt(p, spec, p.softFloorRatio(), meanLevel);

        // 부하 패턴을 쓴 경우, 결과를 읽기 전에 "이 패턴이 애초에 차이를 드러낼 수 있는
        // 시간 규모인가"부터 알려준다. 이게 없으면 느린 패턴을 넣고 "패턴을 넣었는데 왜
        // 순위가 그대로지?"라고 오해하게 된다(이 실험 설계에서 가장 빠지기 쉬운 함정).
        if (hs != null) {
            notes.add(String.format(
                    "2노드 모델로 계산했다 — 방열판을 별도 열 덩어리로 본다(열용량 %.1f J/K, SoC→방열판 %.2f K/W, 방열판→공기 %.2f K/W). "
                    + "정상상태 온도는 1노드와 같고 과도응답만 달라진다: 부하가 출렁일 때 방열판 질량이 피크를 흡수하므로, 열저항이 더 나쁜 방열판이 피크 온도에서는 이길 수 있다.",
                    hs.cThJPerK(), hs.rInternalKPerW(), hs.heatsinkToAirKPerW(p.rJaKPerW())));
            if (spec.aiLoad() == null || spec.aiLoad().isConstant()) {
                notes.add("다만 이번 실행은 부하가 일정해서 2노드로 바꾼 효과가 거의 드러나지 않는다 — 열용량 차이를 보려면 aiLoadProfileId로 burst나 mixed를 함께 지정할 것.");
            }
        }
        if (spec.aiLoad() != null) {
            AiLoadProfile lp = spec.aiLoad();
            notes.add(String.format("AI 부하 패턴 '%s' 적용 — 주기 %.0f초, 평균 배율 %.2f, 최대 배율 %.2f.",
                    lp.getLabel() != null ? lp.getLabel() : lp.getId(),
                    lp.cycleSeconds(), lp.meanLevel(), lp.peakLevel()));
            notes.add(lp.timescaleNote(p.tauSeconds()));
            if (!lp.isConstant()) {
                notes.add(String.format(
                        "부하가 오르내리므로 정상상태 온도가 하나로 정해지지 않는다 — 온도는 평균 배율 기준 %.1f℃를 중심으로 진동하며, 최대 배율을 계속 유지했다면 도달했을 상한은 %.1f℃다. 방열판 비교에는 이 진동의 꼭대기(peakTempC)를 쓸 것.",
                        steady, steadyAtPeak));
            }
        }
        if (!expected) {
            notes.add(String.format(
                    "이론 정상상태 온도 %.1f℃ < 하드 제한 %.1f℃ — 이 조건에서는 무한히 돌려도 스로틀링이 발생하지 않는다(TTT 없음).",
                    steady, p.hardLimitC()));
        } else if (softSteady <= p.hardLimitC() && ttt == null) {
            notes.add(String.format(
                    "소프트 제한(%.0f℃)에서 클럭을 %dMHz로 낮추면 정상상태가 %.1f℃로 하드 제한 아래에 머문다 — 그래서 0x4가 뜨지 않는다. "
                    + "실제 라즈베리파이도 같은 이유로 무냉각 상태에서 80℃ 부근에 '눌러앉는다'. 하드 스로틀링을 관측하려면 주변 온도를 올리거나 냉각을 더 나쁘게 해야 한다.",
                    p.softLimitC(), p.softFloorClockMhz(), softSteady));
        }
        if (softEntry != null && ttt == null) {
            notes.add(String.format(
                    "소프트 온도 제한에는 %.0f초에 진입했다 — 0x4는 뜨지 않았지만 클럭이 %dMHz로 낮아지므로 성능 저하는 이 시점부터 시작된다(softLimitEntrySec).",
                    softEntry, p.softFloorClockMhz()));
        }
        if (expected && ttt == null && softEntry == null) {
            notes.add(String.format(
                    "이론상 스로틀링이 예상되지만 부하 %.0f초 안에는 도달하지 못했다 — 시정수 τ=%.0f초이므로 최소 %.0f초는 돌려야 관측된다.",
                    spec.loadSec(), p.tauSeconds(), 3 * p.tauSeconds()));
        }
        if (spec.policy() != RecoveryPolicy.NONE && spec.triggerOnThrottle()) {
            notes.add(String.format("회복 정책을 %.0f초 시점(%s)에 적용했다.", recoveryStart,
                    ttt != null ? "스로틀링 확정 감지 직후" : "스로틀링이 없어 loadSeconds 도달 시점"));
        }
        if (spec.policy() == RecoveryPolicy.R1_STOP) {
            notes.add("R1(완전 중지)은 추론을 멈추므로 관측 FPS는 0이다. TRT_service는 '부하를 재개했다면 낼 수 있었을 FPS(달성 가능 FPS)' 기준으로 계산했다.");
        }
        if (spec.recoverySec() > 0 && !loadHadThrottling && recoveryGateComputed) {
            notes.add("부하 구간에 스로틀링이 없었으므로 TRT_state는 정의되지 않는다(null) — 회복 실험은 스로틀링이 실제로 걸린 조건에서 해야 의미가 있다.");
        }
        if (spec.recoverySec() > 0 && loadHadThrottling && trtState == null) {
            notes.add(String.format("회복 관찰 %.0f초 동안 스로틀링 비트가 %.0f초 연속 해제된 적이 없다 — 이 정책으로는 회복되지 않는다는 뜻이다.",
                    spec.recoverySec(), RECOVERY_CONFIRM_SEC));
        }
        if (trtFull == null && spec.recoverySec() > 0) {
            notes.add(String.format("회복 관찰 안에 완전 냉각(유휴 %.1f℃ + %.0f℃)에 도달하지 못했다 — 서비스를 유지하는 정책(R2·R3)은 원래 도달하지 못할 수 있고, R1이라면 recoverySeconds를 늘려야 한다.",
                    p.idleSteadyTempC(), FULL_RECOVERY_MARGIN_C));
        }

        List<Double> teds = episodes.stream().map(ThermalRun.Episode::durationSec)
                .filter(d -> d != null).sorted().toList();
        Double medianTed = teds.isEmpty() ? null
                : (teds.size() % 2 == 1 ? teds.get(teds.size() / 2)
                                        : (teds.get(teds.size() / 2 - 1) + teds.get(teds.size() / 2)) / 2.0);

        double meanFps = fpsSamples > 0 ? fpsSum / fpsSamples : 0.0;
        double baseFps = fpsBaseN > 0 ? fpsBaseSum / fpsBaseN : 0.0;
        double lowest = fpsMin == Double.MAX_VALUE ? baseFps : fpsMin;
        double drop = baseFps > 1e-9 ? (baseFps - lowest) / baseFps * 100.0 : 0.0;

        return new ThermalRun(
                board.label(), p,
                round(softEntry, 1), round(ttt, 1), round(tttFirst, 1), episodes, round(medianTed, 1),
                round(trtState, 1), round(trtService, 1), round(trtFull, 1),
                round(peak, 2), round(loadEndTemp, 2), round(steady, 2), expected,
                round(recoveryStart > 0 ? throttledTime / recoveryStart : 0.0, 3),
                round(meanFps, 2), round(drop, 1), round(p.tauSeconds(), 1), round(energyJ, 1),
                series, notes);
    }

    /** 부하 배율 1.0(상수 최대 부하) 기준 — 기존 호출부 하위호환. */
    static double steadyTempAt(ThermalParams p, Spec spec, double clockRatio) {
        return steadyTempAt(p, spec, clockRatio, 1.0);
    }

    /**
     * 주어진 클럭비·부하 배율에서의 이론 정상상태 온도(℃). 목표 FPS 모드는 클럭이 낮아지면
     * 오히려 사용률이 올라가(같은 FPS를 내려고 더 오래 돌린다) 소비전력이 단순 비례하지
     * 않으므로, 사용률까지 함께 계산한다.
     *
     * <p>부하 패턴을 쓰면 정상상태가 하나로 정해지지 않는다 — 온도가 계속 오르내리므로
     * "평균 배율에서의 정상상태"(진동의 중심)와 "최대 배율에서의 정상상태"(도달 가능한
     * 상한) 두 값이 각각 다른 질문에 답한다.
     *
     * @param loadLevel 부하 배율 0~1. 1.0이면 패턴 없음과 같다
     */
    static double steadyTempAt(ThermalParams p, Spec spec, double clockRatio, double loadLevel) {
        double achievableFps = p.maxFps() * clockRatio;
        double util = spec.mode() == WorkloadMode.TARGET_FPS
                ? Math.min(1.0, spec.targetFps() * loadLevel / Math.max(achievableFps, 1e-9))
                : loadLevel;
        return p.ambientC() + (p.idlePowerW() + p.dynamicPowerW() * util * clockRatio) * p.rJaKPerW();
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    static Double round(Double v, int digits) {
        return v == null ? null : round(v.doubleValue(), digits);
    }
}
