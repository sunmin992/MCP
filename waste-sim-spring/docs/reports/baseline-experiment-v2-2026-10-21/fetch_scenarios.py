"""runs/ 의 A 실행마다 그 서버의 GET /api/scenarios/{id} 를 받아 scenarios/ 에 둔다(없으면 missing 으로 적는다)."""
import json, glob, os, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
R = json.load(open(os.path.join(HERE, "requests.json"), encoding="utf-8"))
for p in sorted(glob.glob(os.path.join(HERE, "runs", "*-A-*.json"))):
    r = json.load(open(p, encoding="utf-8"))
    sid = r.get("scenarioId")
    port = R["servers"][r["id"].split("-")[1][0]]["port"]
    if not sid:
        print(r["id"], "no scenario"); continue
    try:
        d = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{port}/api/scenarios/{sid}", timeout=10).read().decode())
        json.dump(d, open(os.path.join(HERE, "scenarios", f"{sid}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(r["id"], sid, d["state"])
    except Exception as e:
        print(r["id"], sid, "missing", e)
