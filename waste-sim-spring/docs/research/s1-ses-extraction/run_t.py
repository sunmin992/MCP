"""조건 T — 나눠 묻기. 한 번에 전체 SES를 내게 하지 않고 좁은 질문 다섯 개로 나눈다.

조립은 코드가 한다(LLM 없음). 그래서 스키마 위반이 구조적으로 불가능하다 —
C↔Cs를 갈랐던 형식 준수 축이 사라진다.

입력은 C와 **똑같이** 112파일 전부다. 바뀌는 것은 질문뿐이다.
사람이 파일을 골라 주면 절차서 1절이 금지한 B 조건(고르는 순간 구조의 절반을 알려준 것)이
되므로, 단계마다 같은 코드를 준다.

  T-a 상태 보유자   시간이 흐르면 무엇이 달라지는가 + 그 변화가 일어나는 코드 위치
  T-b 개체 확정     그 중 무엇이 하나의 개체인가. 같은 것을 두 이름으로 부른 것을 합친다
  T-c 속성 배치     각 필드는 어느 개체를 서술하는가
  T-d 흐름          A의 상태를 바꾸는 코드가 B에서 온 값을 쓰는가
  T-e 분해 축       택일인가 병렬인가 복제인가

조건
  T    T-a에 B안 문구(가장 중립적). 구현 형태를 한 마디도 언급하지 않는다.
       Tpilot(첫 실행)에서 고친 것 셋 — 전부 형식·조립부이고 T-a 문구는 그대로다:
         · T-b에 이름 규칙(명사구 하나, 괄호·"및" 금지). 산문형 이름이 나왔다.
         · T-d·T-e에 T-a의 상태 변경 위치를 함께 넘긴다. 이름만 주니 앱 호출 그래프로 갔다.
         · 자기 참조 분해를 조립부가 버린다.
  T0   통제군. T-a를 "등장하는 개체를 나열하라"로만 둔다.
       T > T0 이고 B안에 코드베이스 고유 정보가 없다면 그 이득은 유출이 아니라 분해 효과다.

사용법:  python run_t.py <모델> <반복> <T|T0>
"""
import glob, io, json, os, sys, time, urllib.error, urllib.request

KIT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(KIT, "..", "..", ".."))
MODEL = sys.argv[1] if len(sys.argv) > 1 else "gpt-4.1-mini"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 1
COND = sys.argv[3] if len(sys.argv) > 3 else "T"
assert COND in ("T", "T0"), COND

RULE = {"include_globs": ["src/main/java/**/*.java", "src/main/**/*.py"],
        "exclude": ["경로에 /config/ 포함", "파일명이 Application.java로 끝남"]}


def selected():
    out = []
    for pat in RULE["include_globs"]:
        out += glob.glob(os.path.join(REPO, pat), recursive=True)
    keep = []
    for f in sorted(set(out)):
        p = f.replace("\\", "/")
        if "/config/" in p or p.endswith("Application.java"):
            continue
        keep.append(os.path.relpath(f, REPO).replace("\\", "/"))
    return keep


HEAD = ("아래는 한 시뮬레이터의 소스 코드입니다. 이 코드가 무엇을 시뮬레이션하는지 읽고 "
        "질문에 답하세요.\n\n"
        "규칙:\n"
        "- 코드에 근거가 있는 것만 적으세요. 있을 법한 것을 채우지 마세요.\n"
        "- 근거 위치(파일:행 또는 클래스·필드명)를 함께 적으세요. 댈 수 없으면 빼세요.\n"
        "- 실행 엔진·프레임워크·로깅·영속화처럼 구조를 실행하는 환경은 넣지 마세요. "
        "시뮬레이션 대상만 넣습니다.\n"
        "- 출력은 주어진 JSON 스키마 하나만. 설명 문장을 붙이지 마세요.\n\n")

# T-a — B안. "클래스"라는 낱말도, 구현 형태(이벤트·배열·반복문)도 언급하지 않는다.
ASK_A = ("질문: 이 시뮬레이션에서 **시간이 흐르면 무엇이 달라지는가.** 달라지는 것마다 "
         "그 변화가 일어나는 코드 위치를 적으세요.\n\n"
         '{"changing": [{"name":"...", "what_changes":"...", "evidence":"파일:행"}]}')
# T0 — 통제군. 아무 말도 더하지 않는다.
ASK_A0 = ("질문: 이 시뮬레이션에 등장하는 개체를 나열하세요.\n\n"
          '{"changing": [{"name":"...", "what_changes":"", "evidence":"파일:행"}]}')

ASK_B = ("아래는 앞 단계에서 찾은 목록입니다.\n\n{prev}\n\n"
         "질문: 이 중 **무엇이 하나의 개체인가.** 같은 것을 두 이름으로 부른 것은 하나로 "
         "합치고, 개체가 아닌 것(값·계산 결과·환경)은 빼세요.\n\n"
         "이름 규칙: **명사구 하나**로 적습니다. 괄호·쉼표·'및'·'와'로 두 개를 묶지 않고, "
         "설명을 붙이지 않습니다. 두 개를 묶고 싶으면 둘로 나눠 적으세요.\n\n"
         '{{"entities": [{{"name":"...", "evidence":"파일:행"}}]}}')

ASK_C = ("아래는 확정된 개체 목록입니다.\n\n{prev}\n\n"
         "질문: 코드의 각 필드는 **어느 개체를 서술하는가.** 선언된 위치가 아니라 그 값이 "
         "무엇의 성질인지로 판단하세요. 설정 객체 하나에 값이 모여 선언돼 있어도, 각 값은 "
         "자기가 서술하는 개체의 속성입니다.\n\n"
         '{{"attrs": [{{"entity":"...", "attr":"...", "evidence":"파일:행"}}]}}')

ASK_D = ("아래는 확정된 개체와, 각 개체의 상태가 바뀌는 코드 위치입니다.\n\n{prev}\n\n"
         "질문: 어떤 개체의 상태를 바꾸는 코드가 **다른 개체에서 온 값을 쓰는가.** 그런 자리를 "
         "찾아 무엇이 어디로 가는지 적으세요. **위에 적힌 상태 변경 지점에서** 찾으세요. "
         "객체가 다른 객체를 필드로 들고 있다는 것(참조)이나 함수가 값을 넘긴다는 것은 "
         "여기 해당하지 않습니다. from·to는 반드시 **위 목록의 개체 이름**으로 적으세요.\n\n"
         '{{"couplings": [{{"from":"개체.무엇", "to":"개체.무엇", "evidence":"파일:행"}}]}}')

ASK_E = ("아래는 확정된 개체와, 각 개체의 상태가 바뀌는 코드 위치입니다.\n\n{prev}\n\n"
         "질문: 개체 사이의 관계를 분류하세요. 자식들이 **함께** 존재하며 동시에 돌아가면 "
         "aspect, 자식 중 **하나를 골라** 확정하면 spec, **동종 개체 여럿**을 복제하면 "
         "multi입니다. parent·children은 반드시 **위 목록의 개체 이름**으로 적고, "
         "자기 자신을 자식으로 두지 마세요.\n\n"
         '{{"decompositions": [{{"parent":"...", "kind":"aspect|spec|multi", "name":"축 이름", '
         '"children":["..."], "evidence":"파일:행"}}]}}')

URL = "https://api.openai.com/v1/chat/completions"
KEY = os.environ["OPENAI_API_KEY"]


def ask(prompt, user, tries=6):
    """한 단계를 묻는다. 429는 기다렸다 다시 보낸다.

    한 실행이 19만 토큰을 다섯 번 연달아 보내므로 분당 토큰 한도에 걸린다. 429에서 그냥
    죽으면 앞 단계의 결과까지 버려진다 — 다섯 단계가 한 실행을 이루기 때문이다.
    """
    body = json.dumps({"model": MODEL, "max_tokens": 16000, "temperature": 1,
                       "response_format": {"type": "json_object"},
                       "messages": [{"role": "system", "content": prompt},
                                    {"role": "user", "content": user}]},
                      ensure_ascii=False).encode()
    for i in range(tries):
        req = urllib.request.Request(URL, data=body, headers={
            "Content-Type": "application/json", "Authorization": "Bearer " + KEY})
        try:
            with urllib.request.urlopen(req, timeout=900) as r:
                d = json.load(r)
            return d["choices"][0]["message"]["content"], d.get("usage", {})
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == tries - 1:
                raise
            wait = int(e.headers.get("retry-after") or 0) or min(90, 20 * (i + 1))
            print(f"      429 - {wait}s 대기 후 재시도 ({i + 1}/{tries - 1})")
            time.sleep(wait)


files = selected()
bundle, lines = [], 0
for f in files:
    s = io.open(os.path.join(REPO, f), encoding="utf-8", errors="replace").read()
    lines += s.count("\n") + 1
    bundle.append(f"===== FILE: {f} =====\n{s}")
CODE = "\n\n".join(bundle)
io.open(os.path.join(KIT, "runs", f"manifest-{COND}.json"), "w", encoding="utf-8").write(
    json.dumps({"condition": COND, "selection_rule": RULE, "model": MODEL,
                "files": len(files), "total_lines": lines,
                "stages": ["T-a", "T-b", "T-c", "T-d", "T-e"],
                "assembly": "코드가 결정론적으로 조립한다 — LLM 없음",
                "ta_wording": "B안(중립)" if COND == "T" else "통제군",
                "file_list": files}, ensure_ascii=False, indent=2) + "\n")
print(f"{COND}: {len(files)}파일 · {lines:,}행 · 5단계 × {N}회")

for run in range(N):
    t0, tok = time.time(), 0
    stages = {}
    try:
        raw, u = ask(HEAD + (ASK_A if COND == "T" else ASK_A0), CODE)
        tok += u.get("total_tokens", 0)
        stages["a"] = json.loads(raw)
        prev_a = json.dumps(stages["a"].get("changing", [])[:80], ensure_ascii=False)

        raw, u = ask(HEAD + ASK_B.format(prev=prev_a), CODE)
        tok += u.get("total_tokens", 0)
        stages["b"] = json.loads(raw)
        ents = [e for e in stages["b"].get("entities", []) if isinstance(e, dict)]
        # 이름만 넘기면 T-d·T-e가 코드의 호출 그래프로 다시 닻을 내린다(Tpilot에서 관측).
        # T-a가 찾은 "어디서 상태가 바뀌는가"를 함께 넘긴다.
        prev_b = json.dumps([{"name": e.get("name"), "state_changes_at": e.get("evidence", "")}
                             for e in ents], ensure_ascii=False)

        for key, tpl in (("c", ASK_C), ("d", ASK_D), ("e", ASK_E)):
            raw, u = ask(HEAD + tpl.format(prev=prev_b), CODE)
            tok += u.get("total_tokens", 0)
            stages[key] = json.loads(raw)
    except (urllib.error.HTTPError, json.JSONDecodeError, KeyError) as e:
        print(f"  실행 {run+1} 실패: {type(e).__name__} {e}")
        continue

    # ── 조립 — 결정론. 스키마 위반이 불가능하다. ──────────────────────────
    ses = {"root": None, "entities": {}, "couplings": [], "evidence": {}}
    for e in ents:
        n = e.get("name")
        if not isinstance(n, str) or not n:
            continue
        ses["entities"][n] = {"attrs": [], "decompositions": []}
        if e.get("evidence"):
            ses["evidence"][n] = e["evidence"]
    for a in stages["c"].get("attrs", []):
        if not isinstance(a, dict):
            continue
        ent, at = a.get("entity"), a.get("attr")
        if ent in ses["entities"] and isinstance(at, str) and at:
            if at not in ses["entities"][ent]["attrs"]:
                ses["entities"][ent]["attrs"].append(at)
            if a.get("evidence"):
                ses["evidence"][f"{ent}.{at}"] = a["evidence"]
    for dc in stages["e"].get("decompositions", []):
        if not isinstance(dc, dict):
            continue
        p, k = dc.get("parent"), str(dc.get("kind", "")).lower()
        kids = [c for c in (dc.get("children") or []) if isinstance(c, str)]
        # 자기 자신을 자식으로 둔 분해는 버린다 — Tpilot에서 "쓰레기 수거장 -[multi]->
        # 쓰레기 수거장" 같은 자기 참조가 나왔고, 그건 분해가 아니다.
        kids = [c for c in kids if c != p]
        if p in ses["entities"] and k in ("aspect", "spec", "multi") and kids:
            ses["entities"][p]["decompositions"].append(
                {"kind": k, "name": dc.get("name", ""), "children": kids})
    for cp in stages["d"].get("couplings", []):
        if isinstance(cp, dict) and cp.get("from") and cp.get("to"):
            ses["couplings"].append({"from": cp["from"], "to": cp["to"],
                                     "evidence": cp.get("evidence", "")})
    # 루트 = 부모로만 등장하고 자식으로 등장하지 않는 엔티티
    kids = {c for b in ses["entities"].values()
            for d2 in b["decompositions"] for c in d2["children"]}
    roots = [n for n in ses["entities"] if n not in kids]
    ses["root"] = roots[0] if roots else (next(iter(ses["entities"]), None))

    k = 1
    while os.path.exists(os.path.join(KIT, "runs", f"{MODEL}-{COND}-{k}.json")):
        k += 1
    name = f"{MODEL}-{COND}-{k}.json"
    io.open(os.path.join(KIT, "runs", name), "w", encoding="utf-8").write(
        json.dumps(ses, ensure_ascii=False, indent=2) + "\n")
    io.open(os.path.join(KIT, "runs", f"{MODEL}-{COND}-{k}-stages.json"), "w",
            encoding="utf-8").write(json.dumps(stages, ensure_ascii=False, indent=2) + "\n")
    dec = sum(len(b["decompositions"]) for b in ses["entities"].values())
    print(f"  {name}: {time.time()-t0:5.1f}s · 토큰 {tok:,} · 엔티티 {len(ses['entities'])} "
          f"· 속성 {sum(len(b['attrs']) for b in ses['entities'].values())} "
          f"· 분해 {dec} · 결합 {len(ses['couplings'])}")
