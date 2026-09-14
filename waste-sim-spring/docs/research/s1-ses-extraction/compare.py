"""조건별 지표를 한 표로 모은다. score_ses.py를 다시 구현하지 않고 그것을 부른다.

사용법:  python compare.py C Cs Cd T T0
"""
import glob, os, re, subprocess, sys

KIT = os.path.dirname(os.path.abspath(__file__))
MODEL = os.environ.get("BENCH_MODEL", "gpt-4.1-mini")
conds = sys.argv[1:] or ["C", "Cs", "Cd"]

ROW = re.compile(r"^\s+(nodes|relations|attrs)\s+P (\S+)\s+R (\S+)\s+F1 (\S+)\s+\((\d+)/(\d+)")
JAC = re.compile(r"노드 Jaccard (\S+)\s+관계 Jaccard (\S+)")
CP = re.compile(r"^\s+임계점\s+(.*)$")

print(f"{'조건':<5}{'실행':>4}  {'노드 F1':>18} {'관계 F1':>18} {'속성 F1':>18} "
      f"{'노드Jac':>8} {'CP통과':>7}")
print("-" * 84)
for c in conds:
    pat = os.path.join(KIT, "runs", f"{MODEL}-{c}-*.json")
    files = sorted(glob.glob(pat))
    if not files:
        print(f"{c:<5}{'--':>4}  (실행 없음)")
        continue
    out = subprocess.run([sys.executable, os.path.join(KIT, "score_ses.py"), "--runs", pat],
                         capture_output=True, text=True, encoding="utf-8", cwd=KIT).stdout
    vals = {k: [] for k in ("nodes", "relations", "attrs")}
    hits = {k: [] for k in ("nodes", "relations", "attrs")}
    cps = []
    for ln in out.split("\n"):
        m = ROW.match(ln)
        if m:
            vals[m.group(1)].append(float(m.group(4)))
            hits[m.group(1)].append(int(m.group(5)))
        m = CP.match(ln)
        if m:
            cps.append(m.group(1).count("O"))
    j = JAC.search(out)

    def cell(k):
        v = vals[k]
        if not v:
            return "—"
        return f"{min(v):.3f}~{max(v):.3f} ({sum(hits[k])//len(hits[k])}개)"
    print(f"{c:<5}{len(files):>4}  {cell('nodes'):>18} {cell('relations'):>18} "
          f"{cell('attrs'):>18} {(j.group(1) if j else '—'):>8} "
          f"{(f'{min(cps)}~{max(cps)}/5' if cps else '—'):>7}")
print("\n(맞춘 개수는 실행 평균. 정답지 ref 판본이 바뀌면 값이 함께 바뀐다 — "
      "같은 표 안의 조건끼리만 비교한다)")
