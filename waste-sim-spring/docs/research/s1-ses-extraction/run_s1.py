"""S1 추출 실행. 절차서 1·3·4절을 그대로 따른다.

실행 원본은 절대 덮어쓰지 않는다 — 비어 있는 다음 번호에 쓴다.
프롬프트는 S1-추출절차.md에서 뽑는다(다시 타이핑하면 원문과 어긋난다).

조건
  C    3절 프롬프트 원문. 스키마가 축약형이라 decompositions가 어디 들어가는지 보이지 않는다.
  Cs   축약 스키마를 2절의 전체 스키마로 교체한다. 새 정보가 아니라 절차서 자신의 스키마다.
  Csr  Cs + 코드 뒤에 출력 계약을 다시 적는다. 코드가 19만 토큰이라 앞쪽 지시가 생성 직전
       신호에서 멀다 — 설계도 경로 벤치마크에서 배치만 바꿔 계약 통과율이 0%→97%가 된 것과
       같은 처방이다. 측정 결과 Cs보다 나빴다(폐기).
  Cd   Cs + 형식론 진술 셋(규칙 6·7·8). 3절 프롬프트는 couplings가 무엇인지 한 번도 정의하지
       않고, 개체가 클래스가 아닐 수도 있다는 말이 없다. 도메인 낱말은 넣지 않는다 — 참조의
       노드 이름도, "거주민을 찾아라" 류의 힌트도 없다.

사용법:  python run_s1.py <모델> <반복> <조건>
"""
import glob, io, json, os, sys, time, urllib.error, urllib.request

REPO = r"C:\Dev\MCP\waste-sim-spring"
KIT = os.path.join(REPO, "docs", "research", "s1-ses-extraction")
MODEL = sys.argv[1] if len(sys.argv) > 1 else "gpt-4.1-mini"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 1
COND = sys.argv[3] if len(sys.argv) > 3 else "C"
assert COND in ("C", "Cs", "Csr", "Cd"), COND
os.chdir(REPO)

RULE = {
    "include_globs": ["src/main/java/**/*.java", "src/main/**/*.py"],
    "exclude": ["경로에 /config/ 포함", "파일명이 Application.java로 끝남"],
    "note": "src/test/** 는 include_globs에 애초에 없다. 도메인 지식 없이 적을 수 있는 규칙만 쓴다.",
}
PROC = io.open(os.path.join(KIT, "S1-추출절차.md"), encoding="utf-8").read()
ABBREV = '{ "root": "...", "entities": { ... }, "couplings": [ ... ], "evidence": { ... } }'


def selected():
    out = []
    for pat in RULE["include_globs"]:
        out += glob.glob(pat, recursive=True)
    return [f.replace("\\", "/") for f in sorted(set(out))
            if "/config/" not in f.replace("\\", "/") and not f.endswith("Application.java")]


def full_schema():
    sec = PROC[PROC.index("## 2. 출력 형식"):PROC.index("`evidence`가 핵심")]
    s = sec[sec.index("```json") + 7:]
    s = s[:s.index("```")].strip()
    assert '"decompositions"' in s and "aspect|spec|multi" in s, "2절 스키마 추출 실패"
    return s


# 형식론 진술 셋 — 3절이 비워 둔 자리를 채운다. 전부 SES/DEVS 일반 규칙이고 도메인 낱말이
# 없다. 이미 프롬프트에 있는 규칙 3("실행 환경 제외")이 정답 유출이 아니라 형식론 규칙인 것과
# 같은 범주다.
#
# 다만 규칙 7은 경계에 있다 — 일반 진술이지만, 이 엔진이 원자 모델을 클래스로 두지 않고
# 이벤트·배열로 쓴다는 것을 알고 고른 문장이다. 코드 양식을 모르는 사람이 같은 문장을 쓸 수
# 있는지는 논쟁의 여지가 있다. 조건을 갈라 재는 이유가 그것이다.
DEFS = """

추가 표기 규칙:

6. couplings는 개체 사이에 오가는 정보·물질의 흐름이다. A가 B의 상태를 바꾸거나 B에게 값을
   보낼 때 결합이 있다. 객체가 다른 객체를 필드로 들고 있다는 것(참조)이나 어떤 타입을 쓴다는
   것은 결합이 아니다. 포트 이름은 무엇이 오가는지를 가리켜야 한다.

7. 개체가 반드시 클래스로 존재하지는 않는다. 이벤트 종류, 상태를 담은 배열, 반복문 안에서
   되풀이되는 판정도 하나의 개체와 그 동작일 수 있다. 클래스 목록을 옮기는 것이 아니라 무엇이
   시간에 따라 상태를 바꾸는지를 찾는다.

8. 선언 위치가 아니라 무엇을 서술하는가로 속성을 배치한다. 설정 객체 하나에 값이 모여 선언돼
   있어도, 그 값들은 각각 자기가 서술하는 개체의 속성이다. 설정 객체를 개체로 두고 모든 값을
   그 아래 매달면 구조가 아니라 선언 목록이 된다.
"""


def prompt_for(cond):
    sec = PROC[PROC.index("## 3. 프롬프트"):PROC.index("**의도적으로 넣지 않은 것**")]
    lines = [ln[2:] if ln.startswith("> ") else "" for ln in sec.split("\n")
             if ln.startswith("> ") or ln.strip() == ">"]
    p = "\n".join(lines).strip()
    assert "System Entity Structure" in p and ABBREV in p, "3절 프롬프트 추출 실패"
    if cond in ("Cs", "Csr", "Cd"):
        p = p.replace(ABBREV, full_schema())
    if cond == "Cd":
        p += DEFS
    return p


TAIL = ("\n\n=====\n위 소스 코드에 대한 답을 아래 형식의 JSON 객체 하나로만 낸다. "
        "분해(aspect/spec/multi)는 최상위 키가 아니라 각 엔티티의 decompositions 배열에 넣는다.\n"
        + full_schema())

files = selected()
bundle, total_lines = [], 0
for f in files:
    s = io.open(f, encoding="utf-8", errors="replace").read()
    total_lines += s.count("\n") + 1
    bundle.append(f"===== FILE: {f} =====\n{s}")
code = "\n\n".join(bundle)
PROMPT = prompt_for(COND)
user = code + (TAIL if COND == "Csr" else "")

io.open(os.path.join(KIT, "runs", f"manifest-{COND}.json"), "w", encoding="utf-8").write(
    json.dumps({"condition": COND, "selection_rule": RULE, "model": MODEL,
                "files": len(files), "total_lines": total_lines,
                "total_bytes": len(code.encode("utf-8")),
                "prompt_len": len(PROMPT), "tail_appended": COND == "Csr",
                "file_list": files}, ensure_ascii=False, indent=2) + "\n")
print(f"{COND} 조건: {len(files)}파일 · {total_lines:,}행 · 프롬프트 {len(PROMPT):,}자")

URL = "https://api.openai.com/v1/chat/completions"
KEY = os.environ["OPENAI_API_KEY"]
for _ in range(N):
    body = json.dumps({"model": MODEL, "max_tokens": 16000,
                       "response_format": {"type": "json_object"},
                       "messages": [{"role": "system", "content": PROMPT},
                                    {"role": "user", "content": user}]},
                      ensure_ascii=False).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code} — {e.read().decode('utf-8','replace')[:300]}")
        continue
    dt = time.time() - t0
    raw = d["choices"][0]["message"]["content"]
    k = 1
    while os.path.exists(os.path.join(KIT, "runs", f"{MODEL}-{COND}-{k}.json")):
        k += 1
    name = f"{MODEL}-{COND}-{k}.json"
    io.open(os.path.join(KIT, "runs", name), "w", encoding="utf-8").write(raw)
    try:
        ses = json.loads(raw)
        dec = sum(len(e.get("decompositions") or []) for e in (ses.get("entities") or {}).values())
        extra = [k2 for k2 in ses if k2 not in ("root", "entities", "couplings", "evidence")]
        print(f"  {name}: {dt:5.1f}s · 엔티티 {len(ses.get('entities') or {})} "
              f"· decompositions {dec} · 결합 {len(ses.get('couplings') or [])} "
              f"· 계약밖키 {extra or '없음'}")
    except Exception as ex:
        print(f"  {name}: JSON 파싱 실패 {ex}")
