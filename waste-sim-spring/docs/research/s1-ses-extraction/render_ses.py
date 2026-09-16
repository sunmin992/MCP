"""참조 SES를 읽을 수 있는 HTML로 렌더한다. 손으로 그리지 않는다 — 곧 어긋난다.

  ses-tree.html      SES 트리 + 결합
  ses-mapping.html   구성 결정 34개 -> SES 자리 (evidence의 "문항 N"에서 유도)

PNG는 headless Chrome으로 뽑는다:
  python render_ses.py --png
"""
import collections, html, io, json, os, re, subprocess, sys

KIT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(KIT, "..", "..", ".."))
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
ref = json.load(io.open(os.path.join(KIT, "reference-ses.json"), encoding="utf-8"))
E, EV = ref["entities"], ref["evidence"]

CSS = """
:root{--ink:#14171a;--dim:#6b7480;--line:#dde2e8;--bg:#fbfcfd;--panel:#fff;
      --asp:#1f6feb;--spec:#8250df;--multi:#1a7f5a;--attr:#eef1f5;--warn:#c0392b}
*{box-sizing:border-box}
body{background:var(--bg);color:var(--ink);margin:0;padding:26px 30px;width:1180px;
     font:14px/1.55 "Malgun Gothic","맑은 고딕",-apple-system,Segoe UI,sans-serif}
h1{font-size:24px;margin:0 0 3px;letter-spacing:-.4px}
.sub{color:var(--dim);font-size:12.5px;margin-bottom:18px}
.legend{display:flex;gap:16px;margin:0 0 16px;font-size:12px;color:var(--dim);
        border-bottom:1px solid var(--line);padding-bottom:12px;flex-wrap:wrap}
.k{display:inline-block;padding:1px 7px;border-radius:4px;font-size:10.5px;font-weight:700;
   letter-spacing:.4px;color:#fff;vertical-align:1px}
.k.aspect{background:var(--asp)}.k.spec{background:var(--spec)}.k.multi{background:var(--multi)}
.tree{background:var(--panel);border:1px solid var(--line);border-radius:9px;padding:16px 18px}
ul{list-style:none;margin:0;padding-left:17px}
ul.root{padding-left:0}
li{position:relative;padding:1.5px 0}
li::before{content:"";position:absolute;left:-11px;top:13px;width:8px;height:1px;background:var(--line)}
ul.root>li::before{display:none}
ul:not(.root)::before{content:"";position:absolute}
.ent{font-weight:600}
.axis{color:var(--dim);font-size:11.5px;margin-left:5px}
.attrs{margin-left:6px}
.at{display:inline-block;background:var(--attr);border-radius:3px;padding:0 5px;margin:0 2px 1px 0;
    font-size:11px;color:#38414c}
.at.new{background:#e6f4ec;color:#14603f;font-weight:600}
.noev{color:var(--warn);font-size:10.5px;margin-left:4px}
.spec-tag{display:inline-block;margin-left:4px;padding:0 4px;border-radius:2px;
          background:#dbe7fb;color:#1a4d9c;font-size:9.5px;font-weight:700;letter-spacing:.2px}
.cond{display:inline-block;background:#fdf0ee;color:#a5341f;border:1px solid #f0c8c1;
      border-radius:3px;padding:0 5px;font-size:10.5px;font-weight:600}
h2{font-size:13px;letter-spacing:.8px;color:var(--dim);text-transform:uppercase;
   margin:22px 0 9px;font-weight:700}
table{border-collapse:collapse;width:100%;font-size:12.5px;background:var(--panel)}
th,td{border-bottom:1px solid var(--line);padding:5px 8px;text-align:left;vertical-align:top}
th{color:var(--dim);font-size:11px;letter-spacing:.5px;text-transform:uppercase;font-weight:700}
td.n{text-align:right;font-variant-numeric:tabular-nums;color:var(--dim);width:30px}
code{background:var(--attr);padding:0 4px;border-radius:3px;font-size:11.5px}
.flow{font-family:Consolas,"D2Coding",monospace;font-size:12px}
.out{color:var(--warn);font-weight:600}
footer{margin-top:18px;padding-top:10px;border-top:1px solid var(--line);
       color:var(--dim);font-size:11.5px}
"""

# ref-v5에서 새로 앉힌 속성 — 초록으로 표시한다
NEW_ATTRS = {"개수", "직업구성", "배출량변동", "외출시각변동", "초기적재량", "배차간격",
             "구간이동시간", "구역내이동시간", "구역배정가정", "배출허용창", "교통패널티"}


def attrs_html(name):
    out = []
    spec = E[name].get("attr_spec") or {}
    for a in E[name].get("attrs") or []:
        cls = "at new" if a in NEW_ATTRS else "at"
        sp = spec.get(a)
        tag = ""
        if sp:
            # attr_spec — 이름만으로 모호한 자리에 붙는 성질(ref-v7 확장)
            bits = [sp.get("kind", "")]
            if sp.get("arity"):
                bits.append(sp["arity"])
            if sp.get("over"):
                bits.append("over " + sp["over"])
            tag = f'<span class="spec-tag">{html.escape(" · ".join(b for b in bits if b))}</span>'
        out.append(f'<span class="{cls}">{html.escape(a)}{tag}</span>')
    return f'<span class="attrs">{"".join(out)}</span>' if out else ""


def node(name, seen):
    if name in seen:                       # 순환 방어
        return f"<li><span class='ent'>{html.escape(name)}</span> <span class='axis'>(재등장)</span></li>"
    seen = seen | {name}
    has_ev = name in EV or any(k.startswith(name + ".") for k in EV)
    mark = "" if has_ev else '<span class="noev">근거 없음</span>'
    s = [f"<li><span class='ent'>{html.escape(name)}</span>{mark}{attrs_html(name)}"]
    for dec in E[name].get("decompositions") or []:
        kind = dec["kind"]
        s.append(f"<ul><li><span class='k {kind}'>{kind}</span>"
                 f"<span class='axis'>{html.escape(dec.get('name',''))}</span><ul>")
        for c in dec["children"]:
            s.append(node(c, seen) if c in E else f"<li>{html.escape(c)} <span class='noev'>정의 없음</span></li>")
        s.append("</ul></li></ul>")
    s.append("</li>")
    return "".join(s)


def tree_page():
    attrs = sum(len(b.get("attrs") or []) for b in E.values())
    body = [f"<title>장량동 시뮬레이터 SES</title><style>{CSS}</style>",
            "<h1>장량동 생활쓰레기 수거 시뮬레이터 — SES</h1>",
            f"<div class='sub'>{ref['_meta']['version']} · "
            f"엔티티 {len(E)} · 속성 {attrs} · 결합 {len(ref['couplings'])} · "
            f"근거 {len(EV)}건 · 검증 범위는 <code>_meta.verified_against</code> · "
            f"정답지는 <code>reference-ses.json</code></div>",
            "<div class='legend'>"
            "<span><span class='k aspect'>aspect</span> 함께 존재하며 동시에 돈다</span>"
            "<span><span class='k spec'>spec</span> 자식 중 하나를 고른다</span>"
            "<span><span class='k multi'>multi</span> 동종 개체 여럿을 복제한다</span>"
            "<span><span class='at'>속성</span> · <span class='at new'>ref-v5에서 앉힌 속성</span></span>"
            "</div>",
            f"<div class='tree'><ul class='root'>{node(ref['root'], frozenset())}</ul></div>",
            "<h2>결합 — 개체 사이에 오가는 것</h2><table>",
            "<tr><th>from</th><th>to</th><th>활성 조건</th><th>코드에서의 기제</th></tr>"]
    for c in ref["couplings"]:
        aw = c.get("active_when")
        # active_when 이 없으면 항상 활성이다(ref-v7 확장의 기본값)
        cond = f"<span class='cond'>{html.escape(aw.split(' — ')[0])}</span>" if aw else \
               "<span style='color:#9aa3ad'>항상</span>"
        body.append(f"<tr><td class='flow'>{html.escape(c['from'])}</td>"
                    f"<td class='flow'>{html.escape(c['to'])}</td>"
                    f"<td>{cond}</td>"
                    f"<td>{html.escape(c.get('mechanism',''))}</td></tr>")
    body.append("</table>")
    body.append("<h2>임계점 — 틀리면 의미가 바뀌는 자리</h2><table>"
                "<tr><th>id</th><th>어디</th><th>무엇이어야 하나</th></tr>")
    for c in ref["critical_points"]:
        body.append(f"<tr><td><code>{c['id']}</code></td><td>{html.escape(c['where'])}</td>"
                    f"<td>{html.escape(c['must_be'])}</td></tr>")
    body.append("</table>")
    body.append(f"<footer>{html.escape(ref['_meta']['status'])}</footer>")
    return "\n".join(body)


def mapping_page():
    sub = json.load(io.open(os.path.join(
        REPO, "src", "main", "resources", "subtask", "jangnyang-simulator-v4.json"), encoding="utf-8"))
    qs = []

    def walk(o):
        if isinstance(o, dict):
            if "answerField" in o:
                qs.append((o.get("order"), o["answerField"], o.get("answerType")))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(sub)
    qs.sort()

    # evidence 값의 "문항 N" 에서 대응을 유도한다 — 표를 따로 적지 않는다
    placed = collections.defaultdict(list)
    for slot, e in EV.items():
        for m in re.finditer(r"문항 (\d+)", e):
            placed[int(m.group(1))].append(slot)
    # 결합의 active_when 도 자리다 — trafficMode 처럼 결합을 켜고 끄는 결정이 여기 앉는다.
    for c in ref["couplings"]:
        aw = c.get("active_when") or ""
        for m in re.finditer(r"문항 (\d+)", aw):
            placed[int(m.group(1))].append(f"결합 활성 조건: {c['from']} -> {c['to']}")
    # attr_spec 의 evidence 도 훑는다
    for ent, b in E.items():
        for a, sp in (b.get("attr_spec") or {}).items():
            for m in re.finditer(r"문항 (\d+)", sp.get("evidence", "")):
                placed[int(m.group(1))].append(f"{ent}.{a} (attr_spec)")

    EXCLUDED = {
        1: "기록용 — 빌더가 “계산에 쓰이지 않는 항목”이라고 적는다",
        3: "실행 수단 — java/python 어댑터 선택. 대상 시스템의 성질이 아니다",
        32: "구성 절차의 제어", 33: "구성 절차의 제어", 34: "구성 절차의 제어",
    }
    hit = sum(1 for o, _, _ in qs if placed.get(o))
    body = [f"<title>구성 결정 ↔ SES</title><style>{CSS}</style>",
            "<h1>구성 결정 34개가 SES의 어디에 앉는가</h1>",
            f"<div class='sub'>문항 세트 <code>jangnyang-simulator-v4</code> · {ref['_meta']['version']} · "
            f"자리를 가진 것 <b>{hit}</b> / 34 · 대조 전에는 4개였다 · "
            f"대응 근거는 <code>JangnyangScenarioBuilder.toConfig()</code>의 소비 지점</div>",
            "<table><tr><th class='n'>#</th><th>문항 필드</th><th>타입</th>"
            "<th>SES 자리 · 또는 넣지 않은 사유</th></tr>"]
    for o, f, t in qs:
        slots = placed.get(o)
        if slots:
            cell = " · ".join(f"<code>{html.escape(s)}</code>" for s in slots)
        elif o in EXCLUDED:
            cell = f"<span class='out'>—</span> {html.escape(EXCLUDED[o])}"
        else:
            cell = "<span class='out'>미배치</span>"
        body.append(f"<tr><td class='n'>{o}</td><td><code>{html.escape(f)}</code></td>"
                    f"<td style='color:#6b7480;font-size:11.5px'>{html.escape(t or '')}</td>"
                    f"<td>{cell}</td></tr>")
    body.append("</table>")
    body.append("<footer>분해 축으로 앉은 것(<code>scenarioType</code> → 실험 유형 축, "
                "<code>dischargeTimeMode</code> → 배출시각 모델 축, <code>truckType</code> → 차종 축, "
                "<code>travelTimeMode</code> → 이동시간 방식 축)은 속성이 아니라 spec 축이므로 "
                "evidence가 노드 쪽에 붙어 이 표에서는 빈칸으로 보일 수 있다 — "
                "구성결정-SES-대응표.md의 표가 정본이다.</footer>")
    return "\n".join(body)


for name, gen, h in (("ses-tree", tree_page, 2300), ("ses-mapping", mapping_page, 1500)):
    p = os.path.join(KIT, name + ".html")
    io.open(p, "w", encoding="utf-8").write(gen())
    print("  " + name + ".html")
    if "--png" in sys.argv:
        png = os.path.join(KIT, name + ".png")
        if os.path.exists(png):
            os.remove(png)
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=2", f"--window-size=1240,{h}",
                        f"--screenshot={png}", "file:///" + p.replace("\\", "/")],
                       capture_output=True, timeout=180)
        if os.path.exists(png):
            print(f"     -> {name}.png  {os.path.getsize(png)//1024} KB")
