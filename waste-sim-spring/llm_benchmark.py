#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
waste-sim-spring 로컬 LLM 벤치마크
────────────────────────────────────────────────────────────
앱(OpenAiService)과 동일한 시스템 프롬프트·JSON 추출 로직으로
여러 Ollama 모델을 비교한다. 측정 항목:
  1) RUN_SIMULATION JSON 추출 성공률  (핵심 — 이게 돼야 차트가 뜸)
  2) 응답 언어 (KO/ZH/EN/혼합)
  3) 평균 응답 지연(초, CPU)
  4) 마크다운 누출 여부
  5) 오탐 — 시뮬레이션 요청이 아닌데 JSON을 뱉는지
  6) 접지성(Grounding)/사실성(Factuality) — 시뮬레이션 결과 숫자를 자연어로
     서술시켰을 때, (a) 정답 데이터에 없는 숫자를 만들어내는지(할루시네이션),
     (b) "증가/감소" 같은 방향성 서술이 실제 데이터 부호와 맞는지 측정.
     ※ 현재 실제 앱(ChatController)은 결과를 LLM이 아니라 코드가 템플릿으로
     채워 넣으므로 이 위험이 없다 — 이 섹션은 "나중에 LLM 해설 기능을 넣는다면
     어느 모델이 안전한가"를 미리 검증하는 순수 벤치마크 전용 기능이다.

의존성 없음(파이썬 표준 라이브러리만). Ollama가 로컬에서 실행 중이어야 함.
사용법:  python llm_benchmark.py
"""
import json, re, time, sys, os, urllib.request

# ── 설정 ─────────────────────────────────────────────────────
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434/v1/chat/completions")
OPENAI_URL = os.environ.get("OPENAI_API_URL", "https://api.openai.com/v1/chat/completions")
OPENAI_KEY = os.environ.get("OPENAI_API_KEY", "")   # OpenAI 비교하려면 이 환경변수 필요

# 비교할 모델 — 로컬(Ollama)과 OpenAI를 같은 표에서 비교.
# key가 비어 있으면(OpenAI 키 미설정) 해당 모델은 자동으로 건너뜀.
MODELS = [
    {"name": "llama3.2:3b", "url": OLLAMA_URL, "key": "ollama"},
    {"name": "qwen2.5:7b",  "url": OLLAMA_URL, "key": "ollama"},
    {"name": "gemma:2b", "url": OLLAMA_URL, "key": "ollama"},
    {"name": "gpt-4o-mini", "url": OPENAI_URL, "key": OPENAI_KEY},
]
RUNS = 3                                  # 프롬프트당 반복(성공률 측정용). 느리면 1~2로.
TIMEOUT = 240                             # 초. 7b CPU 콜드스타트 대비 넉넉히.
REPORT = "benchmark_report.md"
DETAIL_LOG = "benchmark_detail.log"       # 실패 케이스 원문 응답 저장 (진단용)

# 앱의 OpenAiService.SYSTEM_PROMPT 와 동일 (판단 경계 규칙 + few-shot 포함)
SYSTEM_PROMPT = """당신은 지역사회 생활쓰레기 시뮬레이션 어시스턴트입니다.
포항시 북구 장량동 원룸촌의 쓰레기 배출·수거 패턴을 DEVS(이산사건시스템) 기반으로 시뮬레이션합니다.

## 시뮬레이션 모델 개요
- 거주민 100명, 4개 건물, 건물당 25명
- 직업: 생산직(일용직, 07:22 출발), 학생(08:58), 전업주부(14:00)
- 건물당 30kg 임시 수거통, 수거 차량이 매일 지정 시각에 전체 수거
- 수거통 적재율이 임계치(기본 80%) 이상일 때 배출하면 민원 발생으로 집계

## 조정 가능한 파라미터
- collectionTime: 수거 시각 (예: "10:00", "12:00", "14:00")
- days: 시뮬레이션 기간(일), 기본 30
- seeds: 반복 횟수, 기본 30
- leaveSigma: 출발 시각 표준편차(분), 기본 30
- wasteSigma: 일일 쓰레기 표준편차(kg), 기본 0.3
- threshold: 청결도 임계치(0~1), 기본 0.8
- capacity: 수거통 용량(kg), 기본 30

## 이 시뮬레이션이 계산할 수 있는 것 / 없는 것
계산 가능(=RUN_SIMULATION으로 실행): 사용자가 수거 시각 등 구체적
조건을 하나라도 지정하면서 그 조건에서의 "한 달간 총 민원 수·직업별
민원·최대 적재량"을 구하려는 요청. 시각이 "07:22" 같은 정형 표기가
아니라 "아침 8시 반"처럼 자연어라도, 특정 수거 시각을 가리키면 유효한
collectionTime입니다 — 이런 경우는 절대 거절하지 말고 JSON을 내세요.

계산 불가능(JSON 내지 말 것) — 아래 두 경우만 해당:
(a) 특정 순간의 미집계 수치를 직접 묻는 경우 (예: "12시 시점 배출량",
    "17시 시점 배출량" 그 자체 값). 이 모델은 순간값을 출력하지 않고
    월간 집계만 계산하므로, 한계를 설명하세요.
(b) 수거 시각 등 조건을 하나도 지정하지 않고 막연히 묻는 경우
    (예: "패턴 알려줘", "어떻게 돼?", "분석해줘"). 이때는 임의로
    기본값을 정해 실행하지 말고, 어떤 수거 시각을 원하는지 되물어보세요.

"실행해줘"라는 단어가 있어도 동사만으로 판단하지 말고, 위 (a)(b)에
해당하는지만 보세요. 구체적 시각이 이미 있다면 반드시 JSON을 냅니다.

## 응답 규칙
사용자가 위 "계산 가능" 범위의 시뮬레이션 실행을 요청하면 반드시 아래
JSON 블록을 응답에 포함하세요:
```json
{
  "action": "RUN_SIMULATION",
  "params": { "collectionTime": "12:00", "days": 30, "seeds": 30,
    "leaveSigma": 30.0, "wasteSigma": 0.3, "threshold": 0.8, "capacity": 30.0 }
}
```
JSON 블록 앞뒤에 자연어 설명을 추가해도 됩니다.
시뮬레이션 요청이 아니거나 위 (a)(b)에 해당하면 JSON 없이
일반 텍스트로만 답변하세요.

## 판단 예시
- "12시 수거로 시뮬레이션 돌려줘" → 수거시각(12시) 지정됨 → JSON 포함
- "아침 8시 반에 수거하면 민원이 어떻게 되는지 실행해줘" → 수거시각
  (8:30)이 자연어로라도 지정됨 → JSON 포함 (거절 금지)
- "민원을 줄이려면 몇 시가 좋을지 실행해줘" → 수거시각 탐색 요청 →
  JSON 포함(예: collectionTime 기본값 12:00으로 실행 후 비교 제안)
- "12시 배출량이랑 17시 배출량을 실행해줘" → (a) 순간값 조회 →
  JSON 없이, "이 모델은 특정 시각의 순간 배출량이 아니라 수거
  시각별 월간 민원 수를 계산합니다"처럼 한계를 설명
- "시간대별로 직업별 배출 패턴 알려줘" → (b) 수거 시각 미지정 →
  JSON 없이 "어떤 수거 시각으로 시뮬레이션할지 알려주시면
  실행하겠습니다"처럼 되물음

한국어로 답변하세요."""

# 테스트 프롬프트 (sim=True 는 JSON 이 나와야 정상, False 는 안 나와야 정상)
PROMPTS = [
    ("12시에 수거하는 걸로 30일 시뮬레이션 돌려줘", True),
    ("대학가 동네에서 아침 8시 반에 수거하면 민원이 어떻게 되는지 실행해줘", True),
    ("12시 쓰레기 배출량과 17시 쓰레기 배출량 실행해줘", False),
    # 수거 시각을 전혀 지정하지 않은 막연한 요청 → 되물어야 정답(False로 정정,
    # 이전엔 True였으나 "미지정 시 되물음" 정책과 모순되는 라벨이었음)
    ("시간대별로 직업별 쓰레기 배출 패턴 알려줘", False),
    ("이 시뮬레이션은 대체 뭘 하는 거야?", False),  # 오탐 체크: JSON 나오면 안 됨
]

# ═══════════════════════════════════════════════════════════════
# 접지성(Grounding)/사실성(Factuality) 벤치마크
# ═══════════════════════════════════════════════════════════════

FIDELITY_SYSTEM_PROMPT = """당신은 쓰레기 수거 시뮬레이션 결과를 설명하는 어시스턴트입니다.
사용자가 제공한 JSON 데이터에 있는 숫자만 사용해서 자연어로 설명하세요.
데이터에 없는 숫자·통계·추정치를 새로 만들어내지 마세요. 모르는 값은 언급하지
마세요. 2~3문장의 한국어로만 답하고, 마크다운 서식은 쓰지 마세요."""

def _fidelity_prompt(data_desc, question):
    return f"다음은 쓰레기 수거 시뮬레이션 결과입니다: {json.dumps(data_desc, ensure_ascii=False)}\n{question}"

# 실측 검증된 값 기반(논문 표 8.1 및 이 벤치마크 세션에서 curl로 직접 확인한 수치).
# 각 케이스: (설명, 정답데이터or비교쌍, 사용자프롬프트, 기대방향 "increase"/"decrease"/None)
FIDELITY_CASES = [
    (
        "단일 결과 서술(12:00)",
        {"collectionTime": "12:00", "meanComplaints": 26.9, "stdComplaints": 5.6},
        _fidelity_prompt({"collectionTime": "12:00", "meanComplaints": 26.9, "stdComplaints": 5.6},
                        "이 결과를 2~3문장으로 설명해줘."),
        None,
    ),
    (
        "단일 결과 서술(직업별 포함, 08:00)",
        {"collectionTime": "08:00", "meanComplaints": 38.1, "stdComplaints": 9.8,
         "byOccupation": {"BlueCollar": 33.3, "Student": 4.9}},
        _fidelity_prompt({"collectionTime": "08:00", "meanComplaints": 38.1, "stdComplaints": 9.8,
                          "byOccupation": {"BlueCollar": 33.3, "Student": 4.9}},
                        "이 결과를 2~3문장으로 설명해줘. 직업별 수치도 언급해도 됩니다."),
        None,
    ),
    (
        "비교(10:00→12:00, 감소)",
        {"A": {"collectionTime": "10:00", "meanComplaints": 30.9},
         "B": {"collectionTime": "12:00", "meanComplaints": 26.9}},
        "다음 두 수거 시각의 시뮬레이션 결과를 비교해줘: 10:00 평균 민원 30.9건, "
        "12:00 평균 민원 26.9건. 어느 시각이 더 나은지, 민원이 어떻게 변하는지 "
        "2~3문장으로 설명해줘.",
        "decrease",
    ),
    (
        "비교(12:00→14:00, 증가)",
        {"A": {"collectionTime": "12:00", "meanComplaints": 26.9},
         "B": {"collectionTime": "14:00", "meanComplaints": 63.7}},
        "다음 두 수거 시각의 시뮬레이션 결과를 비교해줘: 12:00 평균 민원 26.9건, "
        "14:00 평균 민원 63.7건. 어느 시각이 더 나은지, 민원이 어떻게 변하는지 "
        "2~3문장으로 설명해줘.",
        "increase",
    ),
]

NUM_RE = re.compile(r"\d+\.?\d*")
# 0~3은 "08:00"의 "00"이 별도 토큰으로 뽑히거나 "2~3문장으로" 같은 지시문
# 반복·목록 번호로 흔히 등장해 오탐이 잦으므로 할루시네이션 판정에서 제외
# (도메인 특성상 실데이터로 0~3이 의미 있게 나올 일도 적음).
IGNORE_NUMS = {0.0, 1.0, 2.0, 3.0}

def extract_numbers(text):
    body = re.sub(r"```[\s\S]*?```", "", text)
    return [float(x) for x in NUM_RE.findall(body)]

def collect_allowed_numbers(data):
    """정답 JSON의 숫자 리프값(+파생 합/차) + collectionTime의 시(hour)를 허용
    숫자 집합으로. "30.9건에서 26.9건으로 4건 감소"처럼 모델이 두 값의 차이를
    직접 계산해 말하는 건 할루시네이션이 아니라 정당한 파생 서술이므로, 데이터
    수치(시각 제외)끼리의 합/차도 미리 계산해 허용한다."""
    allowed = set()
    data_nums = set()   # 시각(hour) 제외 — 실제 통계 수치만 (파생 합/차 계산 대상)
    def walk(v):
        if isinstance(v, dict):
            for k, vv in v.items():
                if k == "collectionTime" and isinstance(vv, str) and ":" in vv:
                    h, m = vv.split(":")
                    allowed.add(float(int(h)))
                    if int(m) != 0:
                        allowed.add(float(int(m)))
                else:
                    walk(vv)
        elif isinstance(v, list):
            for vv in v: walk(vv)
        elif isinstance(v, (int, float)):
            allowed.add(float(v))
            data_nums.add(float(v))
    walk(data)
    for a in data_nums:
        for b in data_nums:
            if a != b:
                allowed.add(round(abs(a - b), 1))
                allowed.add(round(a + b, 1))
    return allowed

def is_allowed(n, allowed_numbers):
    if n in IGNORE_NUMS:
        return True
    for a in allowed_numbers:
        tol = max(0.5, abs(a) * 0.05)   # 절대오차 0.5 또는 상대오차 5% 중 큰 쪽까지 반올림 허용
        if abs(n - a) <= tol:
            return True
    return False

def find_hallucinations(text, allowed_numbers):
    return [n for n in extract_numbers(text) if not is_allowed(n, allowed_numbers)]

# 정적 형용사("많아"/"높아"/"낮아"/"적어")는 "문제가 많아" 같은 비교와 무관한
# 문장에서도 흔히 등장해 오탐이 잦으므로 제외하고, 추세(변화) 자체를 가리키는
# 동사·명사만 남긴다 — 애매한 문장 스코핑보다 이쪽이 더 견고했다(실측으로 확인).
INCREASE_WORDS = ["증가", "늘어", "늘었", "늘고", "상승", "커졌", "악화"]
DECREASE_WORDS = ["감소", "줄어", "줄었", "줄고", "떨어", "완화", "개선"]

def check_direction(text, expected, gt_pair=None):
    """expected: 'increase'|'decrease'. 반환: 'correct'|'wrong'|'none'"""
    has_inc = any(w in text for w in INCREASE_WORDS)
    has_dec = any(w in text for w in DECREASE_WORDS)
    claimed = "increase" if (has_inc and not has_dec) else "decrease" if (has_dec and not has_inc) else None
    if claimed is None:
        return "none"
    return "correct" if claimed == expected else "wrong"

def run_fidelity_benchmark(active):
    print("\n" + "═" * 60)
    print(f"접지성/사실성 벤치마크 | 케이스 {len(FIDELITY_CASES)}개 × {RUNS}회")
    print("═" * 60 + "\n")

    results = {}
    detail_lines = []
    for m in active:
        model, url, key = m["name"], m["url"], m["key"]
        agg = {"total_nums": 0, "halluc_nums": 0, "dir_correct": 0, "dir_wrong": 0,
               "dir_none": 0, "dir_total": 0, "lat": [], "errors": 0}
        print(f"── {model} ──")
        for desc, gt_data, prompt, expected_dir in FIDELITY_CASES:
            allowed = collect_allowed_numbers(gt_data)
            for i in range(RUNS):
                try:
                    body = json.dumps({
                        "model": model, "max_tokens": 400, "temperature": 0.2,
                        "messages": [
                            {"role": "system", "content": FIDELITY_SYSTEM_PROMPT},
                            {"role": "user", "content": prompt},
                        ],
                    }).encode("utf-8")
                    req = urllib.request.Request(url, data=body,
                        headers={"Content-Type": "application/json",
                                "Authorization": "Bearer " + (key or "none")})
                    t0 = time.time()
                    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                        data = json.loads(r.read().decode("utf-8"))
                    dt = time.time() - t0
                    text = data["choices"][0]["message"]["content"]
                except Exception as e:
                    agg["errors"] += 1
                    print(f"  [ERR] {type(e).__name__}: {str(e)[:60]}")
                    continue

                agg["lat"].append(dt)
                nums = extract_numbers(text)
                halluc = find_hallucinations(text, allowed)
                agg["total_nums"] += len(nums)
                agg["halluc_nums"] += len(halluc)

                dir_flag = ""
                if expected_dir is not None:
                    dr = check_direction(text, expected_dir, gt_data)
                    agg["dir_total"] += 1
                    if dr == "correct": agg["dir_correct"] += 1
                    elif dr == "wrong": agg["dir_wrong"] += 1
                    else: agg["dir_none"] += 1
                    dir_flag = f" 방향={dr}"

                flag = f"숫자{len(nums)}개(할루시네이션{len(halluc)}){dir_flag}"
                marker = " ⚠️" if halluc or dir_flag.endswith("wrong") else ""
                print(f"  {flag:40s} {dt:5.1f}s  «{desc}»{marker}")

                if halluc or (expected_dir is not None and dir_flag.endswith("wrong")):
                    detail_lines.append(
                        f"=== [FIDELITY] {model} | run{i+1} | {desc} ===\n"
                        f"정답 데이터: {json.dumps(gt_data, ensure_ascii=False)}\n"
                        f"허용 숫자 집합: {sorted(allowed)}\n"
                        f"할루시네이션 숫자: {halluc}\n"
                        f"{'방향 판정: ' + dr if expected_dir is not None else ''}\n"
                        f"--- 원문 응답 ---\n{text}\n")
        results[model] = agg
        print()

    fidelity_detail = detail_lines
    if fidelity_detail:
        with open(DETAIL_LOG, "a", encoding="utf-8") as f:
            f.write("\n" + "\n".join(fidelity_detail))
        print(f"할루시네이션/방향성 오류 원문 로그 추가: {DETAIL_LOG} ({len(fidelity_detail)}건)\n")

    lines = ["\n## 접지성/사실성(Fidelity) 벤치마크 결과\n",
             f"- 케이스 {len(FIDELITY_CASES)}개(단일서술 2 + 방향비교 2) × {RUNS}회\n",
             "| 모델 | 숫자 정확도(비할루시네이션율) | 방향성 정확도 | 평균 지연 | 오류 |",
             "|---|---|---|---|---|"]
    for model, a in results.items():
        acc = 100 * (a["total_nums"] - a["halluc_nums"]) // max(1, a["total_nums"])
        num_acc = f"{a['total_nums']-a['halluc_nums']}/{a['total_nums']} ({acc}%)"
        dir_denom = a["dir_correct"] + a["dir_wrong"]
        dir_acc = f"{a['dir_correct']}/{dir_denom} ({100*a['dir_correct']//max(1,dir_denom)}%)" \
                  + (f", 무판단{a['dir_none']}" if a["dir_none"] else "")
        lat = f"{sum(a['lat'])/max(1,len(a['lat'])):.1f}s"
        lines.append(f"| {model} | {num_acc} | {dir_acc} | {lat} | {a['errors']} |")
    fidelity_report = "\n".join(lines)
    print(fidelity_report)
    return fidelity_report

# ── 앱과 동일한 관대한 JSON 추출 (주석·후행콤마 허용) ────────────
CODE_BLOCK = re.compile(r"```json\s*(\{[\s\S]*?\})\s*```")
ACTION_PAT = re.compile(r"\{[^{}]*\"action\"[\s\S]*?\}\s*\}")

def lenient(s):
    s = re.sub(r"//[^\n]*", "", s)                 # 라인 주석
    s = re.sub(r"/\*[\s\S]*?\*/", "", s)           # 블록 주석
    s = re.sub(r",(\s*[}\]])", r"\1", s)           # 후행 콤마
    return s

def extract_config(text):
    """RUN_SIMULATION 파싱 성공 시 params dict, 실패 시 None"""
    cands = []
    m = CODE_BLOCK.search(text)
    if m: cands.append(m.group(1))
    cands += ACTION_PAT.findall(text)
    for c in cands:
        try:
            node = json.loads(lenient(c))
            if node.get("action") == "RUN_SIMULATION" and "params" in node:
                return node["params"]
        except Exception:
            continue
    return None

# ── 언어·서식 판정 ───────────────────────────────────────────
def detect_lang(text):
    t = re.sub(r"```[\s\S]*?```", "", text)  # 코드블록 제외
    hangul = len(re.findall(r"[가-힣]", t))
    cjk    = len(re.findall(r"[一-鿿]", t))
    latin  = len(re.findall(r"[A-Za-z]", t))
    total = hangul + cjk + latin
    if total == 0: return "?"
    if hangul >= total * 0.5: return "KO"
    if cjk >= total * 0.4 and cjk > hangul: return "ZH"
    if latin >= total * 0.6: return "EN"
    return "혼합"

def markdown_leak(text):
    body = re.sub(r"```[\s\S]*?```", "", text)
    return bool(re.search(r"\*\*|^\s*[\*\+]\s|(?<!\d)#{1,3}\s", body, re.M))

# ── Ollama 호출 ──────────────────────────────────────────────
def call(model, url, key, user):
    body = json.dumps({
        "model": model,
        "max_tokens": 1024,
        "temperature": 0.2,   # 앱(OpenAiService)과 동일 — 형식 준수 편차 축소
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user},
        ],
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + (key or "none")})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        data = json.loads(r.read().decode("utf-8"))
    dt = time.time() - t0
    content = data["choices"][0]["message"]["content"]
    return content, dt

# ── 실행 ─────────────────────────────────────────────────────
def main():
    active = [m for m in MODELS if m["key"] != ""]   # OpenAI 키 없으면 제외
    skipped = [m["name"] for m in MODELS if m["key"] == ""]
    print(f"모델: {', '.join(m['name'] for m in active)} | 프롬프트 {len(PROMPTS)}개 × {RUNS}회")
    if skipped:
        print(f"(건너뜀 — 키 미설정: {', '.join(skipped)}. OpenAI는 OPENAI_API_KEY 환경변수 필요)")
    print()
    results = {}
    detail_lines = []
    false_positives = []   # (model, run_idx, prompt, response_text) — 오탐 전용 추적
    for m in active:
        model, url, key = m["name"], m["url"], m["key"]
        agg = {"sim_total":0, "sim_ok":0, "lat":[], "lang":{}, "md_leak":0,
               "false_pos":0, "nonsim_total":0, "errors":0}
        print(f"── {model} ──")
        for prompt, is_sim in PROMPTS:
            for i in range(RUNS):
                try:
                    text, dt = call(model, url, key, prompt)
                except Exception as e:
                    agg["errors"] += 1
                    print(f"  [ERR] {type(e).__name__}: {str(e)[:60]}")
                    detail_lines.append(f"=== {model} | run{i+1} | 《{prompt}》 ===\n[ERROR] {type(e).__name__}: {e}\n")
                    continue
                agg["lat"].append(dt)
                lang = detect_lang(text)
                agg["lang"][lang] = agg["lang"].get(lang, 0) + 1
                if markdown_leak(text): agg["md_leak"] += 1
                cfg = extract_config(text)
                if is_sim:
                    agg["sim_total"] += 1
                    if cfg is not None: agg["sim_ok"] += 1
                    flag = "JSON✓" if cfg else "JSON✗"
                else:
                    agg["nonsim_total"] += 1
                    if cfg is not None:
                        agg["false_pos"] += 1
                        false_positives.append((model, i + 1, prompt, text))
                    flag = "오탐!" if cfg else "정상(산문)"
                # 오탐은 프롬프트를 잘라내지 않고 전체 출력 — 어떤 문장이 문제인지 바로 보이게
                shown_prompt = prompt if flag == "오탐!" else prompt[:22]
                marker = " ⚠️" if flag == "오탐!" else ""
                print(f"  {flag:12s} {dt:5.1f}s {lang:3s}  «{shown_prompt}»{marker}")
                # 실패/오탐 케이스는 원문 전체를 로그에 남겨 진단 가능하게 함
                if flag in ("JSON✗", "오탐!"):
                    detail_lines.append(
                        f"=== {model} | run{i+1} | flag={flag} | is_sim={is_sim} ===\n"
                        f"프롬프트: {prompt}\n"
                        f"--- 원문 응답 ---\n{text}\n")
        results[model] = agg
        print()

    # ── 오탐 발생 상세 (콘솔 + 리포트 공통) ──
    fp_section = ["## 오탐 발생 상세 (JSON이 나오면 안 되는데 나온 케이스)\n"]
    if false_positives:
        fp_section.append("| 모델 | 실행 회차 | 프롬프트 |")
        fp_section.append("|---|---|---|")
        for model, run_idx, prompt, _ in false_positives:
            fp_section.append(f"| {model} | {run_idx} | {prompt} |")
    else:
        fp_section.append("(오탐 없음)")
    fp_report = "\n".join(fp_section)
    print(fp_report + "\n")

    if detail_lines:
        with open(DETAIL_LOG, "w", encoding="utf-8") as f:
            f.write("\n".join(detail_lines))
        print(f"실패/오탐 케이스 원문 로그: {DETAIL_LOG} ({len(detail_lines)}건)\n")
    # ── 요약표 ──
    lines = ["# LLM 벤치마크 결과 (로컬 Ollama vs OpenAI)\n",
             f"- 모델: {', '.join(results.keys())}",
             f"- 프롬프트 {len(PROMPTS)}개 × {RUNS}회\n",
             "| 모델 | JSON 추출 성공률 | 평균 지연 | 주요 언어 | 마크다운 누출 | 오탐(비시뮬) | 오류 |",
             "|---|---|---|---|---|---|---|"]
    for model, a in results.items():
        rate = f"{a['sim_ok']}/{a['sim_total']} ({100*a['sim_ok']//max(1,a['sim_total'])}%)"
        lat = f"{sum(a['lat'])/max(1,len(a['lat'])):.1f}s"
        lang = max(a["lang"], key=a["lang"].get) if a["lang"] else "?"
        md = f"{a['md_leak']}회"
        fp = f"{a['false_pos']}/{a['nonsim_total']}"
        lines.append(f"| {model} | {rate} | {lat} | {lang} | {md} | {fp} | {a['errors']} |")
    report = "\n".join(lines) + "\n\n" + fp_report
    print("\n" + "\n".join(lines))

    fidelity_report = run_fidelity_benchmark(active)
    report = report + "\n" + fidelity_report

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write(report + "\n")
    print(f"\n리포트 저장: {REPORT}")

if __name__ == "__main__":
    main()
