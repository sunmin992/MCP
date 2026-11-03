"""metrics.json + e3.json → summary.md (보고서 표의 원본).

서버 × 방식마다 회차(r1~r3)별로 요청 6개를 더한 값을 내고, 세 회차의 평균과 범위(최소–최대)를 적는다.
요청별 표는 세 회차의 평균과 범위다.
python aggregate.py
"""
import json, os
from statistics import mean

HERE = os.path.dirname(os.path.abspath(__file__))
M = json.load(open(os.path.join(HERE, "metrics.json"), encoding="utf-8"))
E = json.load(open(os.path.join(HERE, "e3.json"), encoding="utf-8"))
REQ = json.load(open(os.path.join(HERE, "requests.json"), encoding="utf-8"))["requests"]
SRV = {"J": "장량동", "D": "행정동", "P": "주차"}
REPS = ["r1", "r2", "r3"]

QUAL = [  # (이름, 실행 하나에서 값을 꺼내는 함수)
    ("조용한 기본값", lambda r: r.get("silentDefault", 0)),
    ("조용한 추측값", lambda r: r.get("silentGuess", 0) + len(r.get("outsideGuess", []))),
    ("해석 오류", lambda r: r.get("misread", 0)),
    ("기본값 없는 결정 건너뜀", lambda r: len(r.get("noDefaultSkipped", []))),
    ("지어낸 매개변수", lambda r: len(r.get("invented", []))),
    ("범위 밖 값(최종)", lambda r: len(r.get("finalOutOfRange", []))),
    ("물은 기본값", lambda r: r.get("askedDefault", 0)),
    ("질문 묶음", lambda r: r["questions"]),
]
COST = [
    ("모델 호출", "modelCalls"), ("MCP 호출", "mcpCalls"), ("새 입력 토큰", "inputNew"),
    ("캐시 읽기 토큰", "inputCacheRead"), ("출력 토큰(보조)", "outputTokens"), ("걸린 시간(초)", "seconds"),
]


def fmt(xs, nd=1):
    if not xs:
        return "—"
    m, lo, hi = mean(xs), min(xs), max(xs)
    f = (lambda v: f"{v:,.0f}") if max(abs(lo), abs(hi)) >= 1000 else (lambda v: f"{v:.{nd}f}".rstrip("0").rstrip("."))
    return f(m) if lo == hi else f"{f(m)} ({f(lo)}–{f(hi)})"


def rows(s=None, m=None, rep=None, req=None):
    return [r for r in M if (s is None or r["server"] == s) and (m is None or r["method"] == m)
            and (rep is None or r["rep"] == rep) and (req is None or r["req"] == req)]


out = []
servers = ["J", "D", "P", None]
for title, fn in [("품질 — 회차별 요청 6개(전체는 18개) 합의 평균 (범위)", None)]:
    out.append(f"### {title}\n")
    out.append("| 지표 | " + " | ".join(f"{SRV.get(s, '전체')} A | {SRV.get(s, '전체')} B" for s in servers) + " |")
    out.append("|---|" + "---|" * (2 * len(servers)))
    for name, f in QUAL:
        cells = []
        for s in servers:
            for m in "AB":
                cells.append(fmt([sum(f(r) for r in rows(s, m, rep)) for rep in REPS]))
        out.append(f"| {name} | " + " | ".join(cells) + " |")
    out.append("")

out.append("### 비용(E3) — 실행 하나당 평균 (실행별 최소–최대)\n")
out.append("| 지표 | " + " | ".join(f"{SRV.get(s, '전체')} A | {SRV.get(s, '전체')} B" for s in servers) + " |")
out.append("|---|" + "---|" * (2 * len(servers)))
for name, key in COST:
    cells = []
    for s in servers:
        for m in "AB":
            cells.append(fmt([E[r["id"]][key] for r in rows(s, m) if r["id"] in E]))
    out.append(f"| {name} | " + " | ".join(cells) + " |")
out.append("")

out.append("### 요청별 — 세 회차 평균 (범위)\n")
out.append("| 요청 | 방식 | 조용한 기본값 | 조용한 추측값 | 해석 오류 | 기본값 없는 결정 건너뜀 | 범위 밖(최종) | 물은 기본값 | 질문 묶음 | 모델 호출 | MCP 호출 | 새 입력 | 캐시 읽기 | 초 | 끝 |")
out.append("|---|---|" + "---|" * 13)
for q in REQ:
    for m in "AB":
        rs = rows(m=m, req=q["id"])
        cell = lambda f: fmt([f(r) for r in rs])
        ends = sorted({r["state"] or "—" for r in rs})
        cost = lambda k: fmt([E[r["id"]][k] for r in rs if r["id"] in E])
        out.append(f"| {q['id']} | {m} | " + " | ".join(cell(f) for _, f in QUAL if _ not in ("지어낸 매개변수",)) +
                   f" | {cost('modelCalls')} | {cost('mcpCalls')} | {cost('inputNew')} | {cost('inputCacheRead')} | {cost('seconds')} | {' · '.join(ends)} |")
out.append("")
missing = [r["id"] for r in M if r.get("config") is None]
out.append(f"채점에서 빠진 실행(최종 설정 없음 · 다른 서버): {', '.join(missing) or '없음'}  ")
out.append(f"E3 기록이 없는 실행: {', '.join(r['id'] for r in M if r['id'] not in E) or '없음'}")
open(os.path.join(HERE, "summary.md"), "w", encoding="utf-8").write("\n".join(out) + "\n")
print("\n".join(out))
