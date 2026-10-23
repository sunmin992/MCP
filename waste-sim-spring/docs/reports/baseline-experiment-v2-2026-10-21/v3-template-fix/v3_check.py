"""V3 재실행 — 서버가 보관한 시나리오에서 새 항목 값 · 출처를 읽고, 같은 요청의 V2 실행과 비교한다."""
import json, glob, urllib.request

R = r"C:\Users\User\AppData\Local\Temp\claude\C--Dev-MCP\de88c4bd-2bc3-4d94-b163-9251f18bb1dc\scratchpad\v3\runs"
V2 = r"C:\Dev\MCP\waste-sim-spring\docs\reports\baseline-experiment-v2-2026-10-21\runs"
PORT = {"D": 8091, "J": 8090, "P": 8092}
KEY = {"D": "shiftMinutes", "J": "dischargeTimeMode", "P": "lotSpacingMinutes"}

for p in sorted(glob.glob(R + r"\V3-*.json")):
    r = json.load(open(p, encoding="utf-8"))
    s = r["id"].split("-")[1][0]
    sid = r["scenarioId"]
    sc = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT[s]}/api/scenarios/{sid}").read())
    json.dump(sc, open(rf"{R}\{sid}.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    runs = sc.get("runs", [])
    vals = sorted({str(x.get(KEY[s])) for x in runs})
    o = sc.get("origins", {})
    md = sum(1 for v in o.values() if v == "MODEL_DEFAULT")
    old = json.load(open(rf"{V2}\{r['id'].replace('V3', 'V2')}.json", encoding="utf-8"))
    print(r["id"], "| 브로커", [b["verdict"] for b in r["brokerCalls"]], "| 질문", len(r["questions"]),
          f"| {KEY[s]}={vals} 출처={o.get(KEY[s])}", "| 실행", len(runs), "| 미승인 기본값", md,
          "|| V2 브로커", [b["verdict"] for b in old["brokerCalls"]], "질문", len(old["questions"]), "상태", sc.get("state"))
