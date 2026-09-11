"""베이스라인 조건 재현 — 시뮬레이터 정보 없이 LLM에 요청 하나만 던진다.

기록을 남기는 것이 목적이다. 프롬프트·모델·시각·원문 응답을 파일로 떨어뜨려,
발표에서 "이게 그때 나온 것"이라고 말할 근거를 만든다.
시스템 프롬프트를 주지 않는다 — 그것이 '정보 없음' 조건이다.
"""
import json, os, time, urllib.request, urllib.error, pathlib, datetime

REQUEST = ("포항시 장량동의 생활쓰레기 수거 시뮬레이터를 만들어줘. "
           "수거 차량이 도는 시각을 바꿔가며 주민 민원이 얼마나 줄어드는지 보고 싶어.")
MODEL = "gpt-4o-mini"
URL = "https://api.openai.com/v1/chat/completions"
OUT = pathlib.Path(r"C:\Dev\MCP\waste-sim-spring\docs\reports\llm-baseline-2026-09-11")
OUT.mkdir(parents=True, exist_ok=True)

body = json.dumps({
    "model": MODEL, "temperature": 0.2, "max_tokens": 4000,
    "messages": [{"role": "user", "content": REQUEST}],   # 시스템 프롬프트 없음
}, ensure_ascii=False).encode()
req = urllib.request.Request(URL, data=body, headers={
    "Content-Type": "application/json",
    "Authorization": "Bearer " + os.environ["OPENAI_API_KEY"]})

t0 = time.time()
try:
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.loads(r.read().decode())
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode("utf-8", "replace")[:500]); raise
dt = time.time() - t0

reply = data["choices"][0]["message"]["content"]
usage = data.get("usage", {})

(OUT / "request.txt").write_text(REQUEST + "\n", encoding="utf-8")
(OUT / "response.md").write_text(reply, encoding="utf-8")
(OUT / "metadata.json").write_text(json.dumps({
    "실행시각": datetime.datetime.now().isoformat(timespec="seconds"),
    "모델": MODEL, "temperature": 0.2,
    "시스템프롬프트": None,
    "조건": "프로젝트 맥락·시뮬레이터 정보를 일절 주지 않음",
    "소요초": round(dt, 2), "usage": usage,
    "응답길이": len(reply),
}, ensure_ascii=False, indent=2), encoding="utf-8")

print(f"모델 {MODEL} · {dt:.1f}s · 응답 {len(reply)}자 · 토큰 {usage}")
print(f"저장: {OUT}")
print("\n" + "=" * 70)
print(reply[:2500])
