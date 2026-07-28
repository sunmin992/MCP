#!/usr/bin/env python3
"""라즈베리파이 발열·스로틀링 실측 로거 (R&E 실험용).

한 번 실행하면 다음 3단계를 자동으로 진행하며 1초 간격으로 로그를 남긴다.

    [1] BASELINE  유휴 상태 — 시작 온도와 실내 온도를 기록한다(필수! R_ja 계산의 기준)
    [2] LOAD      고부하 AI 추론 — 목표 FPS 또는 최대 처리량
    [3] RECOVERY  회복 정책(R1 완전중지 / R2 저부하 / R3 능동냉각 / none) 적용 후 관찰

출력은 두 파일이다.
    <run_id>.csv   시계열 — calibrate_edge_thermal_model 도구가 그대로 먹는 열 이름
    <run_id>.json  실행 메타데이터(보드·냉각조건·모델·실내온도·시작시각 …)

메타데이터를 반드시 함께 남기는 이유: 실험이 끝난 뒤에는 "이 CSV가 어떤 조건이었지?"를
복원할 방법이 없다. 조건을 잃은 데이터는 그래프를 그릴 수는 있어도 비교를 못 한다.

사용 예:
    # Pi5 + 방열판, 목표 15FPS로 15분 부하 후 팬을 켜서 10분 회복 관찰
    python3 measure_throttling.py --board pi5 --cooling passive \\
        --mode target_fps --target-fps 15 --load 900 \\
        --recovery r3_active_cooling --recovery-seconds 600 \\
        --ambient 26.5 --label "pi5-passive-15fps"

    # 라즈베리파이가 없는 곳에서 스크립트 동작만 확인(가짜 센서값)
    python3 measure_throttling.py --simulate --load 30 --recovery-seconds 20 --ambient 25

주의: 이 스크립트는 보드를 의도적으로 뜨겁게 만든다. 반드시 사람이 있는 곳에서,
가연물 없는 평평한 곳에 두고 돌릴 것. 90℃를 넘으면 자동으로 중단한다.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, asdict, field
from datetime import datetime, timezone

# ── 센서 읽기 ────────────────────────────────────────────────────────────────
# 라즈베리파이가 아닌 환경(개발용 노트북)에서도 스크립트를 끝까지 돌려볼 수 있도록
# 모든 읽기 함수는 실패하면 None을 돌려주고, --simulate 모드에서는 가짜 값을 만든다.

THERMAL_ZONE = "/sys/class/thermal/thermal_zone0/temp"
CPUFREQ = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"


def _vcgencmd(*args: str) -> str | None:
    try:
        out = subprocess.run(["vcgencmd", *args], capture_output=True, text=True, timeout=2)
        return out.stdout.strip() if out.returncode == 0 else None
    except (FileNotFoundError, subprocess.SubprocessError):
        return None


def read_temp_c() -> float | None:
    """SoC 온도(℃). vcgencmd가 없으면 sysfs로 폴백."""
    raw = _vcgencmd("measure_temp")
    if raw:
        m = re.search(r"([\d.]+)", raw)
        if m:
            return float(m.group(1))
    try:
        with open(THERMAL_ZONE) as f:
            return int(f.read().strip()) / 1000.0
    except OSError:
        return None


def read_clock_mhz() -> float | None:
    raw = _vcgencmd("measure_clock", "arm")
    if raw and "=" in raw:
        return int(raw.split("=")[1]) / 1_000_000.0
    try:
        with open(CPUFREQ) as f:
            return int(f.read().strip()) / 1000.0
    except OSError:
        return None


def read_throttled() -> int | None:
    """get_throttled 원본 비트. 0x1 저전압, 0x2 클럭제한, 0x4 스로틀링, 0x8 소프트제한
    (상위 16비트는 '부팅 후 발생한 적 있음' 이력)."""
    raw = _vcgencmd("get_throttled")
    if raw and "=" in raw:
        return int(raw.split("=")[1], 16)
    return None


def read_volts() -> float | None:
    raw = _vcgencmd("measure_volts", "core")
    if raw and "=" in raw:
        return float(raw.split("=")[1].rstrip("V"))
    return None


def read_fan_rpm() -> float | None:
    """Pi5 공식 쿨러 등 hwmon에 노출되는 팬 회전수."""
    import glob
    for path in glob.glob("/sys/class/hwmon/hwmon*/fan1_input"):
        try:
            with open(path) as f:
                return float(f.read().strip())
        except OSError:
            continue
    return None


def read_power_w(shunt_ohm: float | None = None) -> float | None:
    """소비전력(W). INA219/INA260 전력계가 있으면 여기서 읽도록 고쳐 쓴다.

    학교에서 흔한 조합을 주석으로 남겨 둔다 —
      from ina219 import INA219; ina = INA219(shunt_ohm, busnum=1); ina.configure()
      return ina.power() / 1000.0
    전력계가 없으면 None을 반환하고, 캘리브레이션 도구가 보드 기본 전력값으로 대체한다
    (정확도는 떨어지지만 실험 자체는 성립한다)."""
    return None


# ── 냉각팬 제어(R3 정책) ──────────────────────────────────────────────────────

def set_fan(level: int, cooling_device: str = "/sys/class/thermal/cooling_device0/cur_state") -> bool:
    """냉각팬 세기를 바꾼다(0=정지, 최대값=전속). Pi5 공식 쿨러는 cooling_device로 제어된다.
    GPIO PWM 팬을 쓴다면 이 함수만 학교 배선에 맞게 고치면 나머지는 그대로 동작한다."""
    try:
        with open(cooling_device, "w") as f:
            f.write(str(level))
        return True
    except OSError:
        return False


# ── 추론 부하 ────────────────────────────────────────────────────────────────

class InferenceLoad:
    """AI 추론 한 프레임에 해당하는 연산 부하.

    실제 실험에서는 여기를 학생이 쓰는 모델 추론 호출로 바꾸는 것이 가장 좋다
    (예: tflite Interpreter.invoke()). 모델을 아직 못 정했거나 비교 기준이 필요할 때를 위해
    numpy 행렬곱(없으면 순수 파이썬)으로 대체 부하를 제공한다 — 어느 쪽이든 CPU를 4코어로
    태우는 것이 목적이므로 발열 특성 실험에는 충분하다."""

    def __init__(self, size: int = 320, threads: int = 0):
        self.kind = "numpy-matmul"
        self.threads = threads or (os.cpu_count() or 4)
        try:
            import numpy as np  # noqa: F401
            self._np = np
            self._a = np.random.rand(size, size).astype("float32")
            self._b = np.random.rand(size, size).astype("float32")
        except ImportError:
            self._np = None
            self.kind = "python-fallback"
            self._n = size // 8

    def frame(self) -> None:
        """추론 1프레임 분량의 연산."""
        if self._np is not None:
            for _ in range(self.threads):
                self._a @ self._b
        else:
            s = 0.0
            for i in range(self._n * 2000):
                s += math.sqrt(i + 1.0)


# ── 실행 ─────────────────────────────────────────────────────────────────────

@dataclass
class RunMeta:
    run_id: str
    label: str
    board: str
    cooling: str
    mode: str
    target_fps: float
    load_seconds: float
    recovery_policy: str
    recovery_seconds: float
    baseline_seconds: float
    ambient_temp_c: float
    model: str
    precision: str
    started_at_iso: str = ""
    finished_at_iso: str = ""
    load_kind: str = ""
    notes: str = ""
    sample_interval_sec: float = 1.0
    columns: list = field(default_factory=list)


CSV_COLUMNS = ["t_sec", "iso_time", "phase", "soc_temp_c", "clock_mhz",
               "throttled", "throttled_bits", "power_w", "volts", "fan_rpm", "fps"]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--board", default="pi5", choices=["pi4", "pi5"])
    ap.add_argument("--cooling", default="passive", choices=["bare", "passive", "active"])
    ap.add_argument("--mode", default="target_fps", choices=["target_fps", "max_throughput"])
    ap.add_argument("--target-fps", type=float, default=10.0)
    ap.add_argument("--baseline", type=float, default=120.0, help="유휴 기준선 측정 시간(초)")
    ap.add_argument("--load", type=float, default=900.0, help="고부하 유지 시간(초)")
    ap.add_argument("--recovery", default="none",
                    choices=["none", "r1_stop", "r2_low_load", "r3_active_cooling"])
    ap.add_argument("--recovery-seconds", type=float, default=600.0)
    ap.add_argument("--ambient", type=float, required=False,
                    help="실내(주변) 온도 ℃ — 반드시 온도계로 재서 넣을 것. 없으면 분석 정확도가 크게 떨어진다")
    ap.add_argument("--label", default="", help="조건 라벨(예: pi5-passive-int8-26C)")
    ap.add_argument("--model", default="unspecified", help="추론 모델 이름")
    ap.add_argument("--precision", default="fp32", choices=["fp32", "fp16", "int8"])
    ap.add_argument("--interval", type=float, default=1.0, help="샘플링 간격(초)")
    ap.add_argument("--outdir", default="runs")
    ap.add_argument("--max-temp", type=float, default=90.0, help="안전 중단 온도 ℃")
    ap.add_argument("--fan-max", type=int, default=4, help="R3에서 쓸 냉각팬 최대 단계")
    ap.add_argument("--simulate", action="store_true",
                    help="라즈베리파이 없이 스크립트 흐름만 검증(가짜 센서값)")
    args = ap.parse_args()

    if args.ambient is None and not args.simulate:
        print("[!] --ambient 를 넣지 않았다. 주변 온도 없이는 열저항 R_ja를 계산할 수 없다.", file=sys.stderr)
        print("    온도계로 실내 온도를 재서 다시 실행할 것(그게 이 실험에서 가장 자주 빠뜨리는 값이다).", file=sys.stderr)
        return 2

    run_id = f"{args.board}-{args.cooling}-{args.mode}-{datetime.now().strftime('%m%d-%H%M%S')}"
    os.makedirs(args.outdir, exist_ok=True)
    csv_path = os.path.join(args.outdir, run_id + ".csv")
    meta_path = os.path.join(args.outdir, run_id + ".json")

    load = InferenceLoad()
    meta = RunMeta(run_id=run_id, label=args.label or run_id, board=args.board,
                   cooling=args.cooling, mode=args.mode, target_fps=args.target_fps,
                   load_seconds=args.load, recovery_policy=args.recovery,
                   recovery_seconds=args.recovery_seconds, baseline_seconds=args.baseline,
                   ambient_temp_c=args.ambient if args.ambient is not None else float("nan"),
                   model=args.model, precision=args.precision,
                   started_at_iso=datetime.now(timezone.utc).isoformat(),
                   load_kind=load.kind, sample_interval_sec=args.interval,
                   columns=CSV_COLUMNS)

    print(f"[i] run_id={run_id}")
    print(f"[i] 부하 종류={load.kind}  단계: 기준선 {args.baseline:.0f}s → 부하 {args.load:.0f}s "
          f"→ 회복({args.recovery}) {args.recovery_seconds:.0f}s")
    print(f"[i] 출력: {csv_path}")

    t_zero = time.time()
    # 기준선·부하·회복의 경계 시각(초). 캘리브레이션 도구의 loadEndSeconds가 곧 t_load_end다.
    t_load_start = args.baseline
    t_load_end = args.baseline + args.load
    t_end = t_load_end + args.recovery_seconds

    frames = 0
    last_fps_mark = 0.0
    fps = 0.0
    aborted = ""
    sim_temp = 45.0

    with open(csv_path, "w", encoding="utf-8") as f:
        f.write(",".join(CSV_COLUMNS) + "\n")
        next_sample = 0.0
        try:
            while True:
                now = time.time() - t_zero
                if now >= t_end:
                    break
                phase = ("BASELINE" if now < t_load_start
                         else "LOAD" if now < t_load_end else "RECOVERY")

                # ── 회복 정책 적용(경계를 넘는 순간 한 번) ──────────────────
                if phase == "RECOVERY" and args.recovery == "r3_active_cooling":
                    set_fan(args.fan_max)

                # ── 부하 실행 ────────────────────────────────────────────
                run_frame = False
                if phase == "LOAD":
                    run_frame = True
                elif phase == "RECOVERY":
                    run_frame = args.recovery in ("r2_low_load", "r3_active_cooling")

                if run_frame:
                    target = args.target_fps
                    if phase == "RECOVERY" and args.recovery == "r2_low_load":
                        target *= 0.25
                    if args.mode == "target_fps":
                        # 목표 FPS 페이싱 — 한 프레임 처리 후 남는 시간은 쉰다
                        frame_start = time.time()
                        load.frame()
                        frames += 1
                        sleep = max(0.0, 1.0 / target - (time.time() - frame_start))
                        time.sleep(sleep)
                    else:
                        load.frame()
                        frames += 1
                else:
                    time.sleep(0.05)

                # ── 샘플링 ───────────────────────────────────────────────
                now = time.time() - t_zero
                if now < next_sample:
                    continue
                next_sample += args.interval

                if now - last_fps_mark >= 1.0:
                    fps = frames / (now - last_fps_mark)
                    frames = 0
                    last_fps_mark = now

                if args.simulate:
                    # 1차 RC 응답 흉내 — 스크립트 흐름 점검용 가짜 값
                    drive = 85.0 if phase == "LOAD" else 40.0
                    sim_temp += (drive - sim_temp) / 60.0
                    temp, clock, bits = sim_temp, 2400.0, (0x4 if sim_temp >= 85 else 0)
                    volts, rpm, power = 0.9, None, None
                else:
                    temp = read_temp_c()
                    clock = read_clock_mhz()
                    bits = read_throttled()
                    volts = read_volts()
                    rpm = read_fan_rpm()
                    power = read_power_w()

                throttled = "" if bits is None else int(bool(bits & 0x4))
                f.write("{:.1f},{},{},{},{},{},{},{},{},{},{:.2f}\n".format(
                    now, datetime.now(timezone.utc).isoformat(), phase,
                    "" if temp is None else f"{temp:.1f}",
                    "" if clock is None else f"{clock:.0f}",
                    throttled,
                    "" if bits is None else hex(bits),
                    "" if power is None else f"{power:.3f}",
                    "" if volts is None else f"{volts:.3f}",
                    "" if rpm is None else f"{rpm:.0f}",
                    fps))
                f.flush()

                if temp is not None and temp >= args.max_temp:
                    aborted = f"안전 한계 {args.max_temp}℃ 초과({temp:.1f}℃) — 자동 중단"
                    print("[!] " + aborted, file=sys.stderr)
                    break

                if int(now) % 30 == 0:
                    print(f"    {now:6.0f}s [{phase:8}] {temp if temp is None else round(temp,1)}℃ "
                          f"{'' if clock is None else str(round(clock))+'MHz'} "
                          f"throttled={throttled} fps={fps:.1f}")
        except KeyboardInterrupt:
            aborted = "사용자 중단(Ctrl-C)"
        finally:
            if args.recovery == "r3_active_cooling":
                set_fan(args.fan_max)   # 안전: 끝나도 팬은 켠 채로 둔다

    meta.finished_at_iso = datetime.now(timezone.utc).isoformat()
    meta.notes = aborted
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(asdict(meta), f, ensure_ascii=False, indent=2)

    print(f"[✓] 완료: {csv_path}")
    print(f"[✓] 메타: {meta_path}")
    print(f"[→] 다음: python3 csv_to_mcp_payload.py {csv_path} --load-end {t_load_end:.0f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
