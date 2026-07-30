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

워크로드 종류를 바꿔 발열 특성을 비교하려면 --load-kind 를 쓴다(InferenceLoad 참고).
연산 병목(tflite)과 메모리 대역폭 병목(llama)은 온도 곡선 모양부터 다르게 나올 수 있다.

    # 연산 병목 — TFLite 이미지 추론
    python3 measure_throttling.py --board pi5 --cooling bare --mode max_throughput \\
        --load-kind tflite --load-model models/mobilenet_v2_int8.tflite \\
        --model mobilenet-v2 --precision int8 --load 900 --ambient 26.5

    # 메모리 병목 — llama.cpp 텍스트 생성
    python3 measure_throttling.py --board pi5 --cooling bare --mode max_throughput \\
        --load-kind llama --load-model models/qwen2.5-1.5b-q4.gguf --load-tokens 32 \\
        --model qwen2.5-1.5b --precision int8 --load 900 --ambient 26.5

종류가 다르면 CSV의 fps 열 단위가 달라(frames vs generations) 서로 비교할 수 없다.
작업 간 비교는 단위가 같은 전력·온도·TTT로 하고, 성능 저하는 각자의 기준선 대비 %로 본다.

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
    """AI 추론 한 프레임에 해당하는 연산 부하 — 워크로드 종류를 바꿔 끼울 수 있다.

    "어떤 AI 작업이 더 뜨거운가"는 물리적으로 "어떤 작업이 지속적으로 더 많은 전력을
    끌어쓰는가"와 같은 질문이다. 그런데 그 답은 모델 크기가 아니라 **어느 자원이
    병목인가**로 갈린다 — 연산(ALU) 병목이면 코어를 꽉 채워 계속 태우고, 메모리 대역폭
    병목이면 ALU가 데이터를 기다리며 노는 구간이 생겨 같은 "큰 모델"이라도 전력이 낮을
    수 있다. 그래서 워크로드 종류 자체가 실험 요인이 될 수 있고, 이 클래스가 그 요인을
    바꿔 끼우는 자리다.

    --load-kind 로 고른다.

    ===========  =============================================================
    matmul       행렬곱(기본). 모델 파일 없이 CPU를 태우는 합성 부하 —
                 기준선·장비 점검용. numpy가 없으면 순수 파이썬으로 폴백한다
    tflite       TFLite 이미지 추론. **연산 병목의 대표** — 매 프레임 같은
                 연산이라 재현성이 가장 높다. --load-model 에 .tflite 경로
    llama        llama.cpp 텍스트 생성. **메모리 대역폭 병목의 대표** —
                 프리필(병렬)과 디코드(순차)가 갈려 사용률이 출렁이므로
                 온도 곡선 모양이 위 둘과 다르게 나올 수 있다.
                 --load-model 에 .gguf 경로, --load-tokens 로 프레임당 토큰 수
    ===========  =============================================================

    **백엔드가 없거나 모델을 못 열면 조용히 대체하지 않고 실패한다.** "tflite를 쟀다"고
    적어 둔 메타데이터 옆에 실제로는 행렬곱을 잰 CSV가 남으면, 그 데이터는 나중에
    되돌릴 방법이 없다. 다만 --simulate(흐름 점검)일 때는 예외로 경고 후 matmul로
    넘어간다 — 그쪽은 애초에 센서값도 가짜라 데이터로 쓰지 않기 때문이다.

    frame() 한 번이 처리하는 단위가 종류마다 다르다(:attr:`unit`). CSV의 fps 열은 그
    단위의 초당 개수이므로 **종류가 다르면 fps를 서로 비교할 수 없다** — 작업 간 비교는
    단위가 같은 전력·온도·TTT로 하고, 성능 저하는 각자의 기준선 대비 %로 본다.
    """

    #: frame() 한 번이 무엇을 처리하는지 = CSV fps 열의 단위.
    UNITS = {"matmul": "frames", "tflite": "frames", "llama": "generations"}

    def __init__(self, kind: str = "matmul", model_path: str | None = None,
                 tokens: int = 32, size: int = 320, threads: int = 0):
        self.kind = kind
        self.unit = self.UNITS.get(kind, "frames")
        self.model_path = model_path
        self.tokens = tokens
        self.threads = threads or (os.cpu_count() or 4)
        self.detail = ""
        self._np = None
        self._interp = None
        self._llm = None

        if kind == "matmul":
            self._init_matmul(size)
        elif kind == "tflite":
            self._init_tflite()
        elif kind == "llama":
            self._init_llama()
        else:
            raise ValueError(f"알 수 없는 부하 종류: {kind}")

    # ── 종류별 준비 ─────────────────────────────────────────────────────────

    def _init_matmul(self, size: int) -> None:
        try:
            import numpy as np
            self._np = np
            self._a = np.random.rand(size, size).astype("float32")
            self._b = np.random.rand(size, size).astype("float32")
            self.kind = "numpy-matmul"
            self.detail = f"{size}x{size} float32 x{self.threads}"
        except ImportError:
            # 합성 부하 안에서의 폴백은 "다른 실험"이 아니라 같은 목적(코어 태우기)의
            # 저속 구현이므로 허용한다. 무엇으로 돌았는지는 kind에 남는다.
            self.kind = "python-fallback"
            self._n = size // 8
            self.detail = "numpy 없음 — 순수 파이썬"

    def _init_tflite(self) -> None:
        if not self.model_path:
            raise ValueError("tflite 부하에는 --load-model <모델.tflite> 가 필요하다")
        import numpy as np
        self._np = np
        try:                                    # 라즈베리파이는 보통 런타임만 설치한다
            from tflite_runtime.interpreter import Interpreter
        except ImportError:
            from tensorflow.lite import Interpreter   # 전체 TF가 깔린 환경

        self._interp = Interpreter(model_path=self.model_path, num_threads=self.threads)
        self._interp.allocate_tensors()
        inp = self._interp.get_input_details()[0]
        self._in_index = inp["index"]
        # 입력은 한 번 만들어 매 프레임 같은 것을 넣는다 — 입력이 바뀌면 연산량이
        # 달라져 다른 실험이 된다(전처리 시간이 측정에 섞이는 것도 막는다).
        shape, dtype = inp["shape"], inp["dtype"]
        if np.issubdtype(dtype, np.integer):
            self._input = np.random.randint(0, 256, size=shape).astype(dtype)
        else:
            self._input = np.random.rand(*shape).astype(dtype)
        self.detail = f"{os.path.basename(self.model_path)} input={list(shape)} threads={self.threads}"

    def _init_llama(self) -> None:
        if not self.model_path:
            raise ValueError("llama 부하에는 --load-model <모델.gguf> 가 필요하다")
        from llama_cpp import Llama
        self._llm = Llama(model_path=self.model_path, n_threads=self.threads, verbose=False)
        self._prompt = "Explain how a heat sink works in one paragraph."
        self.detail = (f"{os.path.basename(self.model_path)} "
                       f"tokens/frame={self.tokens} threads={self.threads}")

    # ── 프레임 실행 ─────────────────────────────────────────────────────────

    def frame(self) -> None:
        """추론 1프레임(:attr:`unit` 하나) 분량의 연산."""
        if self._interp is not None:
            self._interp.set_tensor(self._in_index, self._input)
            self._interp.invoke()
        elif self._llm is not None:
            # 매 프레임 KV 캐시를 비운다 — 안 비우면 두 번째 프레임부터 프리필이
            # 생략돼 디코드만 재는 셈이 되고, 프레임마다 연산량이 달라진다.
            self._llm.reset()
            self._llm.create_completion(self._prompt, max_tokens=self.tokens)
        elif self._np is not None:
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
    #: fps 열의 단위(frames/generations) — 종류가 다르면 fps를 서로 비교할 수 없다.
    load_unit: str = "frames"
    load_detail: str = ""
    load_model_path: str = ""
    #: AI 부하 패턴 — 서버 시뮬레이터와 같은 파일을 썼음을 남긴다. 없으면 상수 부하.
    #: 이 값이 없으면 나중에 "이 CSV가 어느 패턴이었나"를 복원할 방법이 없다.
    #: 동시/순차 스케줄 — 이 값이 없으면 같은 총작업량의 두 실행을 구분할 수 없다.
    schedule: str = "sequential"
    jobs: int = 1
    threads_per_job: int = 0
    load_pattern_id: str = ""
    load_pattern_file: str = ""
    load_pattern_cycle_sec: float = 0.0
    load_pattern_mean_level: float = 1.0
    notes: str = ""
    sample_interval_sec: float = 1.0
    columns: list = field(default_factory=list)


class AiLoadPattern:
    """AI 데이터센터식 시변 부하 패턴 — 시각에 따라 부하 배율을 바꾼다.

    **서버 시뮬레이터와 같은 JSON을 읽는다**(`src/main/resources/edge/ai-load-*.json`).
    이게 이 클래스의 존재 이유다 — 패턴을 여기서 따로 정의하면 시뮬레이션과 실측이
    "비슷하지만 다른" 조건이 되어, 보고서의 핵심 표(시뮬 vs 실측 비교)를 채울 수 없다.
    한쪽만 고쳐도 어긋나므로 정의는 한 곳에만 둔다.

    구간(segment) 목록을 순서대로 재생하고 주기가 끝나면 처음부터 반복한다.
    배율 1.0이 "그 실행의 기준 부하를 100% 낸다"는 뜻이다.

    부하를 줄이는 방법은 운용 모드마다 다르다.
      target_fps      목표 FPS 자체를 배율만큼 낮춘다(더 자주 쉰다)
      max_throughput  최대 속도로 돌리되 듀티비를 배율에 맞춘다 — 계산 자체를 느리게
                      할 수는 없으므로 "일한 시간 / 전체 시간"을 배율로 맞춘다.
                      시뮬레이터가 사용률(util)을 배율로 두는 것과 같은 처리다.
    """

    #: 배율이 이보다 작으면 사실상 유휴로 본다(0이면 대기 시간이 무한이 된다).
    MIN_LEVEL = 0.02

    def __init__(self, path: str):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        self.id = data.get("id") or os.path.splitext(os.path.basename(path))[0]
        self.label = data.get("label", self.id)
        self.segments = [(float(s["durationSec"]), float(s["level"]))
                         for s in data.get("segments", []) if float(s["durationSec"]) > 0]
        if not self.segments:
            raise ValueError(f"부하 패턴에 구간이 없다: {path}")
        self.cycle = sum(d for d, _ in self.segments)

    def level_at(self, t_sec: float) -> float:
        """이 시각의 부하 배율(0~1). 주기를 넘으면 처음부터 반복한다."""
        t = t_sec % self.cycle
        acc = 0.0
        for dur, level in self.segments:
            acc += dur
            if t < acc:
                return min(1.0, max(0.0, level))
        return min(1.0, max(0.0, self.segments[-1][1]))

    def mean_level(self) -> float:
        return sum(lv * d for d, lv in self.segments) / self.cycle

    def peak_level(self) -> float:
        return max(lv for _, lv in self.segments)

    @staticmethod
    def resolve(name: str, explicit_dir: str | None = None) -> str:
        """패턴 이름(steady/burst/mixed) 또는 파일 경로를 실제 경로로 바꾼다.

        스크립트 위치에서 서버 리소스 디렉터리를 거슬러 찾는다 — 같은 저장소를
        clone하기만 하면 경로 설정 없이 동작해야 하기 때문이다.
        """
        if os.path.exists(name):
            return name
        here = os.path.dirname(os.path.abspath(__file__))
        candidates = []
        if explicit_dir:
            candidates.append(os.path.join(explicit_dir, f"ai-load-{name}.json"))
        candidates.append(os.path.normpath(
            os.path.join(here, "..", "..", "src", "main", "resources", "edge", f"ai-load-{name}.json")))
        for c in candidates:
            if os.path.exists(c):
                return c
        raise FileNotFoundError(
            f"부하 패턴 '{name}'을 찾지 못했다. 찾아본 경로: {candidates} — "
            "저장소 밖에서 실행한다면 --load-pattern-dir 로 리소스 폴더를 지정할 것.")


class LoadRunner:
    """추론 부하를 워커 스레드에서 돌리며 <b>동시 실행과 순차 실행을 비교</b>할 수 있게 한다.

    <h3>왜 스레드로 옮겼는가</h3>
    열적으로 의미 있는 변수는 "몇 개의 스레드가 동시에 계산하고 있는가"다. 한 모델이
    코어를 다 쓰면(기존 기본값 num_threads=4) 인스턴스를 늘려도 OS가 시분할할 뿐이라
    소비전력이 늘지 않아 동시/순차가 열적으로 구분되지 않는다. 그래서 인스턴스마다
    스레드 수를 나눠 주고(--threads-per-job), 몇 개를 동시에 돌릴지를 스케줄로 정한다.

        sequential   워커 1개가 인스턴스들을 번갈아 한 프레임씩 처리 →
                     동시에 계산하는 스레드 = threads_per_job
        concurrent   워커 N개가 각자 자기 인스턴스를 계속 처리 →
                     동시에 계산하는 스레드 = jobs × threads_per_job

    총 작업량은 같고 시간 분포와 순간 전력만 다르다 — "같은 일을 몰아서 하나 펼쳐서
    하나"의 비교이고, 이것이 AI 데이터센터의 스케줄링 의사결정과 대응한다.

    <h3>GIL</h3>
    tflite의 invoke()와 numpy 행렬곱은 C 레벨에서 GIL을 놓으므로 스레드로 실제 병렬이
    된다. 순수 파이썬 폴백(numpy 없음)만 GIL에 막히므로 그 경우 경고한다.
    """

    def __init__(self, instances: list, schedule: str, mode: str, target_fps: float):
        self.instances = instances
        self.schedule = schedule
        self.mode = mode
        self.target_fps = target_fps
        self.jobs = len(instances)
        #: 워커별 누적 프레임 수. 각 워커가 자기 칸만 통째로 덮어써서 락이 필요 없다.
        self.counts = [0] * self.jobs
        #: 메인 루프가 갱신하는 지시 상태 — (돌릴지, 부하 배율).
        self._run = False
        self._level = 1.0
        self._stop = False
        self._threads: list = []

    # ── 메인 루프가 호출 ────────────────────────────────────────────────
    def set_state(self, run: bool, level: float) -> None:
        self._level = level
        self._run = run

    def total_frames(self) -> int:
        return sum(self.counts)

    def active_jobs(self) -> int:
        """지금 계산 중인 인스턴스 수 — CSV에 남겨 전력·온도와 맞춰 본다."""
        if not self._run:
            return 0
        return self.jobs if self.schedule == "concurrent" else 1

    def busy_threads(self, threads_per_job: int) -> int:
        return self.active_jobs() * threads_per_job

    def start(self) -> None:
        import threading
        if self.schedule == "concurrent":
            for i in range(self.jobs):
                t = threading.Thread(target=self._worker_one, args=(i,), daemon=True)
                t.start()
                self._threads.append(t)
        else:
            t = threading.Thread(target=self._worker_rotating, daemon=True)
            t.start()
            self._threads.append(t)

    def stop(self) -> None:
        self._stop = True
        for t in self._threads:
            t.join(timeout=3.0)

    # ── 워커 ──────────────────────────────────────────────────────────
    def _worker_one(self, idx: int) -> None:
        """concurrent — 자기 인스턴스만 계속 처리한다."""
        inst = self.instances[idx]
        local = 0
        while not self._stop:
            if not self._pace(lambda: inst.frame()):
                continue
            local += 1
            self.counts[idx] = local

    def _worker_rotating(self) -> None:
        """sequential — 인스턴스들을 번갈아 한 프레임씩. 동시에 도는 건 항상 하나다."""
        locals_ = [0] * self.jobs
        idx = 0
        while not self._stop:
            inst = self.instances[idx]
            if not self._pace(lambda: inst.frame()):
                continue
            locals_[idx] += 1
            self.counts[idx] = locals_[idx]
            idx = (idx + 1) % self.jobs      # 인스턴스마다 작업량을 고르게 준다

    def _pace(self, do_frame) -> bool:
        """한 프레임 실행 + 모드별 페이싱. 실행하지 않았으면 False."""
        if not self._run:
            time.sleep(0.02)
            return False
        level = self._level
        if level < AiLoadPattern.MIN_LEVEL:
            time.sleep(0.02)
            return False

        start = time.time()
        do_frame()
        busy = time.time() - start

        if self.mode == "target_fps":
            # 인스턴스가 여러 개면 각자 목표의 1/N을 담당해 합이 목표가 되게 한다.
            per_job_target = max(self.target_fps * level / max(self.jobs, 1), 1e-6)
            time.sleep(max(0.0, 1.0 / per_job_target - busy))
        elif level < 0.999:
            # 최대 처리량 — 계산을 느리게 할 수 없으므로 듀티비를 배율에 맞춘다.
            time.sleep(max(0.0, busy * (1.0 / level - 1.0)))
        return True


CSV_COLUMNS = ["t_sec", "iso_time", "phase", "load_level", "active_jobs", "soc_temp_c", "clock_mhz",
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
    ap.add_argument("--load-kind", default="matmul", choices=["matmul", "tflite", "llama"],
                    help="부하 종류 — matmul(합성 기준선) / tflite(연산 병목) / llama(메모리 병목)")
    ap.add_argument("--load-model", default=None,
                    help="tflite(.tflite)·llama(.gguf) 모델 파일 경로")
    ap.add_argument("--load-tokens", type=int, default=32,
                    help="llama: frame() 한 번에 생성할 토큰 수")
    ap.add_argument("--schedule", default="sequential", choices=["sequential", "concurrent"],
                    help="여러 모델 인스턴스를 순차로 돌릴지 동시에 돌릴지. 총 작업량은 같고 "
                         "순간 전력과 소요 시간이 달라진다 — 같은 일을 '펼쳐서' 하나 '몰아서' 하나의 비교. "
                         "--jobs 1이면 둘이 같다")
    ap.add_argument("--jobs", type=int, default=1,
                    help="모델 인스턴스 개수(기본 1). 동시/순차 비교를 하려면 코어 수(보통 4)로 둘 것")
    ap.add_argument("--threads-per-job", type=int, default=0,
                    help="인스턴스당 스레드 수. 0이면 자동(코어수÷jobs) — jobs=1이면 전체 코어를 "
                         "쓰므로 기존 동작과 같고, jobs=4면 각 1스레드가 되어 동시/순차가 "
                         "열적으로 실제로 구분된다")
    ap.add_argument("--load-pattern", default=None,
                    help="AI 부하 패턴 — steady/burst/mixed 또는 JSON 경로. "
                         "서버 시뮬레이터와 같은 파일을 읽으므로 시뮬과 실측을 같은 조건으로 비교할 수 있다. "
                         "생략하면 부하가 일정하다(기존 동작)")
    ap.add_argument("--load-pattern-dir", default=None,
                    help="패턴 JSON 폴더를 직접 지정(저장소 밖에서 실행할 때)")
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

    # 부하 준비 실패는 조용히 넘기지 않는다 — 무엇을 측정했는지 알 수 없는 데이터가
    # 남는 것이 실행이 멈추는 것보다 나쁘다(--simulate는 데이터로 쓰지 않으므로 예외).
    jobs = max(1, args.jobs)
    # 스레드 배분 — 자동이면 코어를 인스턴스 수로 나눈다. jobs=1이면 전체 코어를 쓰므로
    # 기존 동작과 같고, jobs=4면 각 1스레드가 되어 동시/순차의 전력 차이가 실제로 생긴다.
    threads_per_job = args.threads_per_job or max(1, (os.cpu_count() or 4) // jobs)

    try:
        instances = [InferenceLoad(kind=args.load_kind, model_path=args.load_model,
                                   tokens=args.load_tokens, threads=threads_per_job)
                     for _ in range(jobs)]
    except Exception as e:
        if args.simulate:
            print(f"[!] '{args.load_kind}' 부하를 준비하지 못해 matmul로 대체한다(--simulate): {e}")
            instances = [InferenceLoad(kind="matmul", threads=threads_per_job) for _ in range(jobs)]
        else:
            print(f"[x] '{args.load_kind}' 부하를 준비하지 못했다: {e}")
            print("    다른 부하로 조용히 바꾸면 이 CSV가 무엇을 잰 것인지 알 수 없게 되므로 중단한다.")
            print("    백엔드 설치(tflite-runtime / llama-cpp-python)와 --load-model 경로를 확인할 것.")
            return 2
    load = instances[0]      # 메타데이터용 대표 인스턴스(종류·단위·상세는 모두 같다)

    # 순수 파이썬 폴백은 GIL에 막혀 스레드가 병렬로 돌지 않는다 — 동시 실행이
    # 이름만 동시가 되어 전력이 안 올라가므로, 그 조합은 실험이 성립하지 않는다.
    if jobs > 1 and load.kind == "python-fallback":
        print("[!] 순수 파이썬 부하는 GIL 때문에 동시 실행이 실제로 병렬이 되지 않는다 —"
              " numpy를 설치하거나 --load-kind tflite 를 쓸 것.", file=sys.stderr)

    # 부하 패턴도 실패를 조용히 넘기지 않는다 — 패턴을 줬다고 믿었는데 상수 부하로
    # 돌아간 CSV가 남으면, 나중에 그 데이터로 순위를 비교하는 순간 결론이 틀어진다.
    pattern = None
    if args.load_pattern:
        try:
            pattern_path = AiLoadPattern.resolve(args.load_pattern, args.load_pattern_dir)
            pattern = AiLoadPattern(pattern_path)
        except Exception as e:
            print(f"[x] 부하 패턴을 읽지 못했다: {e}", file=sys.stderr)
            return 2

    meta = RunMeta(run_id=run_id, label=args.label or run_id, board=args.board,
                   cooling=args.cooling, mode=args.mode, target_fps=args.target_fps,
                   load_seconds=args.load, recovery_policy=args.recovery,
                   recovery_seconds=args.recovery_seconds, baseline_seconds=args.baseline,
                   ambient_temp_c=args.ambient if args.ambient is not None else float("nan"),
                   model=args.model, precision=args.precision,
                   started_at_iso=datetime.now(timezone.utc).isoformat(),
                   load_kind=load.kind, load_unit=load.unit, load_detail=load.detail,
                   load_model_path=args.load_model or "",
                   schedule=args.schedule, jobs=jobs, threads_per_job=threads_per_job,
                   load_pattern_id=pattern.id if pattern else "",
                   load_pattern_file=os.path.basename(pattern_path) if pattern else "",
                   load_pattern_cycle_sec=pattern.cycle if pattern else 0.0,
                   load_pattern_mean_level=pattern.mean_level() if pattern else 1.0,
                   sample_interval_sec=args.interval,
                   columns=CSV_COLUMNS)

    print(f"[i] run_id={run_id}")
    print(f"[i] 부하 종류={load.kind} ({load.unit}/s)"
          + (f"  {load.detail}" if load.detail else ""))
    if pattern:
        print(f"[i] 부하 패턴={pattern.id} '{pattern.label}' — 주기 {pattern.cycle:.0f}s, "
              f"평균 배율 {pattern.mean_level():.2f}, 최대 {pattern.peak_level():.2f}")
        print(f"[i]   ({pattern_path})")
    else:
        print("[i] 부하 패턴=없음(일정한 부하)")
    busy = jobs * threads_per_job if args.schedule == "concurrent" else threads_per_job
    print(f"[i] 스케줄={args.schedule}  인스턴스 {jobs}개 × {threads_per_job}스레드 "
          f"→ 동시 계산 스레드 {busy}개 / 코어 {os.cpu_count()}")
    print(f"[i] 단계: 기준선 {args.baseline:.0f}s → 부하 {args.load:.0f}s "
          f"→ 회복({args.recovery}) {args.recovery_seconds:.0f}s")
    print(f"[i] 출력: {csv_path}")

    runner = LoadRunner(instances, args.schedule, args.mode, args.target_fps)
    runner.start()

    t_zero = time.time()
    # 기준선·부하·회복의 경계 시각(초). 캘리브레이션 도구의 loadEndSeconds가 곧 t_load_end다.
    t_load_start = args.baseline
    t_load_end = args.baseline + args.load
    t_end = t_load_end + args.recovery_seconds

    frames_mark = 0
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

                # 부하 패턴 배율 — 회복 정책과는 곱셈으로 합성된다(정책의 의미가 유지된다).
                #
                # 패턴 시계는 반드시 **부하 시작**을 0으로 잡는다. 서버 시뮬레이터에는
                # 기준선 단계가 없어 t=0이 곧 부하 시작이므로, 여기서 t_zero(기준선 포함)를
                # 기준으로 재면 기준선 길이만큼 패턴이 밀려 같은 패턴인데 다른 구간을
                # 재생하게 된다 — 시뮬과 실측을 같은 조건으로 비교할 수 없게 되므로
                # 이 정렬이 패턴 재생 기능의 전제다.
                level = 1.0 if pattern is None else pattern.level_at(max(0.0, now - t_load_start))

                # R2(저부하 유지)는 배율에 곱해 합성한다 — 패턴이 있어도 정책 의미가 유지된다.
                eff_level = level
                if phase == "RECOVERY" and args.recovery == "r2_low_load":
                    eff_level *= 0.25

                # 실제 계산은 워커 스레드가 한다. 메인 루프는 지시만 하고 센싱에 집중한다.
                runner.set_state(run_frame, eff_level)
                time.sleep(min(0.05, args.interval / 4.0))

                # ── 샘플링 ───────────────────────────────────────────────
                now = time.time() - t_zero
                if now < next_sample:
                    continue
                next_sample += args.interval

                # 워커들이 누적한 총 프레임에서 구간 증가분으로 FPS를 낸다.
                # 인스턴스가 여러 개면 전체 합이므로 "시스템 처리량"이 된다.
                if now - last_fps_mark >= 1.0:
                    total = runner.total_frames()
                    fps = (total - frames_mark) / (now - last_fps_mark)
                    frames_mark = total
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
                f.write("{:.1f},{},{},{:.3f},{},{},{},{},{},{},{},{},{:.2f}\n".format(
                    now, datetime.now(timezone.utc).isoformat(), phase, level,
                    runner.active_jobs(),
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
            runner.stop()               # 워커 스레드를 세워 보드가 계속 달궈지지 않게 한다
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
